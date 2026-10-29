package dev.glgra.launcher;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A read-only view: taps expand local previews, never dispatch a notification's content or action intents. */
public final class NotificationPanel extends ScrollView {
    private final LinearLayout body;
    private final Set<String> groups = new HashSet<>(), messages = new HashSet<>(), media = new HashSet<>();
    private final Map<String, ImageView> mediaViews = new HashMap<>();
    private final android.util.LruCache<String, Bitmap> thumbnails = new android.util.LruCache<>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
        @Override protected void entryRemoved(boolean evicted, String key, Bitmap old, Bitmap replacement) {
            if (evicted && mediaViews.containsKey(key)) mediaViews.get(key).setImageResource(android.R.drawable.ic_menu_gallery);
        }
    };
    private final Map<String, Long> mediaVersions = new HashMap<>();
    private final Map<String, String> appNames = new HashMap<>();
    private final Map<String, Drawable> appIcons = new HashMap<>();
    private final Set<String> requested = new HashSet<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable observer = this::refresh;
    private final Map<String, View> rows = new LinkedHashMap<>();
    private Viewport pendingViewport;
    private boolean touching, refreshPending, disposed;
    private long lastScroll;
    private final Runnable refreshWhenQuiet = () -> {
        if (!refreshPending || disposed || touching) return;
        long remaining = 160 - (android.os.SystemClock.uptimeMillis() - lastScroll);
        if (remaining > 0) main.postDelayed(this.refreshWhenQuiet, remaining);
        else { refreshPending = false; rebuild(); }
    };
    private ExecutorService decoder;

    private int generation;
    private final String emptyMessage;

    public NotificationPanel(Context context) {
        super(context);
        setVerticalScrollBarEnabled(false); setHorizontalScrollBarEnabled(false);
        setFillViewport(true); setSoundEffectsEnabled(false); setClipToOutline(true);
        setFocusableInTouchMode(true); setDescendantFocusability(FOCUS_BEFORE_DESCENDANTS);
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(dark() ? 0xCF20292B : 0xDDF5F8F7); surface.setCornerRadius(dp(22)); setBackground(surface);
        body = new LinearLayout(context); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(24), dp(20), dp(24));
        addView(body, new ScrollView.LayoutParams(-1, -2));
        String[] quiet = {"Suspiciously peaceful.", "All caught up. Allegedly.", "Nothing to see here. A rare achievement.", "The apps are behaving. For now."};
        emptyMessage = quiet[new java.util.Random().nextInt(quiet.length)];
        refresh();
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); if (getWindowVisibility() == View.VISIBLE) NotificationRepository.addListener(observer); refresh();
    }
    @Override protected void onDetachedFromWindow() {
        cancelDeferredRefresh(); NotificationRepository.removeListener(observer); super.onDetachedFromWindow();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (body == null || disposed) return;
        if (visibility == View.VISIBLE) { NotificationRepository.addListener(observer); refresh(); }
        else {
            cancelDeferredRefresh(); pendingViewport = null;
            NotificationRepository.removeListener(observer); groups.clear(); messages.clear(); media.clear();
            generation++; requested.clear();
            if (decoder != null) { decoder.shutdownNow(); decoder = null; }
            scrollTo(0, 0);
        }
    }
    public void reset() {
        cancelDeferredRefresh(); pendingViewport = null;
        groups.clear(); messages.clear(); media.clear(); scrollTo(0, 0); rebuild();
    }
    public void dispose() {
        disposed = true; generation++; NotificationRepository.removeListener(observer);
        cancelDeferredRefresh(); pendingViewport = null;
        if (decoder != null) decoder.shutdownNow();
        thumbnails.evictAll(); requested.clear(); mediaViews.clear(); mediaVersions.clear(); appNames.clear(); appIcons.clear();
    }
    public void refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::refresh); return; }
        if (disposed || (isAttachedToWindow() && getWindowVisibility() != View.VISIBLE)) return;
        // Keep existing touch targets alive until a drag or native fling has settled.
        if (touching || android.os.SystemClock.uptimeMillis() - lastScroll < 160) {
            refreshPending = true; main.removeCallbacks(refreshWhenQuiet);
            if (!touching) main.postDelayed(refreshWhenQuiet, 160);
            return;
        }
        rebuild();
    }
    private void rebuild() {
        if (disposed) return;
        if (pendingViewport == null) pendingViewport = captureViewport();
        // Removed focused descendants otherwise ask ScrollView to reveal their replacements.
        if (isInTouchMode() && hasFocus()) requestFocus();
        body.removeAllViews(); rows.clear(); mediaViews.clear();
        if (!NotificationRepository.accessGranted(getContext())) {
            body.addView(text("Your notifications, at a glance.", 16));
            Button access = new Button(getContext()); access.setText("Enable notification previews"); access.setAllCaps(false);
            access.setTextColor(Glass.ink(getContext()));access.setTextSize(14);Glass.control(access); access.setOnClickListener(v -> {
                try { getContext().startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (android.content.ActivityNotFoundException ignored) { }
            }); body.addView(access); return;
        }
        Map<String, List<StatusBarNotification>> byApp = new LinkedHashMap<>();
        Set<String> presentKeys = new HashSet<>();
        for (StatusBarNotification record : NotificationRepository.snapshot()) {
            byApp.computeIfAbsent(record.getPackageName(), ignored -> new ArrayList<>()).add(record);
            presentKeys.add(record.getKey());
            Long previous = mediaVersions.put(record.getKey(), record.getPostTime());
            if (previous == null || previous != record.getPostTime()) {
                thumbnails.remove(record.getKey()); requested.remove(record.getKey());
            }
        }
        groups.retainAll(byApp.keySet()); messages.retainAll(presentKeys); media.retainAll(presentKeys);
        for (String key : thumbnails.snapshot().keySet()) if (!presentKeys.contains(key)) thumbnails.remove(key);
        requested.retainAll(presentKeys);
        mediaVersions.keySet().retainAll(presentKeys);
        if (byApp.isEmpty()) {
            TextView empty = text(NotificationRepository.connected() ? emptyMessage : "Waiting for notification access to connect…", 18);
            empty.setGravity(Gravity.CENTER); empty.setPadding(0, dp(26), 0, dp(26)); body.addView(empty);
        }
        for (Map.Entry<String, List<StatusBarNotification>> entry : byApp.entrySet()) addGroup(entry.getKey(), entry.getValue());
    }
    private static final class Viewport {
        final String key; final int offset, scroll;
        Viewport(String key, int offset, int scroll) { this.key = key; this.offset = offset; this.scroll = scroll; }
    }
    private int contentTop(View view) {
        int top = 0;
        while (view != this) {
            top += view.getTop();
            if (!(view.getParent() instanceof View)) break;
            view = (View) view.getParent();
        }
        return top;
    }
    private Viewport captureViewport() {
        int scroll = getScrollY();
        for (Map.Entry<String, View> entry : rows.entrySet()) {
            View row = entry.getValue(); int top = contentTop(row);
            if (top + row.getHeight() > scroll)
                return new Viewport(entry.getKey(), scroll - top, scroll);
        }
        return new Viewport(null, 0, scroll);
    }
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        Viewport viewport = pendingViewport; pendingViewport = null;
        if (viewport != null) {
            View anchor = rows.get(viewport.key);
            int position = anchor == null ? viewport.scroll : contentTop(anchor)
                    + Math.min(viewport.offset, Math.max(0, anchor.getHeight() - 1));
            scrollTo(0, position);
        }
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            touching = true; pendingViewport = null; main.removeCallbacks(refreshWhenQuiet);
        }
        boolean handled = super.dispatchTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            touching = false;
            if (refreshPending) main.postDelayed(refreshWhenQuiet, 160);
        }
        return handled;
    }
    @Override protected void onScrollChanged(int x, int y, int oldX, int oldY) {
        super.onScrollChanged(x, y, oldX, oldY);
        lastScroll = android.os.SystemClock.uptimeMillis();
        if (refreshPending && !touching) {
            main.removeCallbacks(refreshWhenQuiet); main.postDelayed(refreshWhenQuiet, 160);
        }
    }
    private void cancelDeferredRefresh() {
        touching = false; refreshPending = false; main.removeCallbacks(refreshWhenQuiet);
    }
    private void refreshPreview() {
        if (isInTouchMode()) requestFocus();
        Set<String> previousRows=new HashSet<>(rows.keySet());
        refreshPending = false; main.removeCallbacks(refreshWhenQuiet); rebuild();
        if(android.animation.ValueAnimator.areAnimatorsEnabled())for(Map.Entry<String,View> entry:rows.entrySet())if(!previousRows.contains(entry.getKey())){
            View row=entry.getValue();row.setAlpha(0);row.animate().alpha(1).setDuration(160).setInterpolator(Motion.EFFECTS).start();
        }
    }
    private void addGroup(String packageName, List<StatusBarNotification> records) {
        LinearLayout section = new LinearLayout(getContext()); section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header = new LinearLayout(getContext()); header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(12), 0, dp(12));header.setMinimumHeight(dp(64)); header.setSoundEffectsEnabled(false);
        ImageView icon = new ImageView(getContext()); icon.setImageDrawable(appIcon(packageName));
        header.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));
        String groupLabel=appName(packageName)+"  "+records.size();
        android.text.SpannableString groupText=new android.text.SpannableString(groupLabel);
        groupText.setSpan(new android.text.style.RelativeSizeSpan(.75f),appName(packageName).length(),groupLabel.length(),0);
        groupText.setSpan(new android.text.style.ForegroundColorSpan(0xBFF2F4F8),appName(packageName).length(),groupLabel.length(),0);
        TextView name = text(groupLabel, 16);name.setText(groupText);
        Design.type(name,16,24,600);name.setPadding(dp(12), 0, dp(8), 0); name.setMaxLines(2);
        header.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView time = text(relative(records.get(0).getPostTime()), 12); time.setAlpha(.76f); header.addView(time);
        header.setContentDescription(appName(packageName) + ", " + records.size() + " active notifications, " + (groups.contains(packageName) ? "expanded" : "collapsed"));
        header.setOnClickListener(v -> {
            if (!groups.add(packageName)) groups.remove(packageName);
            Haptics.tick(v, new LayoutStore(getContext()).prefs.getInt("haptics", 2)); refreshPreview();
        });
        rows.put("group:" + packageName, header);
        section.addView(header);
        if (groups.contains(packageName)) for (StatusBarNotification record : records) addMessage(section, record);
        LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(-1, -2); sectionParams.bottomMargin = dp(16);
        body.addView(section, sectionParams);
    }
    private void addMessage(LinearLayout section, StatusBarNotification record) {
        Notification notification = record.getNotification(); Bundle extras = notification.extras;
        String title = chars(extras, Notification.EXTRA_TITLE);
        String plain = chars(extras, Notification.EXTRA_TEXT);
        String full = chars(extras, Notification.EXTRA_BIG_TEXT);
        if (full.isEmpty()) {
            CharSequence[] lines = null;
            try { if (extras != null) lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES); }
            catch (RuntimeException ignored) { }
            full = lines == null ? "" : TextUtils.join("\n", lines);
        }
        if (full.isEmpty()) full = messagingText(extras);
        if (full.isEmpty()) full = plain;
        boolean hidden = full.isEmpty() && title.isEmpty();
        if (hidden) full = "Content hidden";
        String key = record.getKey();
        LinearLayout row = new LinearLayout(getContext()); row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(4), dp(12), dp(4), dp(16));
        if (!title.isEmpty()) { TextView heading = text(title, 14); Design.type(heading,14,20,600);heading.setPadding(0,0,0,dp(4)); row.addView(heading); }
        TextView content = text(full.isEmpty() ? "Content hidden" : full, 14);
        content.setMinHeight(dp(48));content.setMaxLines(messages.contains(key) ? Integer.MAX_VALUE : 3); content.setEllipsize(TextUtils.TruncateAt.END);
        content.setOnClickListener(v -> { if (!messages.add(key)) messages.remove(key); refreshPreview(); });
        row.addView(content);
        TextView timestamp = text(relative(record.getPostTime()), 12); timestamp.setAlpha(.76f); timestamp.setPadding(0, dp(4), 0, 0); row.addView(timestamp);
        if (!hidden) addMedia(row, record);
        section.addView(row);
        rows.put("notification:" + key, row);
    }
    private void addMedia(LinearLayout row, StatusBarNotification record) {
        String key = record.getKey(); Bundle extras = record.getNotification().extras;
        if (extras == null) return;
        // Only system-supplied image objects/local content URIs are read. No PendingIntent or network is opened.
        Object picture;
        Icon pictureIcon;
        try { picture = extras.get(Notification.EXTRA_PICTURE); pictureIcon = extras.getParcelable(Notification.EXTRA_PICTURE_ICON, Icon.class); }
        catch (RuntimeException ignored) { return; }
        if (pictureIcon != null && pictureIcon.getType() != Icon.TYPE_BITMAP && pictureIcon.getType() != Icon.TYPE_ADAPTIVE_BITMAP
                && pictureIcon.getType() != Icon.TYPE_URI && pictureIcon.getType() != Icon.TYPE_URI_ADAPTIVE_BITMAP) pictureIcon = null;
        final Icon imageIcon = pictureIcon;
        final android.net.Uri messageImage = messageImage(extras);
        if (!(picture instanceof Bitmap) && imageIcon == null && messageImage == null) return;
        ImageView preview = new ImageView(getContext()); preview.setAdjustViewBounds(true); preview.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        mediaViews.put(key, preview);
        preview.setImageResource(android.R.drawable.ic_menu_gallery);
        preview.setContentDescription(media.contains(key) ? "Collapse image preview" : "Expand image preview");
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(media.contains(key) ? 240 : 68)); params.topMargin = dp(7); row.addView(preview, params);
        preview.setSoundEffectsEnabled(false); preview.setOnClickListener(v -> { if (!media.add(key)) media.remove(key); refreshPreview(); });
        Bitmap cached = thumbnails.get(key);
        if (cached != null) { preview.setImageBitmap(cached); return; }
        if (!requested.add(key)) return;
        if (decoder == null) decoder = Executors.newSingleThreadExecutor();
        int token = generation;
        long stamp = record.getPostTime();
        decoder.execute(() -> {
            Bitmap small = null;
            try {
                if (picture instanceof Bitmap) small = downsample((Bitmap) picture);
                else if (imageIcon != null && (imageIcon.getType() == Icon.TYPE_URI || imageIcon.getType() == Icon.TYPE_URI_ADAPTIVE_BITMAP)) {
                    small = decodeLocalImage(imageIcon.getUri());
                } else if (imageIcon != null) {
                    Drawable loaded = imageIcon.loadDrawable(getContext());
                    if (loaded instanceof BitmapDrawable) small = downsample(((BitmapDrawable) loaded).getBitmap());
                    else if (loaded != null) {
                        small = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888);
                        loaded.setBounds(0, 0, 320, 320); loaded.draw(new android.graphics.Canvas(small));
                    }
                } else small = decodeLocalImage(messageImage);
            } catch (RuntimeException | java.io.IOException ignored) { }
            Bitmap result = small;
            main.post(() -> {
                requested.remove(key);
                if (!disposed && token == generation && mediaVersions.getOrDefault(key, -1L) == stamp && result != null) {
                    thumbnails.put(key, result);
                    ImageView current = mediaViews.get(key); if (current != null) current.setImageBitmap(result);
                }
            });
        });
    }
    private Bitmap downsample(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return null;
        float ratio = Math.min(1f, 640f / Math.max(bitmap.getWidth(), bitmap.getHeight()));
        return Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * ratio)), Math.max(1, Math.round(bitmap.getHeight() * ratio)), true);
    }
    private Bitmap decodeLocalImage(android.net.Uri uri) throws java.io.IOException {
        if (uri == null || !"content".equals(uri.getScheme())) return null;
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContext().getContentResolver(), uri), (decoder, info, source) -> {
            int width = info.getSize().getWidth(), height = info.getSize().getHeight();
            float ratio = Math.min(1f, 640f / Math.max(width, height));
            decoder.setTargetSize(Math.max(1, Math.round(width * ratio)), Math.max(1, Math.round(height * ratio)));
        });
    }
    private android.net.Uri messageImage(Bundle extras) {
        try {
            android.os.Parcelable[] bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (bundles == null) return null;
            List<Notification.MessagingStyle.Message> entries = Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles);
            for (int i = entries.size() - 1; i >= 0; i--) {
                Notification.MessagingStyle.Message message = entries.get(i);
                if (message.getDataMimeType() != null && message.getDataMimeType().startsWith("image/")
                        && message.getDataUri() != null && "content".equals(message.getDataUri().getScheme())) return message.getDataUri();
            }
        } catch (RuntimeException ignored) { }
        return null;
    }
    private String messagingText(Bundle extras) {
        if (extras == null) return "";
        android.os.Parcelable[] entries;
        try { entries = extras.getParcelableArray(Notification.EXTRA_MESSAGES); }
        catch (RuntimeException ignored) { return ""; }
        if (entries == null) return "";
        List<String> lines = new ArrayList<>();
        try {
            for (Notification.MessagingStyle.Message entry : Notification.MessagingStyle.Message.getMessagesFromBundleArray(entries))
                if (entry.getText() != null) lines.add(entry.getText().toString());
        } catch (RuntimeException ignored) { }
        return TextUtils.join("\n", lines);
    }
    private String chars(Bundle extras, String name) {
        if (extras == null) return "";
        try { CharSequence value = extras.getCharSequence(name); return value == null ? "" : value.toString(); }
        catch (RuntimeException ignored) { return ""; }
    }
    private String appName(String packageName) {
        if (appNames.containsKey(packageName)) return appNames.get(packageName);
        String label;
        try { label = getContext().getPackageManager().getApplicationLabel(getContext().getPackageManager().getApplicationInfo(packageName, 0)).toString(); }
        catch (android.content.pm.PackageManager.NameNotFoundException e) { label = packageName; }
        appNames.put(packageName, label); return label;
    }
    private Drawable appIcon(String packageName) {
        if (appIcons.containsKey(packageName)) return appIcons.get(packageName);
        Drawable icon;
        try { icon = getContext().getPackageManager().getApplicationIcon(packageName); }
        catch (android.content.pm.PackageManager.NameNotFoundException e) { icon = getContext().getPackageManager().getDefaultActivityIcon(); }
        appIcons.put(packageName, icon); return icon;
    }
    private String relative(long time) {
        long minutes = Math.max(0, System.currentTimeMillis() - time) / 60000;
        if (minutes < 1) return "Now";
        if (minutes < 60) return minutes + "m";
        if (minutes < 24 * 60) return (minutes / 60) + "h";
        if (minutes < 48 * 60) return "Yesterday";
        return (minutes / (24 * 60)) + "d";
    }
    private TextView text(String value, int size) {
        TextView text = new TextView(getContext()); text.setText(value); text.setTextSize(size);
        Design.type(text,size,size<=12?16:size<=14?22:24,400);text.setTextColor(Glass.ink(getContext()));text.setShadowLayer(.7f*getResources().getDisplayMetrics().density,0,0,0x80000000); text.setSoundEffectsEnabled(false); return text;
    }
    private boolean dark() { return (getResources().getConfiguration().uiMode & 0x30) == 0x20; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
