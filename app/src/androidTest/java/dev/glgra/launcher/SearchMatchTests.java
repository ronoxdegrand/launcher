package dev.glgra.launcher;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

/** Focused matching regressions; does not inspect or mutate installed-app or usage state. */
public final class SearchMatchTests extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    private static int score(String query, String label) { return AppCatalog.match(AppCatalog.normalize(query), AppCatalog.normalize(label)); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void verify() {
        check(score("mesag", "Messages") < 10000, "A misspelled incomplete name must match Messages");
        check(score("masse", "Messages") < 10000, "Two substitutions in a five-letter prefix must match Messages");
        check(score("masse", "Demo · Messages") < 10000, "Fuzzy prefixes match words inside app names");
        check(score("calnedar", "Calendar") < 10000, "Whole-name adjacent transpositions remain supported");
        check(score("camrea", "Camera") < 10000, "Whole-name transposition remains supported");
        check(score("calen", "Calendar") < 10000, "Exact incomplete names remain supported");
        check(score("café", "Café Maps") == score("cafe", "Cafe Maps"), "Accent normalization is consistent");
        check(score("MÉSA G", "Mesa G") == 0, "Case and accent normalization preserve exact matching");
        check(score("Messages", "Messages") < score("mes", "Messages"), "Exact full names outrank exact prefixes");
        check(score("mes", "Messages") < score("mesag", "Messages"), "Exact prefixes outrank fuzzy prefixes");
        check(score("masse", "Masse") < score("masse", "Messages"), "Exact names outrank approximate names");
        check(score("zzzzz", "Messages") == 10000, "Unrelated query must not become a match");
        check(score("qx", "Messages") == 10000, "Two-character queries do not use broad fuzzy matching");
        check(score("qzz", "Messages") == 10000, "Three-character fuzziness remains bounded");
        check(score("z".repeat(129), "Messages") == 10000, "Oversized fuzzy queries stay bounded");
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            verify();
            result.putString("stream", "PASS Fuzzy partial names, transpositions, accent normalization, lexical relevance and unrelated-query rejection\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
