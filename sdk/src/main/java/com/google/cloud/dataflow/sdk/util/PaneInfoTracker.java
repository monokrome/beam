/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.dataflow.sdk.util;

import com.google.cloud.dataflow.sdk.transforms.windowing.AfterWatermark;
import com.google.cloud.dataflow.sdk.transforms.windowing.PaneInfo;
import com.google.cloud.dataflow.sdk.transforms.windowing.PaneInfo.PaneInfoCoder;
import com.google.cloud.dataflow.sdk.transforms.windowing.PaneInfo.Timing;
import com.google.cloud.dataflow.sdk.util.state.StateContents;
import com.google.cloud.dataflow.sdk.util.state.StateContext;
import com.google.cloud.dataflow.sdk.util.state.StateTag;
import com.google.cloud.dataflow.sdk.util.state.StateTags;
import com.google.cloud.dataflow.sdk.util.state.ValueState;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

import org.joda.time.Instant;

/**
 * Determine the timing and other properties of a new pane for a given computation, key and window.
 * Incorporates any previous pane, whether the pane has been produced because an
 * on-time {@link AfterWatermark} trigger firing, and the relation between the element's timestamp
 * and the current output watermark.
 */
public class PaneInfoTracker {
  private TimerInternals timerInternals;

  public PaneInfoTracker(TimerInternals timerInternals) {
    this.timerInternals = timerInternals;
  }

  @VisibleForTesting
  static final StateTag<Object, ValueState<PaneInfo>> PANE_INFO_TAG =
      StateTags.makeSystemTagInternal(StateTags.value("pane", PaneInfoCoder.INSTANCE));

  public void clear(StateContext<?> state) {
    state.access(PANE_INFO_TAG).clear();
  }

  /**
   * Return a (future for) the pane info appropriate for {@code context}. The pane info
   * includes the timing for the pane, who's calculation is quite subtle.
   *
   * @param isWatermarkTrigger should be {@code true} only if the pane is being emitted
   * because a {@link AfterWatermark#pastEndOfWindow} trigger has fired.
   * @param isFinal should be {@code true} only if the triggering machinery can guarantee
   * no further firings for the
   */
  public StateContents<PaneInfo> getNextPaneInfo(ReduceFn<?, ?, ?, ?>.Context context,
      final boolean isWatermarkTrigger, final boolean isFinal) {
    final Object key = context.key();
    final StateContents<PaneInfo> previousPaneFuture =
        context.state().access(PaneInfoTracker.PANE_INFO_TAG).get();
    final Instant windowMaxTimestamp = context.window().maxTimestamp();

    return new StateContents<PaneInfo>() {
      @Override
      public PaneInfo read() {
        PaneInfo previousPane = previousPaneFuture.read();
        return describePane(key, windowMaxTimestamp, previousPane, isWatermarkTrigger, isFinal);
      }
    };
  }

  public void storeCurrentPaneInfo(ReduceFn<?, ?, ?, ?>.Context context, PaneInfo currentPane) {
    context.state().access(PANE_INFO_TAG).set(currentPane);
  }

  private <W> PaneInfo describePane(Object key, Instant windowMaxTimestamp, PaneInfo previousPane,
      boolean isWatermarkTrigger, boolean isFinal) {
    boolean isFirst = previousPane == null;
    Timing previousTiming = isFirst ? null : previousPane.getTiming();
    long index = isFirst ? 0 : previousPane.getIndex() + 1;
    long nonSpeculativeIndex = isFirst ? 0 : previousPane.getNonSpeculativeIndex() + 1;
    Instant outputWM = timerInternals.currentOutputWatermarkTime();
    Instant inputWM = timerInternals.currentInputWatermarkTime();

    // True if it is not possible to assign the element representing this pane a timestamp
    // which will make an ON_TIME pane for any following computation.
    // Ie true if the element's latest possible timestamp is before the current output watermark.
    boolean isLateForOutput = outputWM != null && windowMaxTimestamp.isBefore(outputWM);

    // True if all emitted panes (if any) were EARLY panes.
    // Once the ON_TIME pane has fired, all following panes must be considered LATE even
    // if the output watermark is behind the end of the window.
    boolean onlyEarlyPanesSoFar = previousTiming == null || previousTiming == Timing.EARLY;

    Timing timing;
    if (isLateForOutput || !onlyEarlyPanesSoFar) {
      // The output watermark has already passed the end of this window, or we have already
      // emitted a non-EARLY pane. Irrespective of how this pane was triggered we must
      // consider this pane LATE.
      timing = Timing.LATE;
    } else if (isWatermarkTrigger) {
      // This is the unique ON_TIME firing for the window.
      timing = Timing.ON_TIME;
    } else {
      // All other cases are EARLY.
      timing = Timing.EARLY;
      nonSpeculativeIndex = -1;
    }

    WindowTracing.debug(
        "describePane: {} pane (prev was {}) for key:{}; windowMaxTimestamp:{}; "
        + "inputWatermark:{}; outputWatermark:{}; isWatermarkTrigger:{}; isLateForOutput:{}",
        timing, previousTiming, key, windowMaxTimestamp, inputWM, outputWM, isWatermarkTrigger,
        isLateForOutput);

    if (previousPane != null) {
      // Timing transitions should follow EARLY* ON_TIME? LATE*
      switch (previousTiming) {
        case EARLY:
          Preconditions.checkState(
              timing == Timing.EARLY || timing == Timing.ON_TIME || timing == Timing.LATE,
              "EARLY cannot transition to %s", timing);
          break;
        case ON_TIME:
          Preconditions.checkState(
              timing == Timing.LATE, "ON_TIME cannot transition to %s", timing);
          break;
        case LATE:
          Preconditions.checkState(timing == Timing.LATE, "LATE cannot transtion to %s", timing);
          break;
        case UNKNOWN:
          break;
      }
      Preconditions.checkState(!previousPane.isLast(), "Last pane was not last after all.");
    }

    return PaneInfo.createPane(isFirst, isFinal, timing, index, nonSpeculativeIndex);
  }
}
