package dev.glgra.launcher.demo;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.app.Service;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Separate local-only companion apps. These are never packaged inside the launcher APK. */
public final class NotificationDemo extends Activity {
    public static final String POST = "dev.glgra.launcher.demo.POST";
    public static final String CLEAR = "dev.glgra.launcher.demo.CLEAR";
    public static final String PROGRESS = "dev.glgra.launcher.demo.PROGRESS";
    public static final String ACTION = "dev.glgra.launcher.demo.ACTION";
    public static final String HANGUP = "dev.glgra.launcher.demo.HANGUP";
    private static final String CHANNEL = "local-notification-demo", GROUP = "local-demo-group";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static int generation;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(24 * getResources().getDisplayMetrics().density); body.setPadding(p, p * 2, p, p);
        TextView title = new TextView(this); title.setText(getApplicationInfo().loadLabel(getPackageManager())); title.setTextSize(24); body.addView(title);
        TextView note = new TextView(this); note.setText("Local examples only. No account, network or real messages.\n\nPost a batch, then return Home and expand this app's notification group. The launcher must never open, act on or dismiss these previews.\n\nThe hidden example intentionally contains no title/body. Private lock-screen visibility alone does not guarantee redaction for a notification listener.\n\nOrdinary ongoing examples may still be dismissible in Android 14's native shade. A separate ongoing CallStyle example uses Android 14's supported non-dismissible call-notification configuration in a temporary foreground short service. Start it with the dedicated button; it stops after two minutes. Its caller is explicitly fake; there is no telephony, audio or real call. Its native Hang up button only stops this local example.\n\nThe short progress animation is not a genuine system-promoted Live Update; that needs Android 16+ and system approval."); note.setTextSize(15); body.addView(note);
        button(body, "Post example batch", () -> { if (permission()) { post(this); status.setText("Examples posted. Return Home to explore the groups."); } });
        button(body, "Restart short progress animation", () -> { if (permission()) progress(this, null); });
        button(body, "Start non-dismissible example · 2 min", () -> { if (permission()) { try { startForegroundService(new Intent(this, CallExampleService.class)); status.setText("Temporary call-style example started. There is no real call."); } catch (RuntimeException failure) { status.setText("Android could not start this temporary example."); } } });
        button(body, "Stop non-dismissible example", () -> { stopService(new Intent(this, CallExampleService.class)); status.setText("Temporary call-style example stopped."); });
        button(body, "Clear this demo app's notifications", () -> { clear(this); status.setText("This demo app's notifications cleared."); });
        status = new TextView(this); status.setTextSize(14); body.addView(status);
        ScrollView scroll = new ScrollView(this); scroll.addView(body); setContentView(scroll);
    }
    @Override protected void onResume() { super.onResume(); if (status != null) status.setText("Native demo actions invoked: " + getSharedPreferences("demo", 0).getInt("actions", 0)); }
    private boolean permission() { if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return true; requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1); return false; }
    private void button(LinearLayout body, String text, Runnable action) { Button button = new Button(this); button.setText(text); button.setAllCaps(false); button.setOnClickListener(v -> action.run()); body.addView(button, new LinearLayout.LayoutParams(-1, -2)); }

    public static final class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (CLEAR.equals(action)) { clear(context); return; }
            if (HANGUP.equals(action)) {
                context.stopService(new Intent(context, CallExampleService.class));
                context.getSystemService(NotificationManager.class).cancel(108);
                android.content.SharedPreferences prefs = context.getSharedPreferences("demo", 0);
                prefs.edit().putInt("actions", prefs.getInt("actions", 0) + 1).apply(); return;
            }
            if (ACTION.equals(action)) { android.content.SharedPreferences prefs = context.getSharedPreferences("demo", 0); prefs.edit().putInt("actions", prefs.getInt("actions", 0) + 1).apply(); return; }
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
            if (POST.equals(action)) { postStatic(context); progress(context, goAsync()); }
            else if (PROGRESS.equals(action)) progress(context, goAsync());
        }
    }
    private static void setup(Context context) {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Local example notifications", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null); channel.enableVibration(false); context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    private static Notification.Builder base(Context context) {
        setup(context);
        PendingIntent benign = PendingIntent.getBroadcast(context, 500, new Intent(context, Receiver.class).setAction(ACTION), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(context, CHANNEL).setSmallIcon(icon(context)).setColor(accent(context)).setGroup(GROUP)
                .setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC).setContentIntent(benign)
                .addAction(new Notification.Action.Builder(null, "Demo action", benign).build());
    }
    private static void post(Context context) { postStatic(context); progress(context, null); }
    private static void postStatic(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        String profile = profile(context);
        String[] people = profile.equals("messages") ? new String[]{"Ada · Weekend plans", "Friends · Book club", "Maya · A longer thought"}
                : profile.equals("work") ? new String[]{"Review requested", "Team update", "Project brief"} : new String[]{"Train platform changed", "Arrival reminder", "Route details"};
        manager.notify(100, base(context).setContentTitle("Local demo summary").setContentText("Grouped notification examples").setGroupSummary(true).build());
        manager.notify(101, base(context).setContentTitle(people[0]).setContentText("A short sample notification. Nothing here came from a real account.").build());
        manager.notify(102, base(context).setContentTitle(people[1]).setContentText("Another notification from the same demo app; its count should increase the existing group.").build());
        StringBuilder longText = new StringBuilder("This deliberately long example lets you test expanding and collapsing a preview.\n\n");
        for (int i = 1; i <= 10; i++) longText.append("Paragraph ").append(i).append(": The launcher keeps notification text local and read-only. A tap expands this text without following the content intent. Scroll within the notification workspace to continue reading, then tap the preview again to collapse it.\n\n");
        manager.notify(103, base(context).setContentTitle(people[2]).setContentText("A long message with ten paragraphs…").setStyle(new Notification.BigTextStyle().bigText(longText)).build());
        manager.notify(104, base(context).setVisibility(Notification.VISIBILITY_SECRET).setSubText("Deliberately content-free privacy example").build());
        manager.notify(105, base(context).setContentTitle("Ongoing example").setContentText("Marked ongoing by this demo app. Android may allow dismissal in its native shade; the launcher never dismisses it.").setOngoing(true).build());
        Bitmap image = picture(context);
        manager.notify(106, base(context).setContentTitle("Sample image preview").setContentText("Tap the thumbnail in the launcher to enlarge it locally.").setStyle(new Notification.BigPictureStyle().bigPicture(image)).build());
    }
    private static Notification callExample(Context context) {
        Person caller = new Person.Builder().setName("Demo call · no actual call").setImportant(false).build();
        PendingIntent hangup = PendingIntent.getBroadcast(context, 108, new Intent(context, Receiver.class).setAction(HANGUP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return base(context).setContentTitle("Non-dismissible CallStyle example")
                .setContentText("Fake local example only. Native Hang up stops it; it also ends automatically after two minutes.")
                .setCategory(Notification.CATEGORY_CALL).setOngoing(true)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .setStyle(Notification.CallStyle.forOngoingCall(caller, hangup)).build();
    }
    /** Android requires CallStyle to belong to a service/job; this test service expires and never restarts. */
    public static final class CallExampleService extends Service {
        private final Handler timer = new Handler(Looper.getMainLooper());
        private final Runnable expire = this::stopSelf;
        @Override public int onStartCommand(Intent intent, int flags, int startId) {
            try {
                startForeground(108, callExample(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
                timer.removeCallbacks(expire); timer.postDelayed(expire, 120_000);
            } catch (RuntimeException unavailable) {
                getSharedPreferences("demo", 0).edit().putString("callStyleUnavailable", "Android could not start the temporary call-style example.").apply(); stopSelf();
            }
            return START_NOT_STICKY;
        }
        @Override public void onTimeout(int startId) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
        @Override public IBinder onBind(Intent intent) { return null; }
        @Override public void onDestroy() { timer.removeCallbacks(expire); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy(); }
    }    private static void progress(Context context, BroadcastReceiver.PendingResult pending) {
        Context app = context.getApplicationContext(); int token = ++generation;
        // At most 13 updates in 5.4 seconds: bounded within the broadcast async budget, no persistent service/alarm.
        Runnable tick = new Runnable() { int step;
            @Override public void run() {
                if (token != generation) { if (pending != null) pending.finish(); return; }
                try {
                    int percent = step * 100 / 12;
                    app.getSystemService(NotificationManager.class).notify(107, base(app).setContentTitle(step < 12 ? "Live progress example" : "Example completed")
                            .setContentText(step < 12 ? "Local demo progress · " + percent + "%" : "Progress finished. This was not a system-promoted Live Update.")
                            .setProgress(100, percent, false).setOngoing(step < 12).build());
                    if (step++ < 12) MAIN.postDelayed(this, 450); else if (pending != null) pending.finish();
                } catch (RuntimeException failure) { if (pending != null) pending.finish(); }
            }
        }; MAIN.post(tick);
    }
    private static void clear(Context context) { generation++; context.stopService(new Intent(context, CallExampleService.class)); context.getSystemService(NotificationManager.class).cancelAll(); }
    private static String profile(Context c) { return c.getPackageName().substring(c.getPackageName().lastIndexOf('.') + 1); }
    private static int icon(Context c) { return profile(c).equals("messages") ? android.R.drawable.ic_dialog_email : profile(c).equals("work") ? android.R.drawable.ic_menu_agenda : android.R.drawable.ic_dialog_map; }
    private static int accent(Context c) { return profile(c).equals("messages") ? 0xFF537ED9 : profile(c).equals("work") ? 0xFF9B60BD : 0xFF349A86; }
    private static Bitmap picture(Context context) {
        Bitmap image = Bitmap.createBitmap(480, 240, Bitmap.Config.ARGB_8888); Canvas canvas = new Canvas(image); Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, 480, 240, accent(context), 0xFF142D48, Shader.TileMode.CLAMP)); canvas.drawRect(0, 0, 480, 240, paint); paint.setShader(null);
        paint.setColor(0x55FFFFFF); canvas.drawCircle(370, 65, 90, paint); paint.setColor(Color.WHITE); paint.setTextSize(25); canvas.drawText("Local " + profile(context) + " example", 24, 190, paint); return image;
    }
}
