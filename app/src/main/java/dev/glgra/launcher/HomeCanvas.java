package dev.glgra.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.OverScroller;

/** A single scroll owner arbitrates horizontal drags before any hosted widget can consume them. */
final class HomeCanvas extends ViewGroup {
    final GridModel model;
    final OverScroller scroller;
    private final LayoutStore store;
    private final Paint paint = new Paint();
    private final int slop;
    private float downX, downY, lastX;
    private boolean horizontal, vertical, settling, animating, restoring;
    private VelocityTracker velocity;
    private int lastColumn;
    private long lastTick;
    private final Runnable emptyLongPress;
    private boolean emptyGesture, drawerGestureAllowed, drawerGesture, drawerOpened;
    boolean editing;
    Runnable onEdit;
    Runnable onSwipeUp;
    boolean manipulating, moveMode;
    int dropColumn = -1, dropRow;
    int extraColumns;
    java.util.function.Consumer<MotionEvent> onManipulate;
    HomeCanvas(Context context, GridModel model, LayoutStore store) {
        super(context); this.model = model; this.store = store;
        scroller = new OverScroller(context);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setWillNotDraw(false); setClipChildren(true); setSoundEffectsEnabled(false);
        emptyLongPress = () -> { if (emptyGesture && !horizontal && !vertical && onEdit != null) onEdit.run(); };
    }
    int cellWidth() { return Math.max(1, getWidth() / 4); }
    int cellHeight() { return Math.max(1, getHeight() / 6); }
    int maxScroll() { return Math.max(0, (model.columns()+extraColumns) * cellWidth() - getWidth()); }
    void attach(View view, GridModel.Item item) { view.setTag(item); addView(view); }
    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h));
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i); GridModel.Item item = (GridModel.Item) child.getTag();
            child.measure(MeasureSpec.makeMeasureSpec(item.width * cellWidth(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(item.height * cellHeight(), MeasureSpec.EXACTLY));
        }
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i); GridModel.Item item = (GridModel.Item) child.getTag();
            int x = item.column * cellWidth(), y = item.row * cellHeight();
            child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
        }
        if (changed) setPosition(store.prefs.getFloat("scrollColumns", 0));
    }
    void setPosition(float columns) {
        scroller.abortAnimation(); animating = false; settling = false;
        restoring = true;
        scrollTo(Math.max(0, Math.min(maxScroll(), Math.round(columns * cellWidth()))), 0);
        lastColumn = Math.round((float) getScrollX() / cellWidth());
        restoring = false;
    }
    void persistPosition() { store.prefs.edit().putFloat("scrollColumns", (float) getScrollX() / cellWidth()).apply(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (editing) {
            paint.setColor(0x55FFFFFF); paint.setStrokeWidth(1);
            for (int x = getScrollX() / cellWidth(); x <= (getScrollX() + getWidth()) / cellWidth(); x++)
                canvas.drawLine(x * cellWidth(), 0, x * cellWidth(), getHeight(), paint);
            for (int y = 0; y <= 6; y++) canvas.drawLine(getScrollX(), y * cellHeight(), getScrollX() + getWidth(), y * cellHeight(), paint);
        }
        if (dropColumn >= 0) { paint.setColor(0x6680CBC4);canvas.drawRect(dropColumn*cellWidth(),dropRow*cellHeight(),(dropColumn+1)*cellWidth(),(dropRow+1)*cellHeight(),paint); }
    }
    @Override public void requestDisallowInterceptTouchEvent(boolean disallow) {
        // Vertical widget interactions work; horizontal widget gestures intentionally yield to Home.
        if (vertical) super.requestDisallowInterceptTouchEvent(disallow);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
            scroller.abortAnimation(); settling = false; animating = false;
            horizontal = false; vertical = false; drawerGesture = false; drawerOpened = false;
            downX = lastX = event.getX(); downY = event.getY();
            if (velocity != null) velocity.recycle();
            velocity = VelocityTracker.obtain();
            emptyGesture = true; drawerGestureAllowed = true;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (downX + getScrollX() >= child.getLeft() && downX + getScrollX() < child.getRight()
                        && downY >= child.getTop() && downY < child.getBottom()) {
                    emptyGesture = false;
                    GridModel.Item item = (GridModel.Item) child.getTag();
                    // Icons and closed folders have no vertical content; real widgets keep theirs.
                    drawerGestureAllowed = item != null && (item.isApp() || item.isFolder() || item.kind.equals("shortcut"));
                    break;
                }
            }
            if (emptyGesture) postDelayed(emptyLongPress, ViewConfiguration.getLongPressTimeout());
        }
        if (velocity != null) velocity.addMovement(event);
        if (action == MotionEvent.ACTION_MOVE && (Math.abs(event.getX() - downX) > slop || Math.abs(event.getY() - downY) > slop)) {
            removeCallbacks(emptyLongPress);
        }
        boolean handled = super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(emptyLongPress); emptyGesture = false;
            if (velocity != null) { velocity.recycle(); velocity = null; }
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        return handled;
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (moveMode) return true;
        if (manipulating) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && !vertical) {
            float dx = Math.abs(event.getX() - downX), dy = Math.abs(event.getY() - downY);
            if (dx > slop && dx > dy) { horizontal = true; return true; }
            if (dy > slop && dy > dx) {
                vertical = true;
                if (!horizontal && !editing && drawerGestureAllowed && event.getY() < downY && onSwipeUp != null) {
                    drawerGesture = true;
                    return true; // Cancel the icon's click before delivering this gesture to Home.
                }
            }
        }
        return horizontal;
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (moveMode && onManipulate != null) { onManipulate.accept(event); return true; }
        if (drawerGesture) {
            if (!drawerOpened) { drawerOpened = true; emptyGesture = false; removeCallbacks(emptyLongPress); onSwipeUp.run(); }
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                // Empty wallpaper is already handled by this view, so no interception callback runs.
                if (!manipulating && !editing && !horizontal && drawerGestureAllowed && onSwipeUp != null
                        && downY - event.getY() > slop && downY - event.getY() > Math.abs(event.getX() - downX)) {
                    vertical = true; drawerGesture = true; drawerOpened = true; emptyGesture = false;
                    removeCallbacks(emptyLongPress); onSwipeUp.run(); return true;
                }
                if (emptyGesture && !horizontal && Math.abs(event.getY()-downY)>slop && Math.abs(event.getY()-downY)>Math.abs(event.getX()-downX)) {
                    vertical=true;
                    if (editing) getParent().requestDisallowInterceptTouchEvent(false);
                }
                float dx = event.getX() - lastX;
                if (!vertical && (horizontal || Math.abs(event.getX() - downX) > slop)) {
                    horizontal = true;
                    scrollTo(Math.max(0, Math.min(maxScroll(), getScrollX() - Math.round(dx))), 0);
                }
                lastX = event.getX(); return true;
            case MotionEvent.ACTION_UP:
                if (horizontal && velocity != null) {
                    velocity.computeCurrentVelocity(1000, ViewConfiguration.get(getContext()).getScaledMaximumFlingVelocity());
                    int speed = Math.round(-velocity.getXVelocity());
                    if (Math.abs(speed) > ViewConfiguration.get(getContext()).getScaledMinimumFlingVelocity() && ValueAnimator.areAnimatorsEnabled()) {
                        settling = false; animating = true;
                        scroller.fling(getScrollX(), 0, speed, 0, 0, maxScroll(), 0, 0);
                        postInvalidateOnAnimation();
                    } else snap();
                } else { snap(); if (!vertical) performClick(); }
                return true;
            case MotionEvent.ACTION_CANCEL: snap(); return true;
            default: return true;
        }
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    private void snap() {
        int target = Math.max(0, Math.min(maxScroll(), Math.round((float) getScrollX() / cellWidth()) * cellWidth()));
        if (target != getScrollX() && ValueAnimator.areAnimatorsEnabled()) {
            settling = true; animating = true; scroller.startScroll(getScrollX(), 0, target - getScrollX(), 0, 160); postInvalidateOnAnimation();
        } else { scrollTo(target, 0); persistPosition(); }
    }
    @Override public void computeScroll() {
        if (!animating) return;
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), 0);
            postInvalidateOnAnimation();
        } else {
            animating = false;
            if (settling) { settling = false; persistPosition(); }
            else snap();
        }
    }
    @Override protected void onScrollChanged(int x, int y, int oldX, int oldY) {
        super.onScrollChanged(x, y, oldX, oldY);
        int column = x / cellWidth();
        if (!restoring && column != lastColumn && SystemClock.uptimeMillis() - lastTick > 35) {
            Haptics.tick(this, store.prefs.getInt("haptics", 2)); lastTick = SystemClock.uptimeMillis();
        }
        lastColumn = column;
        if (getWindowToken()!=null) {
            android.app.WallpaperManager wm=android.app.WallpaperManager.getInstance(getContext());
            try { wm.setWallpaperOffsets(getWindowToken(),store.prefs.getBoolean("parallax",false)&&maxScroll()>0?(float)x/maxScroll():.5f,.5f); }catch(IllegalArgumentException ignored){}
        }
    }
}
