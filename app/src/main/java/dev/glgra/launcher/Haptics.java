package dev.glgra.launcher;

import android.content.Context;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.view.View;

final class Haptics {
    static void tick(View view, int level) {
        if (level == 0 || Settings.System.getInt(view.getContext().getContentResolver(), Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return;
        Vibrator vibrator = view.getContext().getSystemService(VibratorManager.class).getDefaultVibrator();
        if (!vibrator.hasVibrator()) return;
        if (vibrator.hasAmplitudeControl()) vibrator.vibrate(VibrationEffect.createOneShot(8, new int[]{0, 35, 90, 180}[Math.min(3, level)]),
                new android.os.VibrationAttributes.Builder().setUsage(android.os.VibrationAttributes.USAGE_TOUCH).build());
        else view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
    }
}
