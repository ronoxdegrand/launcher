package dev.glgra.launcher;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only, process-local projection of system notification events. No notification actions are invoked. */
public final class NotificationRepository extends NotificationListenerService {
    private static final Map<String, StatusBarNotification> ACTIVE = new LinkedHashMap<>();
    private static final Set<Runnable> LISTENERS = new HashSet<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean connected;
    private static boolean scheduled;

    public static boolean accessGranted(Context context) {
        return context.getSystemService(NotificationManager.class).isNotificationListenerAccessGranted(
                new ComponentName(context, NotificationRepository.class));
    }
    public static synchronized boolean connected() { return connected; }
    public static synchronized void addListener(Runnable callback) { LISTENERS.add(callback); }
    public static synchronized void removeListener(Runnable callback) { LISTENERS.remove(callback); }

    /** Summaries with visible children are omitted so badges count active records once. */
    public static synchronized List<StatusBarNotification> snapshot() {
        Set<String> childGroups = new HashSet<>();
        for (StatusBarNotification record : ACTIVE.values()) {
            if (!promoted(record) && record.isGroup() && !summary(record)) childGroups.add(group(record));
        }
        List<StatusBarNotification> result = new ArrayList<>();
        for (StatusBarNotification record : ACTIVE.values()) {
            if (promoted(record)) continue;
            if (summary(record) && childGroups.contains(group(record))) continue;
            result.add(record);
        }
        result.sort(Comparator.comparingLong(StatusBarNotification::getPostTime).reversed());
        return result;
    }
    public static Map<String, Integer> counts() {
        Map<String, Integer> counts = new HashMap<>();
        for (StatusBarNotification record : snapshot()) counts.merge(record.getPackageName(), 1, Integer::sum);
        return counts;
    }
    private static String group(StatusBarNotification record) {
        return record.getUser().hashCode() + ":" + record.getPackageName() + ":" + record.getGroupKey();
    }
    private static boolean summary(StatusBarNotification record) {
        return (record.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0;
    }
    private static boolean promoted(StatusBarNotification record) {
        // Ongoing notifications are retained. Only the explicit system promotion signal is reliable.
        return Build.VERSION.SDK_INT >= 36 && (record.getNotification().flags & Notification.FLAG_PROMOTED_ONGOING) != 0;
    }
    private static synchronized void changed() {
        if (scheduled) return;
        scheduled = true;
        MAIN.post(() -> {
            List<Runnable> callbacks;
            synchronized (NotificationRepository.class) { scheduled = false; callbacks = new ArrayList<>(LISTENERS); }
            for (Runnable callback : callbacks) callback.run();
        });
    }
    @Override public void onListenerConnected() {
        super.onListenerConnected();
        StatusBarNotification[] records;
        try { records = getActiveNotifications(); } catch (SecurityException e) { records = null; }
        synchronized (NotificationRepository.class) {
            ACTIVE.clear(); connected = true;
            if (records != null) for (StatusBarNotification record : records) ACTIVE.put(record.getKey(), record);
        }
        changed();
    }
    @Override public void onListenerDisconnected() {
        synchronized (NotificationRepository.class) { ACTIVE.clear(); connected = false; }
        changed(); super.onListenerDisconnected();
    }
    @Override public void onNotificationPosted(StatusBarNotification record) {
        if (record == null) return;
        synchronized (NotificationRepository.class) { ACTIVE.put(record.getKey(), record); }
        changed();
    }
    @Override public void onNotificationRemoved(StatusBarNotification record) {
        if (record == null) return;
        synchronized (NotificationRepository.class) { ACTIVE.remove(record.getKey()); }
        changed();
    }
    @Override public void onDestroy() {
        synchronized (NotificationRepository.class) { ACTIVE.clear(); connected = false; }
        changed(); super.onDestroy();
    }
}
