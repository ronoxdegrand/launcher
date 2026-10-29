package dev.glgra.launcher;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Measured framework regression: real listener data, attached views, and timed touchscreen input. */
public final class NotificationScrollTests extends Instrumentation {
    private static final String TAG = "notification-scroll-regression";
    private static final String CHANNEL = "notification-scroll-regression-v1";
    private NotificationPanel panel;
    private FrameLayout overlay;
    private NotificationManager manager;
    private int dp(int n) { return Math.round(n * getTargetContext().getResources().getDisplayMetrics().density); }
    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private void main(Runnable work) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { work.run(); } catch (Throwable t) { failure.set(t); } });
        if (failure.get() != null) throw new AssertionError("UI check failed", failure.get());
    }
    private void await(BooleanSupplier condition, String message) {
        long end = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < end) {
            AtomicBoolean ready = new AtomicBoolean(); main(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return;
            SystemClock.sleep(50);
        }
        throw new AssertionError(message);
    }
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int code = Activity.RESULT_CANCELED;
        manager = getTargetContext().getSystemService(NotificationManager.class);
        try {
            check(NotificationRepository.accessGranted(getTargetContext()), "Grant notification listener access before running");
            check(manager.areNotificationsEnabled(), "Grant notification posting permission before running");
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Scroll test fixtures", NotificationManager.IMPORTANCE_LOW));
            for (int i = 0; i < 14; i++) post(i, false);
            await(() -> NotificationRepository.snapshot().stream().filter(n -> TAG.equals(n.getTag())).count() == 14,
                    "Listener did not receive owned fixtures");
            HomeActivity home = (HomeActivity) startActivitySync(new Intent(getTargetContext(), HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            main(() -> {
                overlay = new FrameLayout(home);
                panel = new NotificationPanel(home);
                FrameLayout.LayoutParams size = new FrameLayout.LayoutParams(-1, dp(300), Gravity.CENTER);
                overlay.addView(panel, size);
                home.addContentView(overlay, new ViewGroup.LayoutParams(-1, -1));
            });
            await(() -> home.hasWindowFocus() && panel.isShown() && panel.getHeight() > 0 && !panel.isLayoutRequested(), "Panel not measured/focused");
            waitForIdleSync(); SystemClock.sleep(250);
            String label = getTargetContext().getApplicationInfo().loadLabel(getTargetContext().getPackageManager()).toString();
            AtomicReference<View> header = new AtomicReference<>();
            main(() -> header.set(findDescription(panel, label + ", ")));
            check(header.get() != null, "Own fixture group missing");
            tap(header.get());
            await(() -> findText(panel, "Scroll fixture 13") != null && !panel.isLayoutRequested(), "Group did not expand");
            check(scroll() <= dp(4), "Expanding group jumped from the top: " + scroll());
            AtomicReference<View> preview = new AtomicReference<>();
            main(() -> preview.set(findTextPrefix(panel, "Long fixture 13")));
            tap(preview.get());
            await(() -> previewExpanded() && !panel.isLayoutRequested(), "Message did not expand");
            check(scroll() <= dp(4), "Expanding text scrolled to a descendant: " + scroll());
            main(() -> panel.scrollTo(0, dp(350)));
            SystemClock.sleep(220);
            int before = scroll();
            slowSwipe(dp(65), true);
            SystemClock.sleep(250);
            int after = scroll();
            check(after > before + dp(20) && after < before + dp(115),
                    "Small upward drag must scroll locally, before=" + before + " after=" + after);
            slowSwipe(-dp(45), false);
            SystemClock.sleep(250);
            int reverse = scroll();
            check(reverse < after - dp(12) && reverse > after - dp(95),
                    "Small reverse drag must scroll locally, before=" + after + " after=" + reverse);
            check(reverse > 0 && reverse < maxScroll(), "Small swipes snapped to an endpoint");
            check(manager.getActiveNotifications().length >= 14, "Read-only previews removed native notifications");
            code = Activity.RESULT_OK;
            result.putString("stream", "PASS group/text expansion preserves viewport; timed small bidirectional drags scroll normally; listener update during touch preserves gesture; native notifications remain active.\n");
        } catch (Throwable t) { result.putString("stream", android.util.Log.getStackTraceString(t)); try { android.graphics.Bitmap shot=getUiAutomation().takeScreenshot();java.io.File file=new java.io.File(getTargetContext().getExternalFilesDir(null),"scroll-failure.png");try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);} } catch(Exception ignored){} }
        finally {
            if (overlay != null) main(() -> {
                panel.dispose();
                if (overlay.getParent() instanceof ViewGroup) ((ViewGroup) overlay.getParent()).removeView(overlay);
            });
            for (int i = 0; i < 14; i++) manager.cancel(TAG, 6100 + i);
            manager.deleteNotificationChannel(CHANNEL);
        }
        finish(code, result);
    }
    private void post(int i, boolean update) {
        String body = "Long fixture " + i + "\n" + "A full line of notification text that remains locally expandable.\n".repeat(22);
        Notification notification = new Notification.Builder(getTargetContext(), CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Scroll fixture " + i)
                .setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body))
                .setGroup(TAG).setOnlyAlertOnce(true).setSubText(update ? "Updated during touch" : "Fixture")
                .build();
        manager.notify(TAG, 6100 + i, notification);
    }
    private boolean previewExpanded() {
        View view = findTextPrefix(panel, "Long fixture 13");
        return view instanceof TextView && ((TextView) view).getMaxLines() == Integer.MAX_VALUE;
    }
    private int scroll() { AtomicInteger y = new AtomicInteger(); main(() -> y.set(panel.getScrollY())); return y.get(); }
    private int maxScroll() { AtomicInteger y = new AtomicInteger(); main(() -> y.set(panel.getChildAt(0).getHeight() - panel.getHeight())); return y.get(); }
    private void tap(View view) {
        check(view != null, "Tap target missing"); int[] point = new int[2];
        main(() -> { view.getLocationOnScreen(point); point[0] += view.getWidth() / 2; point[1] += Math.min(view.getHeight() / 2, dp(20)); });
        long down = SystemClock.uptimeMillis(); event(down, MotionEvent.ACTION_DOWN, point[0], point[1]);
        SystemClock.sleep(60); event(down, MotionEvent.ACTION_UP, point[0], point[1]);
    }
    private void slowSwipe(int distance, boolean updateDuringTouch) {
        int[] location = new int[2]; main(() -> panel.getLocationOnScreen(location));
        float x = location[0] + dp(80), y = location[1] + dp(180);
        long down = SystemClock.uptimeMillis(); event(down, MotionEvent.ACTION_DOWN, x, y);
        for (int i = 1; i <= 18; i++) {
            SystemClock.sleep(35); event(down, MotionEvent.ACTION_MOVE, x, y - distance * i / 18f);
            if (i == 6 && updateDuringTouch) post(13, true);
        }
        // Stationary samples remove release velocity, isolating drag distance from native fling distance.
        for (int i = 0; i < 5; i++) { SystemClock.sleep(40); event(down, MotionEvent.ACTION_MOVE, x, y - distance); }
        event(down, MotionEvent.ACTION_UP, x, y - distance);
    }
    private void event(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { check(getUiAutomation().injectInputEvent(event, true), "Touch injection failed"); }
        finally { event.recycle(); }
    }
    private View findDescription(View view, String prefix) {
        if (view.getContentDescription() != null && view.getContentDescription().toString().startsWith(prefix)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = findDescription(((ViewGroup) view).getChildAt(i), prefix); if (found != null) return found;
        }
        return null;
    }
    private View findText(View view, String text) { return findTextValue(view, text, false); }
    private View findTextPrefix(View view, String text) { return findTextValue(view, text, true); }
    private View findTextValue(View view, String text, boolean prefix) {
        if (view instanceof TextView && (prefix ? ((TextView) view).getText().toString().startsWith(text) : text.contentEquals(((TextView) view).getText()))) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = findTextValue(((ViewGroup) view).getChildAt(i), text, prefix); if (found != null) return found;
        }
        return null;
    }
}
