package dev.glgra.launcher;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Positive release-to-release updater check for a disposable emulator only.
 * Install an older APK and this test APK using the public release signing key first.
 * The test reaches Android's install confirmation but never accepts installation.
 */
public final class UpdateSmokeTests extends Instrumentation {
    private final StringBuilder report = new StringBuilder();
    private UpdateRepository repository;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private void passed(String message) {
        report.append("PASS ").append(message).append('\n');
        Bundle progress = new Bundle(); progress.putString("stream", "PASS " + message + "\n"); sendStatus(0, progress);
    }

    private void main(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable problem) { failure.set(problem); } });
        if (failure.get() != null) throw new AssertionError("Main-thread updater check failed", failure.get());
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            PackageManager packages = context.getPackageManager();
            PackageInfo installed = packages.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            report.append("Installed: ").append(installed.versionName).append(" (code ")
                    .append(installed.getLongVersionCode()).append(")\n");
            check(packages.canRequestPackageInstalls(), "Prerequisite missing: enable Allow from this source for Launcher on the disposable emulator before this test.");

            // Deliberately isolate updater state only. Home, widget and usage preferences are untouched.
            SharedPreferences updates = context.getSharedPreferences("updates", Context.MODE_PRIVATE);
            check(updates.edit().clear().commit(), "Could not clear disposable updater preferences");
            repository = new UpdateRepository(context);
            Bundle progress = new Bundle();
            progress.putString("stream", "Downloading the real stable release from https://github.com/" + UpdateRepository.REPOSITORY + "\n");
            sendStatus(0, progress);
            repository.checkBlocking(null);
            report.append("Updater status: ").append(repository.status()).append('\n');
            check(repository.ready(), "No verified newer APK is ready: " + repository.status()
                    + ". Confirm this install is older than the public stable release, shares its signing key, and has validated Wi-Fi.");

            File apk = UpdateRepository.storedFile(context);
            check(apk.isFile() && apk.length() > 0, "Verified download file is missing");
            String digest = UpdateRepository.hash(apk);
            check(digest.equals(updates.getString("readyHash", "")), "Downloaded APK differs from the stored SHA-256 checksum");
            passed("Real GitHub release downloaded over Wi-Fi and SHA-256 verified");

            PackageInfo candidate = packages.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
            check(candidate != null, "Downloaded APK cannot be parsed by Android");
            UpdateRepository.validateCandidate(candidate, installed, updates.getString("readyVersion", ""));
            check(candidate.getLongVersionCode() == updates.getLong("readyCode", -1), "Ready version code differs from the downloaded APK");
            report.append("Candidate: ").append(candidate.versionName).append(" (code ")
                    .append(candidate.getLongVersionCode()).append(") · ").append(apk.length()).append(" bytes\n");
            passed("Actual APK package, higher version, supported SDK and installed signing identity validated");

            Activity settings = startActivitySync(new Intent(context, SettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            waitForIdleSync();
            main(() -> repository.install(settings));
            String installer = awaitInstallerConfirmation(20_000);
            check(installer != null, "Android installer confirmation was not reached: " + repository.status());
            passed("Native install confirmation reached in " + installer + "; installation was not accepted");
            check(UpdateRepository.storedFile(context).isFile(), "Verified pending APK was removed before installation");
            result.putString("stream", "\n" + report + "All real-release updater smoke tests passed. Verified APK retained for manual installation.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            String status = repository == null ? "not started" : repository.status();
            result.putString("stream", "\n" + report + "Updater status at failure: " + status + "\n"
                    + android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (repository != null) repository.close();
        }
    }

    private String awaitInstallerConfirmation(long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        do {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                CharSequence packageName = root.getPackageName();
                if (packageName != null && packageName.toString().toLowerCase(Locale.ROOT).contains("packageinstaller")
                        && hasConfirmationButton(root)) return packageName.toString();
            }
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        return null;
    }

    private boolean hasConfirmationButton(AccessibilityNodeInfo node) {
        CharSequence type = node.getClassName(), content = node.getText();
        if (type != null && type.toString().endsWith("Button") && node.isEnabled() && content != null) {
            String text = content.toString().trim();
            if (text.equalsIgnoreCase("Update") || text.equalsIgnoreCase("Install")) return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null && hasConfirmationButton(child)) return true;
        }
        return false;
    }
}
