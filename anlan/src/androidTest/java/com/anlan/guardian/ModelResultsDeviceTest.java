package com.anlan.guardian;

import android.animation.ValueAnimator;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Arrays;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** 使用真实气泡视图及可控单调时间，覆盖限频、排队和页面生命周期。 */
public class ModelResultsDeviceTest {
    private ModelResultsView view;
    @Before public void create() {
        main(() -> view = new ModelResultsView(InstrumentationRegistry.getInstrumentation().getTargetContext()));
    }
    @After public void clear() { main(() -> view.onDetachedFromWindow()); }

    @Test public void transientSecondSoundSurvivesThrottleAndNewSnapshot() {
        main(() -> {
            view.render(snapshot("Speech"), 10000);
            view.render(snapshot("Buzzer"), 10480);
            assertEquals(1, view.getChildCount());
            view.render(snapshot("Speech"), 10960);
            assertEquals(2, view.getChildCount());
            assertEquals("Buzzer", name(1));
        });
    }

    @Test public void oneSnapshotDrainsAllThreeSoundsWithoutRepeatingOldEvidence() {
        main(() -> {
            GuardianRuntime.Snapshot snapshot = snapshot("Speech", "Buzzer", "Beep, bleep");
            view.render(snapshot, 10000);
            view.render(snapshot, 10750);
            view.render(snapshot, 11500);
            assertEquals(3, view.getChildCount());
            assertEquals("Speech", name(0)); assertEquals("Buzzer", name(1)); assertEquals("Beep, bleep", name(2));
            view.render(snapshot, 13000);
            assertEquals("同一旧快照不应重新生成气泡", 3, view.getChildCount());
        });
    }

    @Test public void updatesKeepWaitingOrderAndLatestScore() {
        main(() -> {
            view.render(snapshot("Speech", "Buzzer", "Beep, bleep"), 10000);
            view.render(withResults(new GuardianRuntime.ModelResult("Buzzer", "Buzzer", .7f)), 10480);
            view.render(snapshot("Speech"), 10960);
            assertEquals("Buzzer", name(1));
            assertEquals("70", ((TextView)((LinearLayout)view.getChildAt(1)).getChildAt(1)).getText().toString());
        });
    }

    @Test public void waitingSoundsExpireInsteadOfShowingAfterLongPause() {
        main(() -> {
            GuardianRuntime.Snapshot snapshot = snapshot("Speech", "Buzzer");
            view.render(snapshot, 10000);
            view.render(snapshot, 13200);
            assertEquals(1, view.getChildCount());
        });
    }

    @Test public void queueIsBoundedAndDoesNotReplaceOlderWaitingSound() {
        main(() -> {
            view.render(snapshot("Speech"), 10000);
            for (int i = 0; i < 30; i++) view.render(snapshot("sound" + i), 10001 + i);
            try {
                java.lang.reflect.Field field = ModelResultsView.class.getDeclaredField("pending"); field.setAccessible(true);
                assertEquals(12, ((java.util.Map<?, ?>)field.get(view)).size());
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            view.render(snapshot("Speech"), 10750);
            assertEquals("sound0", name(1));
        });
    }

    @Test public void visibleLimitRetainsFreshPendingUntilSlotAvailable() {
        main(() -> {
            for (int i = 0; i < 6; i++) view.render(snapshot("sound" + i), 10000 + i * 500);
            GuardianRuntime.Snapshot snapshot = snapshot("Buzzer");
            view.render(snapshot, 13000);
            assertEquals(6, view.getChildCount());
            ((ValueAnimator)view.getChildAt(0).getTag()).end();
            view.render(snapshot, 13400);
            assertEquals(6, view.getChildCount());
            assertEquals("Buzzer", name(5));
        });
    }

    @Test public void hideDetachAndStopDiscardPendingSounds() {
        main(() -> {
            for (int mode = 0; mode < 3; mode++) {
                view.render(snapshot("Speech", "Buzzer"), 10000);
                // 视图未挂载窗口，显式投递窗口生命周期回调。
                if (mode == 0) view.onVisibilityChanged(view, View.GONE);
                else if (mode == 1) view.onDetachedFromWindow();
                else view.render(new GuardianRuntime.Snapshot(GuardianRuntime.Status.STOPPED,
                    Collections.emptyList(), "", "", 0, -96, false), 10480);
                view.render(snapshot("Music"), 10960);
                assertEquals("清理模式" + mode, 1, view.getChildCount()); assertEquals("Music", name(0));
                view.onDetachedFromWindow();
            }
        });
    }

    @Test public void repeatCooldownAndThresholdRemainInEffect() {
        main(() -> {
            view.render(snapshot("Speech"), 10000);
            view.render(snapshot("Speech"), 11000);
            view.render(withResults(new GuardianRuntime.ModelResult("Buzzer", "Buzzer", .24f)), 12000);
            assertEquals(1, view.getChildCount());
            view.render(snapshot("Speech"), 12400);
            assertEquals(1, view.getChildCount());
            view.render(snapshot("Speech"), 14800);
            assertEquals(2, view.getChildCount());
        });
    }

    @Test public void sixSimultaneousLabelsDrainWithoutBeingCrowdedOut() {
        main(() -> {
            GuardianRuntime.Snapshot snapshot = snapshot("Speech", "Animal", "Dog", "Knock", "Glass", "Water");
            for (int i = 0; i < 6; i++) view.render(snapshot, 10000 + i * 500);
            assertEquals(6, view.getChildCount());
            assertEquals("Glass", name(4)); assertEquals("Water", name(5));
        });
    }

    @Test public void selectorKeepsFourthAndFifthLabelsAndRejectsInvalidScores() throws Exception {
        com.soundguardian.core.AppConfig config = new com.soundguardian.core.AppConfig(
            InstrumentationRegistry.getInstrumentation().getTargetContext());
        float[] scores = new float[config.labels.length];
        for (int i = 0; i < 5; i++) scores[i] = .9f - i * .1f;
        scores[5] = Float.NaN; scores[6] = Float.POSITIVE_INFINITY; scores[7] = .24f;
        java.lang.reflect.Method method = GuardianService.class.getDeclaredMethod("modelResults", float[].class,
            com.soundguardian.core.AppConfig.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked") java.util.List<GuardianRuntime.ModelResult> results =
            (java.util.List<GuardianRuntime.ModelResult>)method.invoke(new GuardianService(), scores, config);
        assertEquals(5, results.size());
        assertEquals(config.labels[3], results.get(3).label); assertEquals(config.labels[4], results.get(4).label);
    }

    private String name(int index) {
        return ((TextView)((LinearLayout)view.getChildAt(index)).getChildAt(0)).getText().toString();
    }
    private static void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private static GuardianRuntime.Snapshot snapshot(String... labels) {
        return withResults(Arrays.stream(labels).map(label -> new GuardianRuntime.ModelResult(label, label, .9f))
            .toArray(GuardianRuntime.ModelResult[]::new));
    }
    private static GuardianRuntime.Snapshot withResults(GuardianRuntime.ModelResult... results) {
        return new GuardianRuntime.Snapshot(GuardianRuntime.Status.LISTENING, Collections.emptyList(),
            Arrays.asList(results), "", "", "", 0, 0, -20, false);
    }
}
