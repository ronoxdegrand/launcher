package dev.glgra.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.drawable.Drawable;
import android.os.Process;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;

/** A worker-loaded, immutable snapshot of ordinary current-user launcher activities. */
final class AppCatalog {
    static final class Entry {
        final String key, packageName, label;
        final ComponentName component;
        final Drawable icon;
        final int nativeCategory;
        final String normalized;
        Entry(LauncherActivityInfo activity) {
            component = activity.getComponentName(); key = component.flattenToString();
            packageName = component.getPackageName(); label = activity.getLabel().toString();
            icon = activity.getIcon(0); nativeCategory = activity.getApplicationInfo().category;
            normalized = normalize(label);
        }
    }
    private final Context context;
    private final LauncherApps launcher;
    private final SharedPreferences prefs;
    private volatile List<Entry> entries = List.of();
    private volatile Map<String, Entry> byKey = Map.of();
    AppCatalog(Context context) {
        this.context = context.getApplicationContext(); launcher = context.getSystemService(LauncherApps.class);
        prefs = new LayoutStore(context).prefs;
    }
    void reload() {
        List<Entry> next = new ArrayList<>(); Map<String, Entry> index = new LinkedHashMap<>();
        for (LauncherActivityInfo activity : launcher.getActivityList(null, Process.myUserHandle())) {
            if (activity.getComponentName().getPackageName().equals(context.getPackageName())) continue;
            try {
                Entry entry = new Entry(activity);
                if (!index.containsKey(entry.key)) { index.put(entry.key, entry); next.add(entry); }
            } catch (RuntimeException ignored) { /* An app can disappear while its icon is loaded. */ }
        }
        next.sort(Comparator.comparing((Entry entry) -> entry.label, String.CASE_INSENSITIVE_ORDER).thenComparing(entry -> entry.key));
        byKey = Collections.unmodifiableMap(index); entries = List.copyOf(next);
    }
    List<Entry> all() { return entries; }
    Entry find(String key) { return byKey.get(key); }
    Set<String> hidden() { return new HashSet<>(prefs.getStringSet("hidden", Set.of())); }
    private boolean hidden(Entry entry, Set<String> hidden) { return hidden.contains(entry.key) || hidden.contains(entry.packageName); }
    List<Entry> ranked(UsageRepository usage) {
        Set<String> hidden = hidden(); List<Entry> result = new ArrayList<>();
        for (Entry entry : entries) if (!hidden(entry, hidden)) result.add(entry);
        result.sort(Comparator.comparingDouble((Entry entry) -> usage.score(entry.packageName)).reversed()
                .thenComparing(entry -> entry.label, String.CASE_INSENSITIVE_ORDER).thenComparing(entry -> entry.key));
        return result;
    }
    List<Entry> dock(UsageRepository usage) {
        List<Entry> result = new ArrayList<>(4); Set<String> usedPackages = new HashSet<>(); Set<String> hidden = hidden();
        try {
            JSONArray pins = new JSONArray(prefs.getString("dockPins", "[]"));
            for (int i = 0; i < pins.length() && result.size() < 4; i++) {
                Entry entry = find(pins.optString(i));
                if (entry != null && !hidden(entry, hidden) && usedPackages.add(entry.packageName)) result.add(entry);
            }
        } catch (org.json.JSONException ignored) { }
        for (Entry entry : ranked(usage)) {
            if (result.size() == 4) break;
            if (usedPackages.add(entry.packageName)) result.add(entry);
        }
        // Multiple launcher activities of the same package are still distinct apps exposed by Android.
        if (result.size() < 4) for (Entry entry : ranked(usage)) {
            if (result.size() == 4) break;
            if (!result.contains(entry)) result.add(entry);
        }
        return result;
    }
    String category(Entry entry) {
        String override = prefs.getString("category:" + entry.packageName, "");
        if (!override.isBlank()) return override;
        return switch (entry.nativeCategory) {
            case ApplicationInfo.CATEGORY_GAME -> "Games";
            case ApplicationInfo.CATEGORY_AUDIO -> "Music & audio";
            case ApplicationInfo.CATEGORY_VIDEO -> "Video";
            case ApplicationInfo.CATEGORY_IMAGE -> "Photography";
            case ApplicationInfo.CATEGORY_SOCIAL -> "Social";
            case ApplicationInfo.CATEGORY_NEWS -> "News";
            case ApplicationInfo.CATEGORY_MAPS -> "Maps & travel";
            case ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity";
            case ApplicationInfo.CATEGORY_ACCESSIBILITY -> "Accessibility";
            default -> "Uncategorized";
        };
    }
    List<Entry> search(String query, UsageRepository usage) {
        String normalized = normalize(query);
        if (normalized.isEmpty()) return query == null || query.isBlank() ? dock(usage) : List.of();
        Map<Entry, Integer> matches = new LinkedHashMap<>();
        for (Entry entry : entries) {
            int match = match(normalized, entry.normalized);
            if (match < 10000) matches.put(entry, match);
        }
        List<Entry> result = new ArrayList<>(matches.keySet());
        result.sort(Comparator.comparingInt((Entry entry) -> matches.get(entry))
                .thenComparing(Comparator.comparingDouble((Entry entry) -> usage.score(entry.packageName)).reversed())
                .thenComparing(entry -> entry.label, String.CASE_INSENSITIVE_ORDER).thenComparing(entry -> entry.key));
        return result;
    }
    static String normalize(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }
    /** Integer relevance bands keep usage from outranking better lexical matches. */
    static int match(String query, String label) {
        if (label.equals(query)) return 0;
        if (label.startsWith(query)) return 10;
        for (String word : label.split(" ")) if (word.startsWith(query)) return 20;
        if (label.contains(query)) return 30;
        String compactQuery = query.replace(" ", ""), compactLabel = label.replace(" ", "");
        if (compactLabel.equals(compactQuery)) return 1;
        if (compactLabel.startsWith(compactQuery)) return 11;
        // One- and two-character queries use partial matching, not indiscriminate fuzzy matches.
        if (compactQuery.length() < 3) return 10000;
        int allowed = compactQuery.length() < 6 ? 1 : Math.min(3, compactQuery.length() / 3);
        int best = distance(compactQuery, compactLabel, allowed);
        for (String word : label.split(" ")) best = Math.min(best, distance(compactQuery, word, allowed));
        if (best <= allowed) return 100 + best * 10;
        int at = 0;
        for (int i = 0; i < compactLabel.length() && at < compactQuery.length(); i++)
            if (compactLabel.charAt(i) == compactQuery.charAt(at)) at++;
        if (at == compactQuery.length() && compactLabel.length() <= compactQuery.length() * 2) return 200;
        return 10000;
    }
    /** Bounded Damerau-Levenshtein, including adjacent transpositions. */
    private static int distance(String a, String b, int limit) {
        if (Math.abs(a.length() - b.length()) > limit || a.length() > 128 || b.length() > 128) return limit + 1;
        int[] previousPrevious = null, previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] current = new int[b.length() + 1]; current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                current[j] = Math.min(previous[j] + 1, Math.min(current[j - 1] + 1,
                        previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1)));
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1))
                    current[j] = Math.min(current[j], previousPrevious[j - 2] + 1);
            }
            previousPrevious = previous; previous = current;
        }
        return previous[b.length()];
    }
    void launch(Entry entry) { launcher.startMainActivity(entry.component, Process.myUserHandle(), null, null); }
    List<ShortcutInfo> shortcuts(Entry entry) {
        if (!launcher.hasShortcutHostPermission()) return List.of();
        try {
            List<ShortcutInfo> result = launcher.getShortcuts(new LauncherApps.ShortcutQuery().setPackage(entry.packageName)
                    .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                            | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED), Process.myUserHandle());
            return result == null ? List.of() : result;
        } catch (SecurityException | IllegalStateException ignored) { return List.of(); }
    }
}
