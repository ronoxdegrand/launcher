package dev.glgra.launcher;

import android.app.Activity;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One release channel, no install without a foreground user action. */
final class UpdateRepository implements AutoCloseable {
    static final String REPOSITORY = "ronoxdegrand/launcher";
    static final int JOB_ID = 4104;
    private static final long DAY = TimeUnit.DAYS.toMillis(1);
    private static final long APK_LIMIT = 128L * 1024 * 1024;
    private static final AtomicBoolean CHECKING = new AtomicBoolean();
    private final Context context;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File directory;
    private volatile HttpURLConnection connection;
    private volatile boolean closed;
    private static volatile int progress = -1;
    private static volatile String workStatus;
    private static final java.util.Set<Runnable> WATCHERS=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<Runnable> callbacks=new java.util.HashSet<>();

    UpdateRepository(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences("updates", Context.MODE_PRIVATE);
        directory = new File(this.context.getFilesDir(), "updates");
        reconcileInstalledRelease();
    }

    static void schedule(Context context) {
        // Development installs do not contact a public release feed in the background.
        if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) return;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        JobInfo existing = scheduler.getPendingJob(JOB_ID);
        if (existing != null) return;
        scheduler.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, UpdateJobService.class))
                .setRequiredNetwork(new android.net.NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build())
                .setPersisted(true).setPeriodic(DAY, TimeUnit.HOURS.toMillis(2)).build());
    }

    void checkIfStale(Runnable onChanged) {
        if(onChanged!=null){callbacks.add(onChanged);WATCHERS.add(onChanged);}
        if (closed || System.currentTimeMillis() - prefs.getLong("lastCheck", 0) < DAY) {
            notifyChanged(onChanged); return;
        }
        worker.execute(() -> checkBlocking(onChanged));
    }

    /** The job invokes this on its own worker, never the main thread. */
    void checkBlocking(Runnable onChanged) {
        if (closed || !CHECKING.compareAndSet(false, true)) return;
        File temporary = new File(directory, "download.partial");
        try {
            if (System.currentTimeMillis() - prefs.getLong("lastCheck", 0) < DAY) return;
            // Checkpoint attempts too: network failure waits until the next daily check.
            prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply();
            workStatus = "Checking for updates…"; notifyChanged(onChanged);
            JSONObject release = new JSONObject(new String(readBytes(
                    "https://api.github.com/repos/" + REPOSITORY + "/releases/latest", 2 * 1024 * 1024), StandardCharsets.UTF_8));
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) throw new IOException("Not a stable release");
            String tag = release.getString("tag_name");
            if (!tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+")) throw new IOException("Invalid release version");
            PackageInfo installed = installed();
            if (tag.substring(1).equals(installed.versionName)) {
                prefs.edit().putString("installedNotes", release.optString("body", ""))
                        .putLong("notesCode", installed.getLongVersionCode()).apply();
                workStatus = "Up to date"; return;
            }
            String apkUrl = null, checksumUrl = null;
            long expectedSize = -1;
            JSONArray assets = release.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                String name = asset.getString("name");
                if (name.equals("launcher.apk")) { apkUrl = asset.getString("browser_download_url"); expectedSize = asset.getLong("size"); }
                if (name.equals("SHA256SUMS.txt")) checksumUrl = asset.getString("browser_download_url");
            }
            requireReleaseAsset(apkUrl); requireReleaseAsset(checksumUrl);
            if (expectedSize <= 0 || expectedSize > APK_LIMIT) throw new IOException("Invalid APK length");
            String notes = release.optString("body", "");
            prefs.edit().putString("availableVersion", tag.substring(1)).putString("availableNotes", notes).apply();
            Network wifi = wifiNetwork();
            if (wifi == null) { workStatus = "Update available · waiting for Wi-Fi"; return; }
            String sums = new String(readBytes(checksumUrl, 64 * 1024, wifi), StandardCharsets.UTF_8);
            String expectedHash = null;
            for (String line : sums.split("\\R")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2 && parts[1].replaceFirst("^\\*", "").equals("launcher.apk")
                        && parts[0].matches("(?i)[a-f0-9]{64}")) expectedHash = parts[0].toLowerCase(Locale.ROOT);
            }
            if (expectedHash == null) throw new IOException("APK checksum missing");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create update storage");
            download(apkUrl, temporary, expectedSize, onChanged, wifi);
            if (!hash(temporary).equals(expectedHash)) throw new IOException("APK checksum mismatch");
            PackageInfo candidate = archive(temporary);
            validateCandidate(candidate, installed, tag.substring(1));
            File ready = new File(directory, "update-" + candidate.getLongVersionCode() + "-" + expectedHash + ".apk");
            // Keep a previously verified download intact until all checks on its replacement pass.
            Files.move(temporary.toPath(), ready.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            boolean saved = prefs.edit().putLong("readyCode", candidate.getLongVersionCode()).putString("readyVersion", candidate.versionName)
                    .putString("readyHash", expectedHash).putString("readyNotes", notes).putString("readyFile", ready.getName()).commit();
            if (!saved) throw new IOException("Cannot save update metadata");
            workStatus = "Update ready to install";
        } catch (Exception error) {
            if (!closed) {
                workStatus = "Update check failed · will retry tomorrow";
                prefs.edit().putString("lastResult", workStatus).apply();
            }
        } finally {
            temporary.delete(); progress = -1;
            pruneUnreferencedApks();
            HttpURLConnection active = connection;
            if (active != null) active.disconnect();
            connection = null;
            if (closed || Thread.currentThread().isInterrupted()) workStatus = "Update check paused · will retry tomorrow";
            if (workStatus != null) prefs.edit().putString("lastResult", workStatus).apply();
            CHECKING.set(false); notifyChanged(onChanged);
        }
    }

    String status() {
        if (progress >= 0) return "Downloading update · " + progress + "%";
        if (ready()) {
            try { return "v" + installed().versionName + " → v" + prefs.getString("readyVersion", ""); }
            catch (PackageManager.NameNotFoundException ignored) { return "Install update"; }
        }
        if (workStatus != null) return workStatus;
        return prefs.getString("lastResult", "Updates are checked daily");
    }

    String installedChangelog() {
        try {
            if (prefs.getLong("notesCode", -1) == installed().getLongVersionCode()) return prefs.getString("installedNotes", "");
        } catch (PackageManager.NameNotFoundException ignored) { }
        try(InputStream in=context.getAssets().open("CHANGELOG.md")){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}catch(IOException ignored){return "Personal launcher release.";}
    }
    String availableChangelog() { return prefs.getString(ready() ? "readyNotes" : "availableNotes", ""); }

    boolean ready() {
        try {
            return storedFile(context).isFile()
                    && prefs.getLong("readyCode", 0) > installed().getLongVersionCode();
        } catch (PackageManager.NameNotFoundException ignored) { return false; }
    }

    void install(Activity activity) {
        if (!ready()) return;
        if (!context.getPackageManager().canRequestPackageInstalls()) {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.getPackageName())));
            return;
        }
        // Recheck locally at the install boundary; Android verifies the signature again itself.
        File apk = storedFile(context);
        worker.execute(() -> {
            try {
                String expected = prefs.getString("readyHash", "");
                if (!hash(apk).equals(expected)) throw new IOException("Stored APK changed");
                validateCandidate(archive(apk), installed(), prefs.getString("readyVersion", ""));
                main.post(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    Intent installer = new Intent(Intent.ACTION_VIEW)
                            .setDataAndType(Uri.parse("content://" + context.getPackageName() + ".updates/update.apk"), "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try { activity.startActivity(installer); }
                    catch (RuntimeException unavailable) { workStatus = "Android's package installer is unavailable"; }
                });
            } catch (Exception invalid) { workStatus = "Downloaded update failed validation"; }
        });
    }

    private void reconcileInstalledRelease() {
        try {
            long code = installed().getLongVersionCode();
            if (prefs.getLong("readyCode", 0) == code) {
                File consumed = storedFile(context);
                prefs.edit().putString("installedNotes", prefs.getString("readyNotes", ""))
                        .putLong("notesCode", code).remove("readyCode").remove("readyHash")
                        .remove("readyVersion").remove("readyNotes").remove("readyFile").remove("availableVersion").remove("availableNotes")
                        .putString("lastResult", "Up to date").apply();
                consumed.delete();
            }
        } catch (PackageManager.NameNotFoundException ignored) { }
    }

    private PackageInfo installed() throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
    }

    private PackageInfo archive(File apk) throws IOException {
        PackageInfo info = context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (info == null || info.applicationInfo == null || info.signingInfo == null) throw new IOException("Not a signed APK");
        return info;
    }

    static void validateCandidate(PackageInfo candidate, PackageInfo installed, String version) throws IOException {
        if (candidate == null || candidate.applicationInfo == null || candidate.signingInfo == null || installed.signingInfo == null)
            throw new IOException("APK metadata unavailable");
        if (!installed.packageName.equals(candidate.packageName) || candidate.getLongVersionCode() <= installed.getLongVersionCode()
                || !version.equals(candidate.versionName) || candidate.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT)
            throw new IOException("Incompatible APK version or package");
        Signature[] before = installed.signingInfo.getApkContentsSigners();
        Signature[] after = candidate.signingInfo.getApkContentsSigners();
        if (before.length == 0 || before.length != after.length) throw new IOException("Signing identity changed");
        for (Signature signer : before) if (!Arrays.asList(after).contains(signer)) throw new IOException("Signing identity changed");
    }

    static File storedFile(Context context) {
        String name = context.getSharedPreferences("updates", Context.MODE_PRIVATE).getString("readyFile", "update.apk");
        if (!name.matches("update(?:-[0-9]+-[a-f0-9]{64})?\\.apk")) name = "update.apk";
        return new File(new File(context.getFilesDir(), "updates"), name);
    }

    private void pruneUnreferencedApks() {
        File keep = storedFile(context);
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!file.equals(keep) && file.getName().matches("update(?:-[0-9]+-[a-f0-9]{64})?\\.apk")) file.delete();
        }
    }

    private Network wifiNetwork() {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        Network selected = manager.getActiveNetwork();
        NetworkCapabilities network = manager.getNetworkCapabilities(selected);
        return network != null && network.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && network.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? selected : null;
    }

    private static void requireReleaseAsset(String url) throws IOException {
        if (url == null || !url.startsWith("https://github.com/" + REPOSITORY + "/releases/download/"))
            throw new IOException("Unexpected release asset host");
    }

    private HttpURLConnection open(String address, Network network) throws IOException {
        for (int redirects = 0; redirects < 6; redirects++) {
            if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
            URL url = new URL(address);
            String host = url.getHost().toLowerCase(Locale.ROOT);
            if (!url.getProtocol().equals("https") || !(host.equals("api.github.com") || host.equals("github.com")
                    || host.equals("objects.githubusercontent.com") || host.equals("release-assets.githubusercontent.com")))
                throw new IOException("Unexpected download URL");
            HttpURLConnection request = (HttpURLConnection) (network == null ? url.openConnection() : network.openConnection(url));
            connection = request;
            request.setInstanceFollowRedirects(false); request.setConnectTimeout(15_000); request.setReadTimeout(20_000);
            request.setRequestProperty("User-Agent", "PersonalLauncher/1");
            request.setRequestProperty("Accept", host.equals("api.github.com") ? "application/vnd.github+json" : "application/octet-stream");
            int status = request.getResponseCode();
            if (status >= 300 && status < 400) {
                String next = request.getHeaderField("Location"); request.disconnect();
                if (next == null) throw new IOException("Redirect without location");
                address = new URL(url, next).toString(); continue;
            }
            if (status != 200) { request.disconnect(); throw new IOException("HTTP " + status); }
            return request;
        }
        throw new IOException("Too many redirects");
    }

    private byte[] readBytes(String url, int limit) throws IOException {
        return readBytes(url, limit, null);
    }

    private byte[] readBytes(String url, int limit, Network network) throws IOException {
        HttpURLConnection request = open(url, network);
        try (InputStream input = request.getInputStream(); ByteArrayOutputStream result = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = input.read(buffer)) != -1) {
                if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
                if (result.size() + read > limit) throw new IOException("Response too large");
                result.write(buffer, 0, read);
            }
            return result.toByteArray();
        } finally { request.disconnect(); connection = null; }
    }

    private void download(String url, File destination, long expectedSize, Runnable callback, Network network) throws IOException {
        HttpURLConnection request = open(url, network);
        try (InputStream input = request.getInputStream(); FileOutputStream output = new FileOutputStream(destination)) {
            long length = request.getContentLengthLong();
            if (length > 0 && length != expectedSize) throw new IOException("APK length changed");
            byte[] buffer = new byte[32 * 1024]; long total = 0; int read, previous = -1;
            while ((read = input.read(buffer)) != -1) {
                if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
                total += read; if (total > expectedSize || total > APK_LIMIT) throw new IOException("APK too large");
                output.write(buffer, 0, read); progress = (int) (100 * total / expectedSize);
                if (progress / 5 != previous) { previous = progress / 5; notifyChanged(callback); }
            }
            if (total != expectedSize) throw new IOException("Incomplete APK");
            output.getFD().sync();
        } finally { request.disconnect(); connection = null; }
    }

    static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024]; int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private void notifyChanged(Runnable callback) {
        java.util.Set<Runnable> targets=new java.util.HashSet<>(WATCHERS);if(callback!=null&&!closed)targets.add(callback);
        for(Runnable target:targets)main.post(()->{if(WATCHERS.contains(target)||target==callback&&!closed)target.run();});
    }
    @Override public void close() {
        closed = true;WATCHERS.removeAll(callbacks);callbacks.clear();worker.shutdownNow();
        HttpURLConnection request = connection; if (request != null) request.disconnect();
    }
}
