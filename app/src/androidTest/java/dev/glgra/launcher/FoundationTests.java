package dev.glgra.launcher;

import android.app.Activity;
import android.app.Instrumentation;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Random;

/** No test framework dependency; executes against the installed debug application. */
public final class FoundationTests extends Instrumentation {
    private final StringBuilder report = new StringBuilder();
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private void passed(String name) {
        report.append("PASS ").append(name).append('\n');
        Bundle progress = new Bundle(); progress.putString("stream", "PASS " + name + "\n"); sendStatus(0, progress);
    }
    private void main(Runnable action) {
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { failure.set(e); } });
        if (failure.get() != null) throw new AssertionError("Main-thread assertion", failure.get());
    }
    @Override public void onStart() {
        Bundle results = new Bundle();
        try {
            grid();
            HomeActivity home = (HomeActivity) startActivitySync(new Intent(getTargetContext(), HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            waitForIdleSync();
            main(() -> {
                home.model.items.removeIf(item -> {
                    if (item.isWidget() && item.component.endsWith("FixtureWidget")) { home.host.deleteAppWidgetId(item.widgetId); return true; }
                    return false;
                });
                home.cancelPending(); home.store.save(home.model); home.render();
                check(home.canvas.getWidth() > 0 && home.canvas.getHeight() > 0, "Home must have a viewport");
                home.store.prefs.edit().putInt("haptics", 0).commit();
                GridModel.Item item = new GridModel.Item(-1, "test/app", 5, 0, 1, 1);
                GridModel persistent = new GridModel(); persistent.items.add(item); home.store.save(persistent);
                check(home.store.load().items.get(0).column == 5, "Layout round trip");
                home.store.save(home.model);
                home.canvas.setPosition(2); home.canvas.persistPosition();
                home.canvas.setPosition(home.store.prefs.getFloat("scrollColumns", 0));
                check(home.canvas.getScrollX() == 2 * home.canvas.cellWidth(), "Scroll persistence");
                home.onNewIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
                check(home.canvas.getScrollX() == 2 * home.canvas.cellWidth(), "Repeated Home retains position");
            }); passed("Home viewport, layout persistence and repeated Home");
            gesture(home);
            widget(home);
            verticalWidgetGesture(home);
            results.putString("stream", "\n" + report + "All foundation tests passed.\n");
            finish(Activity.RESULT_OK, results);
        } catch (Throwable error) {
            results.putString("stream", "\n" + report + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, results);
        }
    }
    private void grid() {
        GridModel model = new GridModel(); Random random = new Random(42);
        for (int i = 0; i < 250; i++) {
            GridModel.Item item = new GridModel.Item(i, "fixture", 0, 0, 1 + random.nextInt(8), 1 + random.nextInt(6));
            model.place(item, 0);
            check(model.fits(item, item.column, item.row, item.width, item.height), "No overlap " + i);
        }
        check(!model.fits(null, -1, 0, 1, 1), "Fixed left boundary");
        check(!model.fits(null, 0, 0, 1, 7), "Six-row maximum");
        passed("250 varied grid placements, collision avoidance, oversized widths and bounds");
    }
    private void gesture(HomeActivity home) throws Exception {
        main(() -> {
            home.canvas.setPosition(0);
            float width = home.canvas.getWidth(), y = home.canvas.getHeight() / 2f;
            touch(home.canvas, width * .85f, y, width * .15f, y, 500);
        });
        SystemClock.sleep(1500); waitForIdleSync();
        main(() -> {
            check(home.canvas.getScrollX() > 0, "Horizontal swipe moves Home");
            check(home.canvas.getScrollX() % home.canvas.cellWidth() == 0, "Column snap");
            home.canvas.setPosition(0);
            touch(home.canvas, 40, 40, 300, 40, 500);
        });
        SystemClock.sleep(700);
        main(() -> check(home.canvas.getScrollX() == 0, "Left edge does not overscroll"));
        passed("Horizontal scrolling, column snap and fixed left edge");
    }
    private void widget(HomeActivity home) throws Exception {
        AppWidgetProviderInfo info = AppWidgetManager.getInstance(getTargetContext()).getInstalledProviders().stream()
                .filter(p -> p.provider.equals(new ComponentName(getTargetContext(), FixtureWidget.class))).findFirst().orElseThrow();
        ActivityMonitor configMonitor = addMonitor(FixtureConfigActivity.class.getName(), null, false);
        main(() -> { home.canvas.setPosition(0); home.beginWidget(info); });
        // Exercise the ordinary user's system consent path, not a shell-granted permission.
        boolean accepted = false;
        for (int i = 0; i < 50 && !accepted; i++) {
            android.view.accessibility.AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                for (android.view.accessibility.AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId("android:id/button1")) {
                    if (!"dev.glgra.launcher".contentEquals(node.getPackageName())) accepted = node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
                }
            }
            if (!accepted) SystemClock.sleep(100);
        }
        check(accepted, "System widget binding consent accepted");
        Activity config = waitForMonitorWithTimeout(configMonitor, 5000);
        check(config != null, "Widget configuration activity opened");
        main(() -> config.findViewById(R.id.fixture_accept).performClick());
        waitForIdleSync(); SystemClock.sleep(700);
        main(() -> {
            check(home.model.items.stream().anyMatch(GridModel.Item::isWidget), "Configured widget committed");
            GridModel.Item item = home.model.items.stream().filter(GridModel.Item::isWidget).findFirst().orElseThrow();
            check(home.store.load().widget(item.widgetId) != null, "Widget persisted");
            check(AppWidgetManager.getInstance(home).getAppWidgetInfo(item.widgetId) != null, "Widget is bound");
        }); passed("Real widget allocation, binding, configuration and persistence");
        ActivityMonitor tapMonitor = addMonitor(SettingsActivity.class.getName(), null, false);
        java.util.concurrent.atomic.AtomicReference<View> nativeButton = new java.util.concurrent.atomic.AtomicReference<>();
        for (int i = 0; i < 50 && nativeButton.get() == null; i++) {
            main(() -> {
                // AppWidgetHostView is a root namespace; a lookup on its parent intentionally cannot cross it.
                for (int child = 0; child < home.canvas.getChildCount(); child++) {
                    View button = home.canvas.getChildAt(child).findViewById(R.id.fixture_action);
                    if (button != null) { nativeButton.set(button); break; }
                }
            });
            if (nativeButton.get() == null) SystemClock.sleep(100);
        }
        if (nativeButton.get() == null) main(() -> dump(home.canvas, ""));
        check(nativeButton.get() != null, "RemoteViews inflated; expected ID=" + R.id.fixture_action);
        main(() -> nativeButton.get().performClick());
        Activity settings = waitForMonitorWithTimeout(tapMonitor, 5000);
        check(settings != null, "RemoteViews PendingIntent tap"); main(settings::finish);
        waitForIdleSync();
        main(() -> {
            GridModel.Item actualWidget=home.model.items.stream().filter(GridModel.Item::isWidget).findFirst().orElseThrow();
            home.canvas.setPosition(actualWidget.column);
            touch(home.canvas, home.canvas.cellWidth() * 1.7f, home.canvas.cellHeight() * .5f, 10, home.canvas.cellHeight() * .5f, 500);
        });
        SystemClock.sleep(1200);
        main(() -> check(home.canvas.getScrollX() > 0, "Horizontal swipe over widget moves canvas"));
        passed("Widget native tap and horizontal gesture interception");
        getUiAutomation().adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET");
        removeMonitor(configMonitor); configMonitor = addMonitor(FixtureConfigActivity.class.getName(), null, false);
        main(() -> home.beginWidget(info));
        Activity cancelled = waitForMonitorWithTimeout(configMonitor, 5000); check(cancelled != null, "Second configuration opened");
        int[] before = home.host.getAppWidgetIds();
        main(cancelled::finish); waitForIdleSync(); SystemClock.sleep(500);
        main(() -> check(home.host.getAppWidgetIds().length == before.length - 1, "Cancelled widget ID released"));
        passed("Configuration cancellation releases widget ID");
        getUiAutomation().dropShellPermissionIdentity();
    }
    private void verticalWidgetGesture(HomeActivity home) {
        java.util.concurrent.atomic.AtomicReference<ScrollView> scrollingChild = new java.util.concurrent.atomic.AtomicReference<>();
        main(() -> {
            home.canvas.scroller.abortAnimation(); home.canvas.setPosition(0); home.canvas.removeAllViews();
            ScrollView child = new ScrollView(home); TextView content = new TextView(home); content.setText("Scrollable widget interaction fixture");
            content.setMinimumHeight(home.canvas.getHeight() * 3);
            child.addView(content, new ScrollView.LayoutParams(-1, home.canvas.getHeight() * 3));
            scrollingChild.set(child); home.canvas.attach(child, new GridModel.Item(99999, "gesture fixture", 0, 0, 4, 6));
            home.canvas.requestLayout();
        });
        SystemClock.sleep(250); waitForIdleSync();
        main(() -> {
            touch(home.canvas, home.canvas.getWidth() / 2f, home.canvas.getHeight() * .8f,
                    home.canvas.getWidth() / 2f, home.canvas.getHeight() * .2f, 500);
            check(scrollingChild.get().getScrollY() > 0, "Vertical child scrolling remains available; child="
                    + scrollingChild.get().getWidth() + "x" + scrollingChild.get().getHeight()
                    + ", content=" + scrollingChild.get().getChildAt(0).getHeight() + ", canvasX=" + home.canvas.getScrollX());
            check(home.canvas.getScrollX() == 0, "Vertical widget gesture does not move Home");
            home.render(); home.canvas.setPosition(0); home.canvas.persistPosition();
        });
        passed("Vertical child gesture preserved without horizontal canvas movement");
    }
    private void touch(View view, float x1, float y1, float x2, float y2, long duration) {
        long start = SystemClock.uptimeMillis();
        for (int i = 0; i <= 10; i++) {
            int action = i == 0 ? MotionEvent.ACTION_DOWN : i == 10 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            MotionEvent event = MotionEvent.obtain(start, start + duration * i / 10, action,
                    x1 + (x2 - x1) * i / 10, y1 + (y2 - y1) * i / 10, 0);
            view.dispatchTouchEvent(event); event.recycle();
        }
    }
    private void dump(View view, String indent) {
        report.append(indent).append(view.getClass().getName()).append(" id=").append(view.getId());
        if (view instanceof TextView) report.append(" text=").append(((TextView) view).getText());
        report.append('\n');
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) dump(group.getChildAt(i), indent + "  ");
        }
    }
}

