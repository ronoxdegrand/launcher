package dev.glgra.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Debug-only fixture. Its cleanup touches only its own three tagged notification IDs. */
public final class NotificationProbe extends BroadcastReceiver {
    public static final String ACTION_POST = "dev.glgra.launcher.PROBE_POST";
    public static final String ACTION_CLEAR = "dev.glgra.launcher.PROBE_CLEAR";
    public static final String ACTION_INVOKED = "dev.glgra.launcher.PROBE_ACTION";
    static final String TAG = "launcher-integration-probe", CHANNEL = "launcher-integration-probe";
    static final String BODY_ONE = "Read-only probe body one. Expanding this text must not open or dismiss its notification.";
    static final String BODY_TWO = "Read-only probe body two. This is another active notification, not a message count.";
    static final int SUMMARY_ID = 904100, FIRST_ID = 904101, SECOND_ID = 904102;
    @Override public void onReceive(Context context, Intent intent) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (ACTION_INVOKED.equals(intent.getAction())) {
            android.content.SharedPreferences state = context.getSharedPreferences("notification-probe", Context.MODE_PRIVATE);
            state.edit().putInt("probeActions", state.getInt("probeActions", 0) + 1).commit(); return;
        }
        if (ACTION_CLEAR.equals(intent.getAction())) {
            manager.cancel(TAG, SUMMARY_ID); manager.cancel(TAG, FIRST_ID); manager.cancel(TAG, SECOND_ID); return;
        }
        if (!ACTION_POST.equals(intent.getAction())) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Integration test", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null); channel.enableVibration(false); manager.createNotificationChannel(channel);
        PendingIntent action = PendingIntent.getBroadcast(context, FIRST_ID,
                new Intent(context, NotificationProbe.class).setAction(ACTION_INVOKED),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(TAG, SUMMARY_ID, base(context, action).setContentTitle("Probe summary").setGroupSummary(true).build());
        manager.notify(TAG, FIRST_ID, base(context, action).setContentTitle("Probe one").setContentText(BODY_ONE)
                .setStyle(new Notification.BigTextStyle().bigText(BODY_ONE)).build());
        manager.notify(TAG, SECOND_ID, base(context, action).setContentTitle("Probe two").setContentText(BODY_TWO)
                .setStyle(new Notification.BigTextStyle().bigText(BODY_TWO)).build());
    }
    private Notification.Builder base(Context context, PendingIntent action) {
        return new Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setGroup(TAG).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(action).addAction(new Notification.Action.Builder(null, "Probe action", action).build());
    }
}
