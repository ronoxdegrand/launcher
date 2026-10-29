package dev.glgra.launcher;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.view.MotionEvent;
import android.view.InputDevice;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Real framework integration checks, with fixture-owned state restored on failure as well as success. */
public final class IntegrationTests extends Instrumentation {
    private final StringBuilder report = new StringBuilder();
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    private void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private void passed(String message) {
        report.append("PASS ").append(message).append('\n');
        Bundle progress = new Bundle(); progress.putString("stream", "PASS " + message + "\n"); sendStatus(0, progress);
    }
    private void main(Runnable action) {
        java.util.concurrent.atomic.AtomicReference<Throwable> problem = new java.util.concurrent.atomic.AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { problem.set(failure); } });
        if (problem.get() != null) throw new AssertionError("Main-thread check failed", problem.get());
    }
    private void await(BooleanSupplier ready, long timeout, String message) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        do { if (ready.getAsBoolean()) return; SystemClock.sleep(100); } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError(message);
    }
    private void awaitUi(BooleanSupplier ready, long timeout, String message) {
        await(() -> { AtomicBoolean result = new AtomicBoolean(); main(() -> result.set(ready.getAsBoolean())); return result.get(); }, timeout, message);
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            models(); persistence(); catalog(); updater(); canvasGestures();
            HomeActivity home = (HomeActivity) startActivitySync(new Intent(getTargetContext(), HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            awaitUi(() -> home.catalog.all().size() > 0 && home.canvas.getWidth() > 0, 15000, "Home/catalog did not become ready");
            drawer(home); nativeDrags(home); notifications(home); transientMenus(home); demoExamples(home);
            result.putString("stream", "\n" + report + "All integrated tests passed.\n"); finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "\n" + report + android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private GridModel.Item app(String packageName, int column, int row) {
        return new GridModel.Item(-1, packageName + "/" + packageName + ".Main", column, row, 1, 1);
    }
    private void noOverlap(GridModel model) {
        for (GridModel.Item item : model.items) check(model.fits(item, item.column, item.row, item.width, item.height), "Grid overlap/out-of-bounds");
    }
    private void models() {
        GridModel model = new GridModel(); GridModel.Item a = app("probe.a", 3, 0), b = app("probe.b", 4, 0);
        model.items.add(a); model.items.add(b); model.merge(a, b);
        GridModel.Item folder = model.items.stream().filter(GridModel.Item::isFolder).findFirst().orElseThrow();
        check(folder.children.size() == 2, "Two apps create one folder");
        model.merge(a, folder);
        check(model.items.contains(folder) && folder.children.size() == 2 && model.find(a.identity()) == a, "Dropping a child onto its own folder is lossless");
        model.move(a, 8, 2, 1, 1);
        check(!model.items.contains(folder) && model.items.contains(a) && model.items.contains(b), "Moving one child dissolves a two-app folder");
        check(a.column == 8 && a.row == 2 && b.width == 1 && b.height == 1, "Child relocation and remaining app footprint");
        check(model.items.stream().filter(i -> i.identity().equals(a.identity())).count() == 1, "No duplicate app after relocation");
        GridModel.Item c = app("probe.c", 0, 0); model.items.add(c); model.reserveNotifications(true, 3); noOverlap(model);
        check(model.notification().width == 3 && model.notification().height == 6, "Reserved default notification footprint");
        model.reserveNotifications(true, 4); noOverlap(model);
        for (GridModel.Item item : model.items) if (!item.isNotification()) check(item.column >= 4, "Widening notifications displaces neighbors");
        model.reserveNotifications(false, 4); noOverlap(model);
        check(model.notification() == null && model.fits(null, 0, 0, 1, 1), "Removing notification widget frees cells at fixed left boundary");
        passed("Folder self-drop, uniqueness, dissolution/relocation and notification reservation resizing");
    }
    private void persistence() {
        main(() -> {
            LayoutStore store = new LayoutStore(getTargetContext()); String previous = store.prefs.getString("layout", null);
            try {
                GridModel model = new GridModel(); GridModel.Item a = app("probe.a", 3, 1), b = app("probe.b", 4, 1);
                model.items.add(a); model.items.add(b); model.merge(a, b);
                GridModel.Item folder = model.items.get(0); folder.name = "Round-trip folder";
                GridModel.Item shortcut = app("probe.shortcuts", 8, 2); shortcut.kind = "shortcut"; shortcut.shortcutId = "unique-shortcut"; shortcut.name = "Shortcut label";
                model.items.add(shortcut); store.save(model); GridModel loaded = store.load();
                GridModel.Item restored = loaded.items.stream().filter(GridModel.Item::isFolder).findFirst().orElseThrow();
                check(restored.uid.equals(folder.uid) && restored.name.equals(folder.name) && restored.children.size() == 2, "Folder key/name/children round trip");
                for (GridModel.Item child : folder.children) check(loaded.find(child.identity()) != null, "Nested identity survives persistence");
                GridModel.Item restoredShortcut = loaded.find(shortcut.identity());
                check(restoredShortcut != null && restoredShortcut.uid.equals(shortcut.uid) && restoredShortcut.shortcutId.equals(shortcut.shortcutId), "Unique shortcut key survives persistence");
                noOverlap(loaded);
            } finally {
                SharedPreferences.Editor edit = store.prefs.edit(); if (previous == null) edit.remove("layout"); else edit.putString("layout", previous); edit.commit();
            }
        });
        passed("Nested layout, folder names, stable IDs and shortcut identity persistence");
    }
    private void catalog() {
        SearchMatchTests.verify();
        AppCatalog catalog = new AppCatalog(getTargetContext()); catalog.reload();
        check(!catalog.all().isEmpty(), "Installed apps discovered");
        check(AppCatalog.match("calnedar", "calendar") < 10000, "Adjacent-letter typo is searchable");
        check(AppCatalog.match("cal", "calendar") < AppCatalog.match("calnedar", "calendar"), "Partial exact match outranks typo");
        check(AppCatalog.match("calendar", "calendar") < AppCatalog.match("cal", "calendar"), "Exact full name outranks partial");
        UsageRepository usage = new UsageRepository(getTargetContext());
        check(!usage.hasAccess(), "Test environment must deny usage access for the fallback check"); usage.refresh();
        check(usage.score(catalog.all().get(0).packageName) == 0, "Denied usage access falls back without querying/tracking");
        LayoutStore store = new LayoutStore(getTargetContext()); boolean hadHidden = store.prefs.contains("hidden"), hadPins = store.prefs.contains("dockPins");
        Set<String> previousHidden = new HashSet<>(store.prefs.getStringSet("hidden", Set.of())); String previousPins = store.prefs.getString("dockPins", "[]");
        try {
            store.prefs.edit().putStringSet("hidden", Set.of()).putString("dockPins", "[]").commit();
            List<AppCatalog.Entry> dock = catalog.dock(usage);
            if (catalog.all().size() >= 4) check(dock.size() == 4 && new HashSet<>(dock).size() == 4, "Four distinct dock slots");
            AppCatalog.Entry hidden = catalog.all().stream().filter(e -> !e.normalized.isEmpty()).findFirst().orElseThrow();
            store.prefs.edit().putStringSet("hidden", Set.of(hidden.packageName)).commit();
            check(catalog.ranked(usage).stream().noneMatch(e -> e.packageName.equals(hidden.packageName)), "Hidden app excluded from ordinary rankings");
            check(catalog.search("", usage).stream().noneMatch(e -> e.packageName.equals(hidden.packageName)), "Hidden app excluded from empty-query defaults");
            check(catalog.search(hidden.label, usage).stream().anyMatch(e -> e.key.equals(hidden.key)), "Explicit search can discover hidden app");
            check(catalog.search("???", usage).isEmpty(), "Punctuation-only query does not expose default/hidden results");
        } finally {
            SharedPreferences.Editor restore = store.prefs.edit(); if (hadHidden) restore.putStringSet("hidden", previousHidden); else restore.remove("hidden");
            if (hadPins) restore.putString("dockPins", previousPins); else restore.remove("dockPins"); restore.commit();
        }
        passed("App discovery, lexical/fuzzy ordering, hidden-app search, empty defaults and denied-usage fallback");
    }
    private PackageInfo cloneInfo(PackageInfo original) {
        Parcel parcel = Parcel.obtain();
        try { original.writeToParcel(parcel, 0); parcel.setDataPosition(0); return PackageInfo.CREATOR.createFromParcel(parcel); }
        finally { parcel.recycle(); }
    }
    private interface Checked { void run() throws Exception; }
    private void rejected(Checked action, String reason) throws Exception {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Updater accepted " + reason);
    }
    private void updater() throws Exception {
        PackageInfo installed = getTargetContext().getPackageManager().getPackageInfo(getTargetContext().getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo good = cloneInfo(installed); good.setLongVersionCode(installed.getLongVersionCode() + 1); good.versionName = "99.0.0";
        UpdateRepository.validateCandidate(good, installed, good.versionName);
        PackageInfo otherPackage = cloneInfo(good); otherPackage.packageName = "probe.different.package";
        rejected(() -> UpdateRepository.validateCandidate(otherPackage, installed, "99.0.0"), "different package");
        PackageInfo notNew = cloneInfo(good); notNew.setLongVersionCode(installed.getLongVersionCode());
        rejected(() -> UpdateRepository.validateCandidate(notNew, installed, "99.0.0"), "non-increasing version code");
        PackageInfo unsigned = cloneInfo(good); unsigned.signingInfo = null;
        rejected(() -> UpdateRepository.validateCandidate(unsigned, installed, "99.0.0"), "missing signing metadata");
        PackageInfo tooNew = cloneInfo(good); tooNew.applicationInfo.minSdkVersion = Build.VERSION.SDK_INT + 1;
        rejected(() -> UpdateRepository.validateCandidate(tooNew, installed, "99.0.0"), "unsupported minimum SDK");
        rejected(() -> UpdateRepository.validateCandidate(good, installed, "98.0.0"), "mismatched release version");
        PackageInfo wrongSigner = cloneInfo(good);
        wrongSigner.signingInfo = getTargetContext().getPackageManager().getPackageInfo("android", PackageManager.GET_SIGNING_CERTIFICATES).signingInfo;
        rejected(() -> UpdateRepository.validateCandidate(wrongSigner, installed, "99.0.0"), "different signing identity");
        passed("Updater accepts a compatible newer candidate and rejects package/version/signature/SDK mismatches");
    }
    private void canvasGestures() {
        main(() -> {
            GridModel model = new GridModel(); HomeCanvas canvas = new HomeCanvas(getTargetContext(), model, new LayoutStore(getTargetContext()));
            FrameLayout parent = new FrameLayout(getTargetContext()); parent.addView(canvas, new FrameLayout.LayoutParams(400, 600));
            AtomicInteger opened = new AtomicInteger(), clicked = new AtomicInteger(); canvas.onSwipeUp = opened::incrementAndGet;
            TextView icon = new TextView(getTargetContext()); icon.setOnClickListener(v -> clicked.incrementAndGet());
            canvas.attach(icon, app("probe.icon", 0, 0)); layout(parent); canvas.setPosition(0);
            touch(canvas, 50, 80, 50, 20); check(opened.get() == 1 && clicked.get() == 0, "Upward icon gesture opens drawer once without launching app");
            touch(canvas, 350, 500, 350, 300); check(opened.get() == 2, "Upward empty-wallpaper gesture opens drawer");
            canvas.removeAllViews(); ScrollView widget = new ScrollView(getTargetContext()); TextView content = new TextView(getTargetContext()); content.setMinimumHeight(1800); widget.addView(content);
            canvas.attach(widget, new GridModel.Item(123456, "probe/widget", 0, 0, 4, 6)); layout(parent); canvas.setPosition(0);
            touch(canvas, 200, 500, 200, 200);
            check(opened.get() == 2 && widget.getScrollY() > 0, "Upward widget content gesture scrolls vertically instead of opening drawer");
        });
        passed("Upward gestures over icons/empty Home and preserved vertical widget gestures");
    }
    private void layout(View view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY)); view.layout(0, 0, 400, 600);
    }
    private void touch(View view, float x1, float y1, float x2, float y2) {
        long time = SystemClock.uptimeMillis();
        for (int i = 0; i <= 10; i++) {
            MotionEvent event = MotionEvent.obtain(time, time + i * 40, i == 0 ? MotionEvent.ACTION_DOWN : i == 10 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE,
                    x1 + (x2 - x1) * i / 10, y1 + (y2 - y1) * i / 10, 0); view.dispatchTouchEvent(event); event.recycle();
        }
    }
    @SuppressWarnings("unchecked") private <T> T field(Object owner, String name) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return (T) field.get(owner); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void drawer(HomeActivity home) {
        main(()->home.canvas.setPosition(0));
        if(Glass.blurAvailable(home))awaitUi(()->home.surfaceBlurs.visibleCount()==2,5000,"Native dock and notification blur regions did not appear");else main(()->check(home.surfaceBlurs.visibleCount()==0,"Blur-disabled mode leaves no regional blur windows visible"));
        saveDesignShot("modern-home");
        main(()->{check(home.bottom.getChildAt(1).getHeight()==home.canvas.cellHeight(),"Dock shares Home row height");check(home.canvas.cellHeight()*6<=home.canvas.getHeight(),"Six Home rows fit viewport");});
        int[] dockCenters=new int[4];
        main(()->{LinearLayout dock=(LinearLayout)home.bottom.getChildAt(1);for(int i=0;i<4;i++){View tile=dock.getChildAt(i);int[] point=new int[2];tile.getLocationOnScreen(point);dockCenters[i]=point[0]+tile.getWidth()/2;}});
        main(() -> home.openDrawer(true));
        awaitUi(() -> home.drawer.isOpen() && home.drawer.search.hasFocus(), 5000, "Drawer search did not receive focus");
        awaitUi(() -> home.drawer.getRootWindowInsets() != null && home.drawer.getRootWindowInsets().isVisible(WindowInsets.Type.ime()), 6000, "Soft keyboard did not open");
        AppCatalog.Entry target = home.catalog.all().get(0);
        awaitUi(()->(Float)field(home,"drawerReveal")==1f,5000,"Dock expansion animation did not finish");
        main(()->{LinearLayout strip=field(home.drawer,"results");check(strip.getOrientation()==LinearLayout.HORIZONTAL&&strip.getChildCount()==4,"Empty search preserves a horizontal four-app dock");
            int top=-1;for(int i=0;i<4;i++){View tile=strip.getChildAt(i);int[] point=new int[2];tile.getLocationOnScreen(point);check(Math.abs(point[0]+tile.getWidth()/2-dockCenters[i])<=2,"Search app retains its dock column");if(top<0)top=point[1];check(point[1]==top,"All default results share a horizontal row");check(tile instanceof AppTile&&((AppTile)tile).title.getAlpha()==1,"Search result names are revealed");}
            check(((home.getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)!=0)==Glass.blurAvailable(home),"Backdrop blur follows Android support/state");});
        main(()->{check(home.canvas.getVisibility()==View.INVISIBLE,"Only wallpaper sits behind search blur");check(home.drawer.search.getHeight()==home.canvas.cellHeight(),"Search occupies one shared row");});
        main(()->{NotificationPanel panel=field(home,"notificationPanel");if(panel!=null)panel.refresh();check(home.drawer.search.hasFocus(),"Background notification refresh cannot steal search focus");});
        saveDesignShot("modern-search");
        int[] searchTop=new int[1];main(()->{int[] point=new int[2];home.drawer.search.getLocationOnScreen(point);searchTop[0]=point[1];check(point[1]<home.stage.getHeight()/3,"Search is at the top, not attached to the IME");});
        main(() -> { home.drawer.search.setText(target.label); check(home.drawer.isOpen(), "Typing keeps drawer open");
            LinearLayout results = field(home.drawer, "results"); check(findText(results, target.label, false) != null, "Search shows matching installed app");
            home.drawer.search.setText(""); home.drawer.search.onEditorAction(EditorInfo.IME_ACTION_GO); check(home.drawer.isOpen(), "Enter with empty query does nothing");
            home.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(home.drawer.search.getWindowToken(), 0); home.drawer.search.clearFocus(); });
        awaitUi(() -> ((LinearLayout) field(home.drawer, "categories")).getVisibility() == View.VISIBLE, 5000, "Keyboard dismissal did not reveal categories");
        saveDesignShot("modern-categories");
        main(() -> {
            check((Boolean)field(home.drawer,"browsing"),"Gesture entry reveals drawer after keyboard dismissal");
            int[] point=new int[2];home.drawer.search.getLocationOnScreen(point);check(Math.abs(point[1]-searchTop[0])<=2,"Keyboard dismissal does not move the top search bar");
            LinearLayout categories = field(home.drawer, "categories"); check(categories.getChildCount() > 0, "Categorized app grid populated");
            LinearLayout pair=(LinearLayout)categories.getChildAt(0);check(pair.getChildAt(0).getHeight()==2*home.canvas.cellHeight(),"Category occupies two shared grid rows");
            TextView more = findText(categories, "+", true);
            if (more != null) { more.performClick(); check(!((String) field(home.drawer, "expanded")).isEmpty(), "Category expands inline"); }
            home.closeDrawer(); check(!home.drawer.isOpen() && home.drawer.getVisibility() == View.GONE, "Drawer closes");
            check((home.getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)==0&&home.bottom.getAlpha()==1,"Closing restores clear Home and dock");
            check(home.drawer.search.getText().toString().isEmpty() && ((String) field(home.drawer, "expanded")).isEmpty(), "Closing resets query and category expansion");
        });
        main(()->home.openDrawer(true,true));
        awaitUi(()->home.drawer.search.hasFocus()&&home.drawer.getRootWindowInsets().isVisible(WindowInsets.Type.ime()),6000,"Search-button entry did not show keyboard");
        main(()->{home.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(home.drawer.search.getWindowToken(),0);home.drawer.search.clearFocus();});
        awaitUi(()->!home.drawer.getRootWindowInsets().isVisible(WindowInsets.Type.ime()),6000,"Search keyboard did not dismiss");
        main(()->check(!(Boolean)field(home.drawer,"browsing")&&home.drawer.isOpen(),"Search-button entry stays in search when keyboard is dismissed"));
        getTargetContext().startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setClass(getTargetContext(),HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        awaitUi(()->!home.drawer.isOpen()&&!home.canvas.editing,5000,"Home intent did not leave search");
        main(()->home.openDrawer(true));
        awaitUi(()->home.drawer.search.hasFocus(),5000,"Gesture search did not open");
        getTargetContext().startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setClass(getTargetContext(),HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        awaitUi(()->!home.drawer.isOpen(),5000,"Home intent did not leave gesture search");
        main(()->{int[] origin=new int[2];home.bottom.getLocationOnScreen(origin);check(origin[1]+home.bottom.getHeight()==home.stage.getHeight(),"Dock glass extends behind the navigation bar");
            NotificationPanel panel=field(home,"notificationPanel");if(panel!=null){float[] corners=((android.graphics.drawable.GradientDrawable)panel.getBackground()).getCornerRadii();check(corners[0]==0&&corners[1]==0&&corners[6]==0&&corners[7]==0&&corners[2]>0,"Notification panel is square at the left boundary");}});
        if(Glass.blurAvailable(home)){
            for(int repeat=0;repeat<3;repeat++){
                main(()->home.openDrawer(false));
                awaitUi(()->(Float)field(home,"drawerReveal")==1f,5000,"Drawer transition did not settle");
                main(home::closeDrawer);
                awaitUi(()->blurBoundsMatch(home),5000,"Dock blur bounds did not recover after drawer transition");
            }
            main(()->home.canvas.setPosition(home.canvas.cellWidth()));
            awaitUi(()->blurBoundsMatch(home),5000,"Dock blur bounds changed during Home scroll");
            main(()->home.canvas.setPosition(0));
        }
        passed("Shared Home/dock/search/category grid; wallpaper-only search; stable dock blur bounds across drawer transitions and Home scrolling; focus/IME and Home reset");
    }
    private boolean blurBoundsMatch(HomeActivity home){
        android.app.Dialog[] regions=field(home.surfaceBlurs,"windows");
        if(regions[0]==null||regions[0].getWindow().getDecorView().getVisibility()!=View.VISIBLE)return false;
        android.graphics.Rect dock=new android.graphics.Rect(),blur=new android.graphics.Rect();
        home.bottom.getGlobalVisibleRect(dock);regions[0].getWindow().getDecorView().getGlobalVisibleRect(blur);
        int[] location=new int[2];regions[0].getWindow().getDecorView().getLocationOnScreen(location);
        blur.offsetTo(location[0],location[1]);return dock.equals(blur);
    }
    private void transientMenus(HomeActivity home){
        main(()->{LinearLayout dock=(LinearLayout)home.bottom.getChildAt(1);check(dock.getChildAt(0).performLongClick(),"Dock opens context menu");});
        awaitUi(()->{android.widget.PopupWindow popup=field(home,"contextPopup");return popup!=null&&popup.isShowing();},5000,"Context popup did not show");
        getTargetContext().startActivity(new Intent(getTargetContext(),SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        awaitUi(()->(Boolean)field(home,"wasStopped"),5000,"Home did not stop behind Settings");
        main(()->{android.widget.PopupWindow popup=field(home,"contextPopup");check(!popup.isShowing()&&!(Boolean)field(home,"contextGesture")&&!home.canvas.manipulating,"Leaving Home clears popup and gesture state");check(home.surfaceBlurs.visibleCount()==0,"Home blur regions hide behind other activities");});
        getTargetContext().startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setClass(getTargetContext(),HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        awaitUi(()->home.hasWindowFocus()&&!(Boolean)field(home,"wasStopped"),5000,"Home did not resume");
        passed("Context menus and regional blur windows are cleared on leaving Home");
    }
    private void demoExamples(HomeActivity home){
        List<AppCatalog.Entry> examples=home.catalog.all().stream().filter(e->e.packageName.startsWith("dev.glgra.launcher.demo.")).toList();
        if(examples.size()!=3)return;
        diagnostic("Native category metadata: "+home.catalog.all().stream().filter(e->e.nativeCategory>=0).count()+" / "+home.catalog.all().size()+" installed apps");
        for(AppCatalog.Entry entry:examples){check(entry.nativeCategory>=0&&!home.catalog.category(entry).equals("Uncategorized"),"Demo category is read from Android metadata");}
        await(()->examples.stream().allMatch(e->NotificationRepository.counts().getOrDefault(e.packageName,0)>=7),10000,"Demo apps did not each post seven active notifications");
        List<StatusBarNotification> records=NotificationRepository.snapshot().stream().filter(n->n.getPackageName().startsWith("dev.glgra.launcher.demo.")).toList();
        check(records.stream().anyMatch(n->(n.getNotification().flags&Notification.FLAG_ONGOING_EVENT)!=0),"Ongoing fixture exists");
        check(records.stream().anyMatch(n->Notification.CallStyle.class.getName().equals(n.getNotification().extras.getString(Notification.EXTRA_TEMPLATE))&&(n.getNotification().flags&Notification.FLAG_ONGOING_EVENT)!=0),"Native ongoing CallStyle non-dismissible example exists");
        check(records.stream().anyMatch(n->n.getNotification().extras.getCharSequence(Notification.EXTRA_BIG_TEXT,"").length()>1000),"Long fixture exists");
        check(records.stream().anyMatch(n->n.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE,"").length()==0&&n.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT,"").length()==0),"Content-free privacy fixture exists");
        main(()->{home.closeDrawer();home.setEditing(false);home.canvas.setPosition(0);home.canvas.persistPosition();home.render();});
        awaitUi(()->{NotificationPanel panel=field(home,"notificationPanel");return panel!=null&&findText(panel,"Demo · Messages",true)!=null;},5000,"Demo groups are missing from the panel");
        saveDesignShot("notification-examples-collapsed");
        main(()->{NotificationPanel panel=field(home,"notificationPanel");TextView title=findText(panel,"Demo · Messages",true);((View)title.getParent()).performClick();check(findText(panel,"Content hidden",false)!=null,"Privacy preview remains generic");TextView longText=findText(panel,"This deliberately long example",true);check(longText!=null,"Long notification preview available");longText.performClick();});
        saveDesignShot("notification-examples-expanded");
        main(()->{NotificationPanel panel=field(home,"notificationPanel");panel.reset();});
        passed("Three real demo app groups, native categories, long/hidden/ongoing/image/progress examples ready for exploration");
    }
    private TextView findText(View view, String text, boolean prefix) {
        if (view instanceof TextView) { String value = ((TextView) view).getText().toString(); if (prefix ? value.startsWith(text) : value.equals(text)) return (TextView) view; }
        if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup) view; for (int i = 0; i < group.getChildCount(); i++) { TextView found = findText(group.getChildAt(i), text, prefix); if (found != null) return found; } }
        return null;
    }
    private boolean nativePointerDown;
    private long nativeDownTime;
    private float nativeX, nativeY;
    private void nativeDrags(HomeActivity home) {
        java.util.Map<String, ?> saved = new java.util.HashMap<>(home.store.prefs.getAll());
        AppCatalog.Entry first = home.catalog.all().get(0);
        AppCatalog.Entry second = home.catalog.all().stream().filter(entry -> !entry.packageName.equals(first.packageName)).findFirst().orElseThrow();
        java.util.concurrent.atomic.AtomicReference<View> source = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            main(() -> {
                dismissPopup(home, "contextPopup"); dismissPopup(home, "folderPopup"); home.closeDrawer(); home.setEditing(false);
                home.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(home.drawer.search.getWindowToken(), 0);
                home.store.prefs.edit().putBoolean("notificationsEnabled", true).putInt("notificationWidth", 3)
                        .putBoolean("returnNotifications", false).putInt("haptics", 0).putStringSet("hidden", Set.of())
                        .putString("dockPins", new org.json.JSONArray(List.of(first.key, second.key)).toString()).commit();
                // Keep provider bindings intact; restore the saved layout after these two temporary drops.
                home.model.items.clear(); home.model.reserveNotifications(true, 3); home.store.save(home.model);
                home.canvas.setPosition(0); home.canvas.persistPosition(); home.render(); invoke(home, "renderDock");
            });
            awaitUi(() -> home.hasWindowFocus() && !((Boolean) field(home, "loading")) && home.bottom.getVisibility() == View.VISIBLE
                    && home.canvas.getWidth() > 0 && home.canvas.getHeight() > 0, 10000, "Home not ready for native dock drag");
            SystemClock.sleep(300); waitForIdleSync();
            main(() -> { source.set(findDescription(home.bottom, first.label)); check(source.get() != null, "Dock source icon found by app description"); });
            nativeDrag(home, source.get(), first, 3);
            main(() -> home.setEditing(false));
            awaitUi(() -> home.hasWindowFocus() && !home.canvas.editing, 5000, "Home did not exit editing after dock drop");
            main(() -> { home.openDrawer(true); home.drawer.search.setText(second.label); });
            awaitUi(() -> home.drawer.isOpen() && home.drawer.search.hasFocus() && home.drawer.getRootWindowInsets() != null
                    && home.drawer.getRootWindowInsets().isVisible(WindowInsets.Type.ime()), 6000, "Search drawer and keyboard not ready for native drag");
            SystemClock.sleep(300); waitForIdleSync();
            main(() -> {
                LinearLayout results = field(home.drawer, "results"); TextView name = findText(results, second.label, false);
                check(results.getVisibility() == View.VISIBLE && name != null, "Nonempty search displays its drag source result");
                source.set((View) name.getParent());
            });
            nativeDrag(home, source.get(), second, 4);
            main(() -> {
                check(home.model.find(first.packageName) != null && home.model.find(second.packageName) != null, "Dock and search drops both remain on Home");
                noOverlap(home.model); home.setEditing(false);
            });
            passed("Native long-press drags from dock and nonempty search place unique apps in free Home cells");
        } finally {
            try { if (nativePointerDown) nativeInput(MotionEvent.ACTION_CANCEL, nativeDownTime, nativeX, nativeY); }
            finally {
                main(() -> {
                    dismissPopup(home, "contextPopup"); dismissPopup(home, "folderPopup"); home.closeDrawer(); home.setEditing(false);
                    SharedPreferences.Editor restore = home.store.prefs.edit().clear();
                    for (java.util.Map.Entry<String, ?> entry : saved.entrySet()) {
                        Object value = entry.getValue(); String key = entry.getKey();
                        if (value instanceof String) restore.putString(key, (String) value);
                        else if (value instanceof Integer) restore.putInt(key, (Integer) value);
                        else if (value instanceof Long) restore.putLong(key, (Long) value);
                        else if (value instanceof Float) restore.putFloat(key, (Float) value);
                        else if (value instanceof Boolean) restore.putBoolean(key, (Boolean) value);
                        else if (value instanceof Set) {
                            Set<String> strings = new HashSet<>(); for (Object item : (Set<?>) value) strings.add((String) item); restore.putStringSet(key, strings);
                        }
                    }
                    restore.commit(); GridModel original = home.store.load(); home.model.items.clear(); home.model.items.addAll(original.items);
                    home.canvas.setPosition(home.store.prefs.getFloat("scrollColumns", 0)); home.render(); invoke(home, "renderDock");
                });
                getTargetContext().startActivity(new Intent(getTargetContext(), HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                waitForIdleSync();
            }
        }
    }
    private void nativeDrag(HomeActivity home, View source, AppCatalog.Entry entry, int targetRow) {
        float[] point = new float[2];
        main(() -> {
            android.graphics.Rect bounds = new android.graphics.Rect(); check(source.getGlobalVisibleRect(bounds), "Native drag source must be visible");
            point[0] = bounds.exactCenterX(); point[1] = bounds.exactCenterY();
        });
        traceNativeDrag(home, source, entry, "before DOWN", point[0], point[1]);
        long down = SystemClock.uptimeMillis(); nativeInput(MotionEvent.ACTION_DOWN, down, point[0], point[1]);
        main(()->{if(home.drawer.isOpen())home.drawer.refresh();else invoke(home,"renderDock");check(source.isAttachedToWindow(),"Metadata refresh must preserve the tile under the held finger");});
        // A real elapsed hold triggers Android's long press; forged event timestamps do not.
        SystemClock.sleep(80);
        traceNativeDrag(home, source, entry, "80ms after DOWN", point[0], point[1]);
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 170L);
        traceNativeDrag(home, source, entry, "after elapsed long hold", point[0], point[1]);
        try {
            awaitUi(() -> { android.widget.PopupWindow popup = field(home, "contextPopup"); return popup != null && popup.isShowing(); }, 3000, "Real long press did not open app context menu");
        } catch (AssertionError failure) {
            traceNativeDrag(home, source, entry, "context timeout", point[0], point[1]); captureNativeFailure(entry);
            throw failure;
        }
        float triggerY = point[1] - Glass.dp(home, 24);
        nativeInput(MotionEvent.ACTION_MOVE, down, point[0], triggerY);
        awaitUi(() -> home.canvas.editing && !home.drawer.isOpen(), 5000, "Moving held context source did not start native Home drag");
        awaitUi(() -> home.canvas.getRootWindowInsets() != null && !home.canvas.getRootWindowInsets().isVisible(WindowInsets.Type.ime()), 5000, "Keyboard did not close for Home drop");
        SystemClock.sleep(200); waitForIdleSync();
        float[] destination = new float[2];
        main(() -> {
            check(home.model.fits(null, 3, targetRow, 1, 1), "Native drag destination must be an unoccupied cell");
            int[] origin = new int[2]; home.canvas.getLocationOnScreen(origin);
            destination[0] = origin[0] + 3.5f * home.canvas.cellWidth() - home.canvas.getScrollX();
            destination[1] = origin[1] + (targetRow + .5f) * home.canvas.cellHeight();
        });
        for (int i = 1; i <= 24; i++) {
            SystemClock.sleep(20);
            nativeInput(MotionEvent.ACTION_MOVE, down, point[0] + (destination[0] - point[0]) * i / 24,
                    triggerY + (destination[1] - triggerY) * i / 24);
        }
        SystemClock.sleep(150); nativeInput(MotionEvent.ACTION_UP, down, destination[0], destination[1]);
        awaitUi(() -> home.model.find(entry.packageName) != null, 5000, "Native drop did not add app " + entry.label);
        main(() -> {
            GridModel.Item item = home.model.find(entry.packageName);
            check(home.canvas.editing && item.column == 3 && item.row == targetRow, "Native drop snaps to target and stays in editing");
            long copies = 0;
            for (GridModel.Item root : home.model.items) {
                if (root.identity().equals(entry.packageName)) copies++;
                for (GridModel.Item child : root.children) if (child.identity().equals(entry.packageName)) copies++;
            }
            check(copies == 1, "Native drag creates exactly one Home occurrence"); noOverlap(home.model);
        });
    }
    private void nativeInput(int action, long down, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0); event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        if (action == MotionEvent.ACTION_DOWN) { nativePointerDown = true; nativeDownTime = down; }
        nativeX = x; nativeY = y;
        try { check(getUiAutomation().injectInputEvent(event, true), "System rejected native touch injection"); }
        finally { event.recycle(); if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) nativePointerDown = false; }
    }
    private void diagnostic(String message) {
        report.append("DIAG ").append(message).append('\n');
        Bundle update = new Bundle(); update.putString("stream", "DIAG " + message + "\n"); sendStatus(0, update);
    }
    private void saveDesignShot(String name) {
        waitForIdleSync();SystemClock.sleep(120);android.graphics.Bitmap screenshot=getUiAutomation().takeScreenshot();
        check(screenshot!=null,"Design screenshot available");
        try{java.io.File directory=getTargetContext().getExternalFilesDir("design");check(directory!=null&&(directory.isDirectory()||directory.mkdirs()),"Design evidence directory");
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(directory,name+".png"))){check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out),"Design screenshot saved");}
        }catch(IOException failure){throw new AssertionError(failure);}finally{screenshot.recycle();}
    }
    private void traceNativeDrag(HomeActivity home, View source, AppCatalog.Entry entry, String phase, float x, float y) {
        main(() -> {
            android.graphics.Rect bounds = new android.graphics.Rect(); boolean visible = source.getGlobalVisibleRect(bounds);
            int[] screen = new int[2]; source.getLocationOnScreen(screen);
            LinearLayout results = field(home.drawer, "results"); TextView currentName = findText(results, entry.label, false);
            View currentRow = currentName != null && currentName.getParent() instanceof View ? (View) currentName.getParent() : null;
            android.widget.PopupWindow popup = field(home, "contextPopup"); View focus = home.getCurrentFocus();
            WindowInsets insets = home.drawer.getRootWindowInsets();
            diagnostic(entry.packageName + " " + phase + " pointer=" + x + "," + y
                    + " held=" + (nativePointerDown ? SystemClock.uptimeMillis() - nativeDownTime : 0) + "ms"
                    + " source=" + source.getClass().getSimpleName() + "@" + System.identityHashCode(source)
                    + " attached=" + source.isAttachedToWindow() + " shown=" + source.isShown() + " visible=" + visible + " bounds=" + bounds
                    + " screen=" + screen[0] + "," + screen[1] + " size=" + source.getWidth() + "x" + source.getHeight()
                    + " longClickable=" + source.isLongClickable() + " clickable=" + source.isClickable() + " enabled=" + source.isEnabled()
                    + " sourceWindowFocus=" + source.hasWindowFocus() + " homeWindowFocus=" + home.hasWindowFocus()
                    + " sourceFocus=" + source.hasFocus() + " focus=" + (focus == null ? "null" : focus.getClass().getSimpleName() + "@" + System.identityHashCode(focus))
                    + " parent=" + (source.getParent() == null ? "null" : source.getParent().getClass().getSimpleName())
                    + " currentSearchRow=" + (currentRow == null ? "null" : currentRow.getClass().getSimpleName() + "@" + System.identityHashCode(currentRow))
                    + " sameSearchRow=" + (source == currentRow) + " resultIndex=" + results.indexOfChild(source)
                    + " resultsVisibility=" + results.getVisibility() + " resultsCount=" + results.getChildCount()
                    + " drawer=" + home.drawer.isOpen() + " drawerY=" + home.drawer.getTranslationY()
                    + " searchFocus=" + home.drawer.search.hasFocus() + " query=" + home.drawer.search.getText()
                    + " ime=" + (insets != null && insets.isVisible(WindowInsets.Type.ime()))
                    + " imeBottom=" + (insets == null ? -1 : insets.getInsets(WindowInsets.Type.ime()).bottom)
                    + " keyboardState=" + field(home.drawer, "keyboard") + " contextActive=" + field(home.drawer, "contextActive")
                    + " contextGesture=" + field(home, "contextGesture") + " popup=" + (popup != null && popup.isShowing())
                    + " editing=" + home.canvas.editing + " manipulating=" + home.canvas.manipulating + " moveMode=" + home.canvas.moveMode);
        });
    }
    private void captureNativeFailure(AppCatalog.Entry entry) {
        android.graphics.Bitmap image = getUiAutomation().takeScreenshot();
        if (image == null) { diagnostic("Native drag failure screenshot unavailable"); return; }
        try {
            java.io.File directory = getTargetContext().getExternalFilesDir("integration");
            if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) throw new IOException("Screenshot directory unavailable");
            java.io.File output = new java.io.File(directory, "native-drag-" + entry.packageName.replaceAll("[^a-zA-Z0-9._-]", "_") + "-failure.png");
            try (java.io.FileOutputStream stream = new java.io.FileOutputStream(output)) {
                if (!image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("Screenshot encoding failed");
            }
            diagnostic("Native drag failure screenshot: " + output.getAbsolutePath());
        } catch (IOException error) { diagnostic("Native drag screenshot failed: " + error); }
        finally { image.recycle(); }
    }
    private void dismissPopup(HomeActivity home, String name) { android.widget.PopupWindow popup = field(home, name); if (popup != null) popup.dismiss(); }
    private void invoke(Object target, String name) {
        try { java.lang.reflect.Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(target); }
        catch (ReflectiveOperationException problem) { throw new AssertionError(problem); }
    }
    private View findDescription(View view, String text) {
        if (view.getContentDescription() != null && view.getContentDescription().toString().contains(text)) return view;
        if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup) view; for (int i = 0; i < group.getChildCount(); i++) { View found = findDescription(group.getChildAt(i), text); if (found != null) return found; } }
        return null;
    }
    private void probe(String action) { getTargetContext().sendBroadcast(new Intent(getTargetContext(), NotificationProbe.class).setAction(action)); }
    private void notifications(HomeActivity home) {
        check(NotificationRepository.accessGranted(getTargetContext()), "Grant listener access before the integrated test");
        check(getTargetContext().getSystemService(NotificationManager.class).areNotificationsEnabled(), "Grant debug POST_NOTIFICATIONS before the integrated test");
        await(NotificationRepository::connected, 15000, "Notification listener did not connect");
        SharedPreferences state = getTargetContext().getSharedPreferences("notification-probe", Context.MODE_PRIVATE);
        state.edit().putInt("probeActions", 0).commit(); probe(NotificationProbe.ACTION_CLEAR);
        await(() -> NotificationRepository.snapshot().stream().noneMatch(n -> NotificationProbe.TAG.equals(n.getTag())), 5000, "Old fixture notifications did not clear");
        final NotificationPanel[] panel = new NotificationPanel[1];
        try {
            probe(NotificationProbe.ACTION_POST);
            await(() -> NotificationRepository.counts().getOrDefault(getTargetContext().getPackageName(), 0) == 2, 10000, "Grouped notifications did not produce two active-record counts");
            List<StatusBarNotification> snapshot = NotificationRepository.snapshot();
            long records = snapshot.stream().filter(n -> NotificationProbe.TAG.equals(n.getTag())).count();
            check(records == 2 && snapshot.stream().filter(n -> NotificationProbe.TAG.equals(n.getTag())).noneMatch(n -> (n.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0), "Group summary excluded when children exist");
            main(() -> {
                panel[0] = new NotificationPanel(home);
                String label = home.getPackageManager().getApplicationLabel(home.getApplicationInfo()).toString();
                View header = findDescription(panel[0], label + ", 2 active notifications"); check(header != null, "Notification group visible"); header.performClick();
                TextView body = findText(panel[0], NotificationProbe.BODY_ONE, false); check(body != null && body.isClickable(), "Read-only message preview visible"); body.performClick();
                TextView expanded = findText(panel[0], NotificationProbe.BODY_ONE, false); check(expanded.getMaxLines() == Integer.MAX_VALUE, "Message preview expands locally"); expanded.performClick();
                check(findText(panel[0], NotificationProbe.BODY_ONE, false).getMaxLines() == 3, "Message preview collapses locally"); panel[0].reset();
            });
            SystemClock.sleep(300);
            check(state.getInt("probeActions", 0) == 0, "Local preview taps never invoke content/action PendingIntents");
            check(NotificationRepository.counts().getOrDefault(getTargetContext().getPackageName(), 0) == 2, "Preview taps never dismiss notifications");
            long nativeRecords = java.util.Arrays.stream(getTargetContext().getSystemService(NotificationManager.class).getActiveNotifications()).filter(n -> NotificationProbe.TAG.equals(n.getTag())).count();
            check(nativeRecords == 3, "Summary plus both children remain active in Android");
            passed("Real notification events, summary deduplication, counts and read-only expansion without actions or dismissal");
        } finally {
            if (panel[0] != null) main(() -> panel[0].dispose()); probe(NotificationProbe.ACTION_CLEAR);
            await(() -> java.util.Arrays.stream(getTargetContext().getSystemService(NotificationManager.class).getActiveNotifications())
                    .noneMatch(n -> NotificationProbe.TAG.equals(n.getTag())), 5000, "Fixture-owned notifications did not clear");
        }
    }
}
