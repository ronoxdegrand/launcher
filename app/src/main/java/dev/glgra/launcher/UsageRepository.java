package dev.glgra.launcher;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.os.UserManager;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/** Event-driven local foreground-duration summaries. refresh() is called on a worker on relevant UI events. */
final class UsageRepository {
    static final long HOUR = 60 * 60 * 1000L, DAY = 24 * HOUR, HISTORY = 14 * DAY;
    private static final Object STORAGE_LOCK = new Object();
    private final Context context;
    private final SharedPreferences prefs;
    private volatile Map<String, Double> scores = Map.of();
    private long lastRefresh;
    UsageRepository(Context context) {
        this.context = context.getApplicationContext();
        prefs = context.getSharedPreferences("usage-summaries", Context.MODE_PRIVATE);
    }
    boolean hasAccess() {
        return context.getSystemService(AppOpsManager.class).unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }
    double score(String packageName) { return scores.getOrDefault(packageName, 0d); }
    void refresh() {
        if (!hasAccess()) { scores = Map.of(); return; }
        if (!context.getSystemService(UserManager.class).isUserUnlocked()) return;
        synchronized (STORAGE_LOCK) {
            long now = System.currentTimeMillis();
            if (lastRefresh > 0 && now >= lastRefresh && now - lastRefresh < 60_000) return;
            UsageStatsManager manager = context.getSystemService(UsageStatsManager.class);
            try {
                JSONObject state = new JSONObject(prefs.getString("state", "{}"));
                JSONObject buckets = state.optJSONObject("hours"); if (buckets == null) buckets = new JSONObject();
                JSONObject active = state.optJSONObject("active"); if (active == null) active = new JSONObject();
                // Query only a conservative raw-event window. Older unknown gaps cannot be reconstructed as zero use.
                long end = now - 2000, checkpoint = state.optLong("checkpoint", end - 3 * DAY);
                if (checkpoint > end || checkpoint < end - 3 * DAY) { checkpoint = end - 3 * DAY; active = new JSONObject(); }
                UsageEvents events = manager.queryEvents(checkpoint, end);
                if (events == null) return; // Locked/unavailable: preserve both the checkpoint and cached ranks.
                long cursor = checkpoint;
                UsageEvents.Event event = new UsageEvents.Event();
                while (events.hasNextEvent()) {
                    events.getNextEvent(event);
                    long time = event.getTimeStamp();
                    if (time < cursor || time >= end) continue;
                    accrue(buckets, active, cursor, time); cursor = time;
                    String packageName = event.getPackageName();
                    String activity = packageName + "/" + String.valueOf(event.getClassName());
                    switch (event.getEventType()) {
                        case UsageEvents.Event.ACTIVITY_RESUMED:
                            if (packageName != null) active.put(activity, packageName); break;
                        case UsageEvents.Event.ACTIVITY_PAUSED:
                        case UsageEvents.Event.ACTIVITY_STOPPED:
                            active.remove(activity); break;
                        case UsageEvents.Event.SCREEN_NON_INTERACTIVE:
                        case UsageEvents.Event.DEVICE_SHUTDOWN:
                        case UsageEvents.Event.DEVICE_STARTUP:
                            active = new JSONObject(); break;
                    }
                }
                accrue(buckets, active, cursor, end);
                Iterator<String> keys = buckets.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    try { if (Long.parseLong(key) + HOUR <= now - HISTORY || Long.parseLong(key) > now) keys.remove(); }
                    catch (NumberFormatException corrupt) { keys.remove(); }
                }
                state.put("checkpoint", end).put("hours", buckets).put("active", active);
                // Data + checkpoint form a single committed record; repeating refresh never double counts a window.
                if (!prefs.edit().putString("state", state.toString()).commit()) return;
                Map<String, Long> fallback = new HashMap<>();
                List<UsageStats> aggregate = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - HISTORY, now);
                if (aggregate != null) for (UsageStats stats : aggregate) {
                    long amount = Math.max(0, Math.min(HISTORY, stats.getTotalTimeInForeground()));
                    fallback.merge(stats.getPackageName(), amount, Long::sum);
                }
                scores = rank(buckets, fallback, now); lastRefresh = now;
            } catch (org.json.JSONException | SecurityException | IllegalStateException unavailable) {
                // Do not overwrite a valid checkpoint or cached ranks with an unavailable query.
            }
        }
    }
    private static void accrue(JSONObject buckets, JSONObject active, long start, long end) throws org.json.JSONException {
        if (end <= start || active.length() == 0) return;
        Set<String> packages = new HashSet<>();
        Iterator<String> activities = active.keys();
        while (activities.hasNext()) { String pkg = active.optString(activities.next(), ""); if (!pkg.isEmpty()) packages.add(pkg); }
        // Count each package once, including when two of its activities are simultaneously resumed.
        for (long cursor = start; cursor < end;) {
            long hour = Math.floorDiv(cursor, HOUR) * HOUR, finish = Math.min(end, hour + HOUR);
            String key = Long.toString(hour); JSONObject amounts = buckets.optJSONObject(key);
            if (amounts == null) { amounts = new JSONObject(); buckets.put(key, amounts); }
            for (String pkg : packages) amounts.put(pkg, Math.min(HOUR, amounts.optLong(pkg) + finish - cursor));
            cursor = finish;
        }
    }
    private static Map<String, Double> rank(JSONObject buckets, Map<String, Long> fallback, long now) {
        Map<String, Long> overall = new HashMap<>(), hour = new HashMap<>(), dayHour = new HashMap<>();
        Set<Long> hourDays = new HashSet<>(), dayHourDays = new HashSet<>();
        Calendar current = Calendar.getInstance(); current.setTimeInMillis(now);
        int currentHour = current.get(Calendar.HOUR_OF_DAY), currentDay = current.get(Calendar.DAY_OF_WEEK);
        Iterator<String> keys = buckets.keys();
        while (keys.hasNext()) {
            String key = keys.next(); long timestamp;
            try { timestamp = Long.parseLong(key); } catch (NumberFormatException ignored) { continue; }
            if (timestamp + HOUR <= now - HISTORY || timestamp > now) continue;
            Calendar at = Calendar.getInstance(); at.setTimeInMillis(timestamp);
            boolean sameHour = at.get(Calendar.HOUR_OF_DAY) == currentHour;
            boolean sameDayHour = sameHour && at.get(Calendar.DAY_OF_WEEK) == currentDay;
            JSONObject values = buckets.optJSONObject(key); if (values == null) continue;
            Iterator<String> apps = values.keys(); boolean positive = false;
            while (apps.hasNext()) {
                String pkg = apps.next(); long value = Math.max(0, Math.min(HOUR, values.optLong(pkg)));
                overall.merge(pkg, value, Long::sum);
                if (sameHour) hour.merge(pkg, value, Long::sum);
                if (sameDayHour) dayHour.merge(pkg, value, Long::sum);
                positive |= value > 0;
            }
            if (positive && sameHour) hourDays.add(Math.floorDiv(timestamp, DAY));
            if (positive && sameDayHour) dayHourDays.add(Math.floorDiv(timestamp, DAY));
        }
        for (Map.Entry<String, Long> entry : fallback.entrySet()) overall.merge(entry.getKey(), Math.min(HISTORY, entry.getValue()), Math::max);
        double total = overall.values().stream().mapToDouble(Long::doubleValue).sum();
        double hourTotal = hour.values().stream().mapToDouble(Long::doubleValue).sum();
        double dayTotal = dayHour.values().stream().mapToDouble(Long::doubleValue).sum();
        boolean useHour = hourDays.size() >= 3 && hourTotal >= 15 * 60_000;
        boolean useDay = dayHourDays.size() >= 2 && dayTotal >= 15 * 60_000;
        Map<String, Double> result = new HashMap<>();
        for (String pkg : overall.keySet()) {
            double generic = total > 0 ? overall.get(pkg) / total : 0;
            double contextual = useHour ? hour.getOrDefault(pkg, 0L) / hourTotal : generic;
            double weekly = useDay ? dayHour.getOrDefault(pkg, 0L) / dayTotal : contextual;
            result.put(pkg, generic * .2 + contextual * .5 + weekly * .3);
        }
        return Map.copyOf(result);
    }
}
