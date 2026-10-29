package dev.glgra.launcher;

import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Rect;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

public final class HomeActivity extends Activity {
    static final int HOST_ID=1042,BIND=10,CONFIGURE=11;
    LayoutStore store; GridModel model; HomeCanvas canvas; AppWidgetHost host; AppWidgetManager widgets;
    LinearLayout root,bottom; FrameLayout stage; AppDrawer drawer; AppCatalog catalog; UsageRepository usage;
    private int pendingId=-1;
    private final java.util.concurrent.ExecutorService loader=java.util.concurrent.Executors.newSingleThreadExecutor();
    private NotificationPanel notificationPanel;
    private ScrollView editingScroll;
    private SettingsPanel editingSettings;
    private LinearLayout editingControls;
    private PopupWindow contextPopup,folderPopup;
    private boolean contextGesture,loading;
    private boolean wasStopped,foreground;
    SurfaceBlur surfaceBlurs;
    private GridModel.Item resizeSelection;
    private long lastMetadataLoad;
    private Motion.Spring drawerMotion;
    private float drawerReveal;
    private boolean touchingDock,dockRefreshPending;
    private final java.util.function.Consumer<Boolean> blurChanged=enabled->{if(drawer!=null){drawer.updateGlass();Glass.backdrop(getWindow(),canvas,drawer.isOpen());Glass.applyDock(bottom);if(notificationPanel!=null)Glass.applyNotifications(notificationPanel);if(surfaceBlurs!=null)surfaceBlurs.sync();}};
    private final Runnable notificationChanged=()->{renderDock();updateFolderBadges();if(drawer!=null&&drawer.isOpen())drawer.refresh();};
    private final LauncherApps.Callback packages=new LauncherApps.Callback(){
        public void onPackageRemoved(String p,UserHandle u){refreshApps(true);} public void onPackageAdded(String p,UserHandle u){refreshApps(true);}
        public void onPackageChanged(String p,UserHandle u){refreshApps(true);}public void onPackagesAvailable(String[] p,UserHandle u,boolean r){refreshApps(true);}
        public void onPackagesUnavailable(String[] p,UserHandle u,boolean r){refreshApps(true);}
    };
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().setDecorFitsSystemWindows(false);getWindow().setStatusBarContrastEnforced(true);getWindow().setNavigationBarContrastEnforced(false);getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        store=new LayoutStore(this);model=store.load();catalog=new AppCatalog(this);usage=new UsageRepository(this);
        model.reserveNotifications(store.prefs.getBoolean("notificationsEnabled",true),store.prefs.getInt("notificationWidth",3));store.save(model);
        widgets=AppWidgetManager.getInstance(this);host=new AppWidgetHost(this,HOST_ID);pendingId=store.prefs.getInt("pendingWidget",-1);
        if(model.widget(pendingId)!=null){pendingId=-1;store.prefs.edit().remove("pendingWidget").apply();}
        Set<Integer> ids=new HashSet<>();for(GridModel.Item i:model.items)if(i.isWidget())ids.add(i.widgetId);
        for(int id:host.getAppWidgetIds())if(!ids.contains(id)&&id!=pendingId)host.deleteAppWidgetId(id);
        stage=new FrameLayout(this);root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());root.setPadding(bars.left,canvas!=null&&canvas.editing?0:bars.top,bars.right,canvas!=null&&canvas.editing?bars.bottom:0);bottom.setPadding(0,0,0,bars.bottom);return insets;});
        canvas=new HomeCanvas(this,model,store);canvas.onEdit=this::enterEditing;canvas.onSwipeUp=()->openDrawer(true);
        root.addView(canvas,new LinearLayout.LayoutParams(-1,0,1));
        bottom=new DockSurface();bottom.setOrientation(LinearLayout.VERTICAL);Glass.applyDock(bottom);LinearLayout.LayoutParams dockParams=new LinearLayout.LayoutParams(-1,-2);dockParams.setMargins(0,0,0,0);root.addView(bottom,dockParams);
        stage.addView(root,new FrameLayout.LayoutParams(-1,-1));
        drawer=new AppDrawer(this,catalog,usage,new AppDrawer.Actions(){
            public void launch(AppCatalog.Entry entry){closeDrawer();launchEntry(entry);}
            public void context(View source,AppCatalog.Entry entry){appMenu(source,entry,null);}
            public void bindDrag(View source,AppCatalog.Entry entry){enableDragTouch(source,new DragPayload(null,entry));}
            public void close(){animateDrawer(false);}
        });
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->updateGrid());
        stage.addView(drawer,new FrameLayout.LayoutParams(-1,-1));setContentView(stage);getWindow().setNavigationBarContrastEnforced(false);surfaceBlurs=new SurfaceBlur(this,stage,bottom,()->notificationPanel);
        getWindow().getInsetsController().setSystemBarsAppearance(Glass.dark(this)?0:WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,()->{
            if(folderPopup!=null&&folderPopup.isShowing())folderPopup.dismiss();else if(contextPopup!=null&&contextPopup.isShowing())contextPopup.dismiss();
            else if(drawer.isOpen())animateDrawer(false);else if(canvas.editing)setEditing(false);
        });
        getSystemService(LauncherApps.class).registerCallback(packages,new Handler(Looper.getMainLooper()));
        NotificationRepository.addListener(notificationChanged);render();renderDock();refreshApps(true);UpdateRepository.schedule(this);
        handlePin(getIntent());
        if(store.prefs.getBoolean("returnNotifications",false)&&model.notification()!=null)canvas.post(()->{canvas.setPosition(0);canvas.persistPosition();});
    }
    @Override protected void onStart(){super.onStart();foreground=true;host.startListening();surfaceBlurs.active(true);getWindowManager().addCrossWindowBlurEnabledListener(blurChanged);}
    @Override protected void onResume(){super.onResume();if(store!=null){
        model.reserveNotifications(store.prefs.getBoolean("notificationsEnabled",true),store.prefs.getInt("notificationWidth",3));store.save(model);render();refreshApps(false);
        if(wasStopped&&store.prefs.getBoolean("returnNotifications",false)&&model.notification()!=null)canvas.post(()->{canvas.setPosition(0);canvas.persistPosition();});wasStopped=false;
    }}
    @Override protected void onStop(){foreground=false;wasStopped=true;surfaceBlurs.active(false);getWindowManager().removeCrossWindowBlurEnabledListener(blurChanged);canvas.persistPosition();host.stopListening();if(notificationPanel!=null)notificationPanel.reset();if(folderPopup!=null)folderPopup.dismiss();if(contextPopup!=null)contextPopup.dismiss();contextGesture=false;if(!canvas.moveMode)canvas.manipulating=false;if(drawer.isOpen())closeDrawer();super.onStop();}
    @Override protected void onDestroy(){if(contextPopup!=null)contextPopup.dismiss();if(folderPopup!=null)folderPopup.dismiss();surfaceBlurs.close();NotificationRepository.removeListener(notificationChanged);getSystemService(LauncherApps.class).unregisterCallback(packages);if(notificationPanel!=null)notificationPanel.dispose();if(editingSettings!=null)editingSettings.close();loader.shutdownNow();super.onDestroy();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(Intent.ACTION_MAIN.equals(intent.getAction())&&intent.hasCategory(Intent.CATEGORY_HOME)){if(folderPopup!=null)folderPopup.dismiss();if(contextPopup!=null)contextPopup.dismiss();if(drawer.isOpen())closeDrawer();if(canvas.editing)setEditing(false);}else handlePin(intent);
    }
    private void refreshApps(boolean metadata){
        if(loading||loader.isShutdown())return;loading=true;
        loader.execute(()->{try{if(metadata||catalog.all().isEmpty()||SystemClock.elapsedRealtime()-lastMetadataLoad>300000){catalog.reload();lastMetadataLoad=SystemClock.elapsedRealtime();}usage.refresh();}
            finally{runOnUiThread(()->{loading=false;if(!isDestroyed()){renderDock();if(drawer.isOpen())drawer.refresh();render();}});}});
    }
    void render(){
        if(notificationPanel!=null){notificationPanel.dispose();notificationPanel=null;}
        canvas.removeAllViews();
        for(GridModel.Item item:model.items){View view;
            if(item.isNotification()){notificationPanel=new NotificationPanel(this);Glass.applyNotifications(notificationPanel);view=notificationPanel;}
            else if(item.isWidget())view=widgetView(item);
            else if(item.isFolder())view=folderView(item);
            else view=shortcutView(item,false);
            if(!item.isNotification()){
                View original=view;view.setOnLongClickListener(v->{Haptics.tick(v,store.prefs.getInt("haptics",2));if(item.isApp()){AppCatalog.Entry entry=catalog.find(item.component);if(entry!=null)appMenu(v,entry,item);else itemMenu(item);}else itemMenu(item);return true;});
                enableDragTouch(original,new DragPayload(item,null));
            }
            canvas.attach(view,item);
        }
        canvas.setOnDragListener(this::homeDrag);canvas.requestLayout();
    }
    private View widgetView(GridModel.Item item){
        AppWidgetProviderInfo info=widgets.getAppWidgetInfo(item.widgetId);
        if(info==null)return text("Widget unavailable\nLong-press to remove");
        AppWidgetHostView hosted=host.createView(this,item.widgetId,info);
        hosted.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol||b-t!=ob-ot){
            Rect pad=AppWidgetHostView.getDefaultPaddingForWidget(this,info.provider,null);
            int w=Math.max(1,Math.round((r-l-pad.left-pad.right)/getResources().getDisplayMetrics().density));
            int h=Math.max(1,Math.round((b-t-pad.top-pad.bottom)/getResources().getDisplayMetrics().density));hosted.updateAppWidgetSize(null,w,h,w,h);
        }});
        if(resizeSelection!=item)return hosted;
        FrameLayout frame=new FrameLayout(this);frame.addView(hosted,new FrameLayout.LayoutParams(-1,-1));
        TextView handle=text("↘");handle.setTextSize(28);handle.setBackgroundColor(0xCC80CBC4);
        FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.BOTTOM|Gravity.RIGHT);frame.addView(handle,hp);
        handle.setOnTouchListener(new View.OnTouchListener(){float startX,startY;int startW,startH;
            public boolean onTouch(View v,android.view.MotionEvent e){
                if(e.getActionMasked()==MotionEvent.ACTION_DOWN){startX=e.getRawX();startY=e.getRawY();startW=item.width;startH=item.height;canvas.manipulating=true;return true;}
                if(e.getActionMasked()==MotionEvent.ACTION_MOVE){int w=startW+Math.round((e.getRawX()-startX)/canvas.cellWidth()),h=startH+Math.round((e.getRawY()-startY)/canvas.cellHeight());
                    w=Math.max(1,Math.min(64,w));h=Math.max(1,Math.min(6-item.row,h));
                    if((info.resizeMode&AppWidgetProviderInfo.RESIZE_HORIZONTAL)==0)w=startW;
                    if((info.resizeMode&AppWidgetProviderInfo.RESIZE_VERTICAL)==0)h=startH;
                    if(w*canvas.cellWidth()<Math.max(1,info.minResizeWidth)||h*canvas.cellHeight()<Math.max(1,info.minResizeHeight))return true;
                    if(info.maxResizeWidth>0&&w*canvas.cellWidth()>info.maxResizeWidth||info.maxResizeHeight>0&&h*canvas.cellHeight()>info.maxResizeHeight)return true;
                    if(w!=item.width||h!=item.height){model.move(item,item.column,item.row,w,h);canvas.requestLayout();Haptics.tick(v,store.prefs.getInt("haptics",2));}return true;}
                if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){canvas.manipulating=false;store.save(model);v.performClick();return true;}return true;
            }});return frame;
    }
    private View shortcutView(GridModel.Item item,boolean badge){
        LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);view.setGravity(Gravity.CENTER);view.setPadding(dp(4),dp(16),dp(4),0);
        ImageView icon=new ImageView(this);String label=item.name;
        try{ComponentName c=ComponentName.unflattenFromString(item.component);
            if(item.kind.equals("shortcut")){
                List<ShortcutInfo> list=getSystemService(LauncherApps.class).getShortcuts(new LauncherApps.ShortcutQuery().setPackage(c.getPackageName()).setShortcutIds(List.of(item.shortcutId)).setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED),android.os.Process.myUserHandle());
                if(list!=null&&!list.isEmpty())icon.setImageDrawable(getSystemService(LauncherApps.class).getShortcutIconDrawable(list.get(0),0));else icon.setImageDrawable(getPackageManager().getActivityIcon(c));
            }else{AppCatalog.Entry entry=catalog.find(item.component);icon.setImageDrawable(entry==null?getPackageManager().getActivityIcon(c):entry.icon);if(label.isBlank())label=entry==null?getPackageManager().getActivityInfo(c,0).loadLabel(getPackageManager()).toString():entry.label;}
        }catch(Exception unavailable){label=label.isBlank()?"App unavailable":label;icon.setImageResource(android.R.drawable.sym_def_app_icon);}
        FrameLayout image=new FrameLayout(this);image.addView(icon,new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.CENTER));view.addView(image,new LinearLayout.LayoutParams(dp(60),dp(52)));TextView name=text(label);Design.label(name);name.setLines(getResources().getConfiguration().fontScale>1.15f?1:2);name.setIncludeFontPadding(false);LinearLayout.LayoutParams labelParams=new LinearLayout.LayoutParams(-1,-2);labelParams.topMargin=dp(4);view.addView(name,labelParams);
        Design.press(view);view.setSoundEffectsEnabled(false);view.setOnClickListener(v->launchItem(item));return view;
    }
    private View folderView(GridModel.Item folder){
        FrameLayout card=new FrameLayout(this);Glass.area(card);
        GridLayout grid=new GridLayout(this);grid.setColumnCount(2);List<GridModel.Item> children=rankedChildren(folder);
        for(int i=0;i<Math.min(3,children.size());i++){GridModel.Item child=children.get(i);View icon=shortcutView(child,false);enableDragTouch(icon,new DragPayload(child,null));icon.setOnLongClickListener(v->{appMenu(v,catalog.find(child.component),child);return true;});folderCell(grid,icon);}
        while(grid.getChildCount()<3)folderCell(grid,new View(this));TextView more=Design.more(this,"+"+Math.max(0,children.size()-3));more.setOnClickListener(v->openFolder(folder,card));folderCell(grid,more);card.addView(grid,new FrameLayout.LayoutParams(-1,-1));
        int count=0;for(GridModel.Item child:children){ComponentName c=ComponentName.unflattenFromString(child.component);if(c!=null)count+=NotificationRepository.counts().getOrDefault(c.getPackageName(),0);}
        TextView name=text(folder.name+(count>0?"  · "+count:""));name.setTag("folder-badge:"+folder.uid);Design.caption(name);name.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);name.setPadding(dp(16),0,dp(12),0);card.setOnClickListener(v->openFolder(folder,card));card.addView(name,new FrameLayout.LayoutParams(-1,dp(20),Gravity.TOP));return card;
    }
    private void folderCell(GridLayout grid,View v){GridLayout.LayoutParams lp=new GridLayout.LayoutParams();lp.width=0;lp.height=0;lp.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);lp.rowSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);grid.addView(v,lp);}
    private List<GridModel.Item> rankedChildren(GridModel.Item folder){List<GridModel.Item> result=new ArrayList<>(folder.children);result.sort(Comparator.comparingDouble((GridModel.Item i)->{ComponentName c=ComponentName.unflattenFromString(i.component);return c==null?0:usage.score(c.getPackageName());}).reversed());return result;}
    private void openFolder(GridModel.Item folder,View origin){
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(16),dp(20),dp(16));Glass.apply(body);
        TextView name=text(folder.name);Design.type(name,22,28,500);name.setMinHeight(dp(48));name.setOnClickListener(v->rename(folder));body.addView(name);
        ScrollView scroll=new ScrollView(this);GridLayout grid=new GridLayout(this);grid.setColumnCount(4);
        for(GridModel.Item child:rankedChildren(folder)){View icon=shortcutView(child,true);icon.setOnLongClickListener(v->{appMenu(v,catalog.find(child.component),child);return true;});enableDragTouch(icon,new DragPayload(child,null));GridLayout.LayoutParams lp=new GridLayout.LayoutParams();lp.width=0;lp.height=canvas.cellHeight();lp.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);grid.addView(icon,lp);}
        scroll.addView(grid);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));Design.panel(body);int folderHeight=Math.min(Math.min(dp(440),stage.getHeight()/2),dp(80)+Math.max(1,(folder.children.size()+3)/4)*canvas.cellHeight());folderPopup=new PopupWindow(body,Math.max(dp(240),stage.getWidth()-dp(32)),folderHeight,true);folderPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));folderPopup.setOutsideTouchable(true);
        int[] loc=new int[2];origin.getLocationOnScreen(loc);loc[0]+=origin.getWidth()/2;loc[1]+=origin.getHeight()/2;
        if(android.animation.ValueAnimator.areAnimatorsEnabled()){
            body.setScaleX(.96f);body.setScaleY(.96f);body.setAlpha(0);
            body.addOnLayoutChangeListener(new View.OnLayoutChangeListener(){public void onLayoutChange(View view,int l,int t,int r,int b,int ol,int ot,int or,int ob){
                if(r<=l||b<=t)return;body.removeOnLayoutChangeListener(this);int[] position=new int[2];body.getLocationOnScreen(position);
                body.setPivotX(Math.max(0,Math.min(body.getWidth(),loc[0]-position[0])));body.setPivotY(Math.max(0,Math.min(body.getHeight(),loc[1]-position[1])));
                body.animate().scaleX(1).scaleY(1).alpha(1).setDuration(220).setInterpolator(Motion.EFFECTS).start();
            }});
        }
        folderPopup.showAtLocation(stage,Gravity.CENTER,0,0);
        Haptics.tick(origin,store.prefs.getInt("haptics",2));
    }
    private void rename(GridModel.Item folder){EditText input=new EditText(this);input.setSingleLine();input.setText(folder.name);input.selectAll();
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Folder name").setView(input).create();input.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int n){}public void onTextChanged(CharSequence s,int a,int b,int c){folder.name=s.toString();store.save(model);}public void afterTextChanged(android.text.Editable e){}});
        dialog.setOnDismissListener(d->{if(folderPopup!=null)folderPopup.dismiss();render();});dialog.getWindow();dialog.setOnShowListener(d->{input.requestFocus();dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);});dialog.show();
    }
    private void launchEntry(AppCatalog.Entry entry){try{catalog.launch(entry);}catch(RuntimeException e){toast("This app cannot be opened right now.");}}
    private void launchItem(GridModel.Item item){try{ComponentName component=ComponentName.unflattenFromString(item.component);
        if(item.kind.equals("shortcut"))getSystemService(LauncherApps.class).startShortcut(component.getPackageName(),item.shortcutId,null,null,android.os.Process.myUserHandle());
        else getSystemService(LauncherApps.class).startMainActivity(component,android.os.Process.myUserHandle(),null,null);
    }catch(RuntimeException e){toast("This app or shortcut is unavailable.");}}
    private void updateGrid(){if(bottom==null||drawer==null||canvas.editing)return;int row=GridMetrics.row(this,root.getHeight()-root.getPaddingTop()-bottom.getPaddingBottom());if(canvas.gridRow==row)return;canvas.gridRow=row;canvas.requestLayout();if(bottom.getChildCount()>1){View icons=bottom.getChildAt(1);icons.getLayoutParams().height=row;icons.requestLayout();}drawer.setGridRow(row);}
    private void renderDock(){if(bottom==null||catalog==null)return;if(touchingDock||contextGesture){dockRefreshPending=true;return;}dockRefreshPending=false;bottom.removeAllViews();
        FrameLayout hint=new FrameLayout(this);Button search=button("Search apps",()->openDrawer(true,true));search.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search,0,0,0);search.setCompoundDrawablePadding(dp(8));search.setTextSize(15);search.setTextColor(Glass.ink(this));search.setPadding(dp(18),0,dp(18),0);search.setMinHeight(0);search.setMinimumHeight(0);Glass.control(search);FrameLayout.LayoutParams pill=new FrameLayout.LayoutParams(Math.min(stage.getWidth()>0?stage.getWidth()-dp(32):dp(260),Math.max(dp(176),Math.round(dp(176)*getResources().getConfiguration().fontScale))),dp(48),Gravity.CENTER);hint.addView(search,pill);bottom.addView(hint,new LinearLayout.LayoutParams(-1,dp(52)));
        LinearLayout dock=new LinearLayout(this);List<AppCatalog.Entry> entries=catalog.dock(usage);Map<String,Integer> counts=NotificationRepository.counts();
        for(int i=0;i<4;i++){
            View tile;if(i<entries.size()){
                AppCatalog.Entry entry=entries.get(i);tile=new AppTile(this,entry,counts.getOrDefault(entry.packageName,0),false);
                tile.setOnClickListener(v->launchEntry(entry));tile.setOnLongClickListener(v->{appMenu(v,entry,null);return true;});enableDragTouch(tile,new DragPayload(null,entry));
            }else tile=new View(this);
            dock.addView(tile,new LinearLayout.LayoutParams(0,-1,1));
        }bottom.addView(dock,new LinearLayout.LayoutParams(-1,canvas.gridRow>0?canvas.gridRow:dp(88)));
    }
    private void beginDrawer(){beginDrawer(false);}
    private void beginDrawer(boolean searchOnly){
        if(canvas.editing)setEditing(false);if(folderPopup!=null)folderPopup.dismiss();
        drawer.open(false,searchOnly);drawer.bringToFront();drawer.updateGlass();canvas.setVisibility(View.INVISIBLE);bottom.setAlpha(0);if(surfaceBlurs!=null)surfaceBlurs.active(false);Glass.backdrop(getWindow(),canvas,true);
    }
    private Rect dockBounds(){int[] origin=new int[2],parent=new int[2];bottom.getLocationOnScreen(origin);stage.getLocationOnScreen(parent);return new Rect(origin[0]-parent[0],origin[1]-parent[1],origin[0]-parent[0]+bottom.getWidth(),origin[1]-parent[1]+bottom.getHeight());}
    private void revealDrawer(float amount){drawerReveal=amount;drawer.setReveal(amount,dockBounds());}
    void openDrawer(boolean keyboard){openDrawer(keyboard,false);}
    void openDrawer(boolean keyboard,boolean searchOnly){
        if(drawer.isOpen()){animateDrawer(true);if(keyboard)drawer.open(true);return;}beginDrawer(searchOnly);revealDrawer(0);animateDrawer(true);if(keyboard)drawer.open(true);
    }
    private void animateDrawer(boolean opening){
        if(opening&&!drawer.isOpen())beginDrawer();
        if(!opening)drawer.hideKeyboard();
        if(drawerMotion==null)drawerMotion=new Motion.Spring(drawerReveal,this::revealDrawer,()->{if(drawerReveal==0&&drawer.isOpen())closeDrawer();});
        drawerMotion.to(opening?1:0);
    }
    void closeDrawer(){if(drawerMotion!=null){drawerMotion.cancel();drawerMotion=null;}drawer.reset();drawerReveal=0;drawer.setTranslationY(0);canvas.setVisibility(View.VISIBLE);bottom.setAlpha(1);if(surfaceBlurs!=null)surfaceBlurs.active(foreground);Glass.backdrop(getWindow(),canvas,false);contextGesture=false;}
    private final class DockSurface extends LinearLayout{
        float x,y;boolean tracking;
        DockSurface(){super(HomeActivity.this);}
        @Override public boolean dispatchTouchEvent(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_DOWN)touchingDock=true;boolean result=super.dispatchTouchEvent(e);if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){touchingDock=false;if(dockRefreshPending&&!contextGesture)post(()->renderDock());}return result;}
        @Override public boolean onInterceptTouchEvent(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_DOWN){x=e.getX();y=e.getY();tracking=false;}
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&!contextGesture&&y-e.getY()>dp(10)&&y-e.getY()>Math.abs(x-e.getX())){tracking=true;beginDrawer();return true;}return tracking;}
        @Override public boolean onTouchEvent(MotionEvent e){if(!tracking)return true;float amount=Math.min(1,Math.max(0,(y-e.getY())/(stage.getHeight()*.45f)));revealDrawer(amount);
            if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){tracking=false;boolean complete=e.getActionMasked()!=MotionEvent.ACTION_CANCEL&&amount>.35f;animateDrawer(complete);if(complete)drawer.open(true);}return true;}
    }    private void appMenu(View source,AppCatalog.Entry entry,GridModel.Item homeItem){
        if(entry==null){if(homeItem!=null)itemMenu(homeItem);return;}
        if(contextPopup!=null)contextPopup.dismiss();contextGesture=true;canvas.manipulating=true;
        LinearLayout menu=new LinearLayout(this);menu.setOrientation(LinearLayout.VERTICAL);Glass.apply(menu);menu.setPadding(dp(12),dp(8),dp(12),dp(8));
        for(ShortcutInfo shortcut:catalog.shortcuts(entry)){
            Button action=button(String.valueOf(shortcut.getShortLabel()),()->{contextPopup.dismiss();try{getSystemService(LauncherApps.class).startShortcut(entry.packageName,shortcut.getId(),null,null,android.os.Process.myUserHandle());}catch(RuntimeException e){toast("Shortcut unavailable.");}});
            GridModel.Item child=new GridModel.Item(-4,entry.key,0,0,1,1);child.kind="shortcut";child.shortcutId=shortcut.getId();child.name=String.valueOf(shortcut.getShortLabel());action.setOnLongClickListener(v->{beginDrag(v,new DragPayload(child,null));return true;});menu.addView(action);
        }
        boolean pinned=pinKeys().contains(entry.key);menu.addView(button(pinned?"Unpin from Dock":"Pin to Dock",()->{togglePin(entry);contextPopup.dismiss();}));
        menu.addView(button("Add to Home",()->{contextPopup.dismiss();addHome(entry);enterEditing();}));
        if(homeItem!=null)menu.addView(button("Remove from Home",()->{removeItem(homeItem);contextPopup.dismiss();}));
        menu.addView(button("App Info",()->{contextPopup.dismiss();startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+entry.packageName)));}));
        Design.panel(menu);contextPopup=new PopupWindow(menu,dp(250),-2,false);contextPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));contextPopup.setOutsideTouchable(true);contextPopup.setOnDismissListener(()->{contextGesture=false;canvas.manipulating=false;if(dockRefreshPending)bottom.post(()->renderDock());});android.graphics.Insets safe=stage.getRootWindowInsets().getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
        int menuWidth=Math.min(dp(280),stage.getWidth()-dp(32)),available=stage.getHeight()-safe.top-safe.bottom-dp(16);
        menu.measure(View.MeasureSpec.makeMeasureSpec(menuWidth,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(Math.max(dp(48),available),View.MeasureSpec.AT_MOST));
        contextPopup.setWidth(menuWidth);contextPopup.setHeight(menu.getMeasuredHeight());contextPopup.setIsClippedToScreen(true);
        int[] anchor=new int[2];source.getLocationOnScreen(anchor);int below=anchor[1]+source.getHeight()+dp(8),above=anchor[1]-menu.getMeasuredHeight()-dp(8);
        int x=Math.max(safe.left+dp(8),Math.min(stage.getWidth()-safe.right-menuWidth-dp(8),anchor[0]+source.getWidth()/2-menuWidth/2));
        int y=below+menu.getMeasuredHeight()<=stage.getHeight()-safe.bottom-dp(8)?below:Math.max(safe.top+dp(8),above);
        contextPopup.showAtLocation(stage,Gravity.TOP|Gravity.LEFT,x,y);
        Haptics.tick(source,store.prefs.getInt("haptics",2));
    }
    private List<String> pinKeys(){List<String> keys=new ArrayList<>();try{org.json.JSONArray json=new org.json.JSONArray(store.prefs.getString("dockPins","[]"));for(int i=0;i<json.length();i++)keys.add(json.getString(i));}catch(org.json.JSONException ignored){}return keys;}
    private void togglePin(AppCatalog.Entry entry){List<String> keys=pinKeys();if(!keys.remove(entry.key)){if(keys.size()==4){toast("Unpin an app before pinning another.");return;}keys.add(entry.key);}store.prefs.edit().putString("dockPins",new org.json.JSONArray(keys).toString()).apply();renderDock();}
    static final class DragPayload{final GridModel.Item item;final AppCatalog.Entry entry;DragPayload(GridModel.Item item,AppCatalog.Entry entry){this.item=item;this.entry=entry;}}
    private void enableDragTouch(View source,DragPayload payload){source.setOnTouchListener(new View.OnTouchListener(){float x,y;
        public boolean onTouch(View v,MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_DOWN){x=e.getRawX();y=e.getRawY();}
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&contextGesture&&Math.hypot(e.getRawX()-x,e.getRawY()-y)>dp(12)){beginDrag(v,payload);return true;}return false;}});}
    private void beginDrag(View source,DragPayload payload){View.DragShadowBuilder shadow=new View.DragShadowBuilder(source){public void onProvideShadowMetrics(android.graphics.Point size,android.graphics.Point touch){size.set(Math.min(source.getWidth(),stage.getWidth()),Math.min(source.getHeight(),stage.getHeight()));touch.set(size.x/2,size.y/2);}};stage.startDragAndDrop(ClipData.newPlainText("Home item","item"),shadow,payload,0);if(contextPopup!=null)contextPopup.dismiss();if(folderPopup!=null)folderPopup.dismiss();closeDrawer();enterEditing();canvas.manipulating=false;}
    private boolean homeDrag(View view,DragEvent event){if(!(event.getLocalState() instanceof DragPayload))return false;DragPayload payload=(DragPayload)event.getLocalState();
        int column=Math.max(0,(int)(event.getX()+canvas.getScrollX())/canvas.cellWidth()),row=Math.max(0,Math.min(5,(int)event.getY()/canvas.cellHeight()));
        if(event.getAction()==DragEvent.ACTION_DRAG_LOCATION){canvas.dropColumn=column;canvas.dropRow=row;canvas.invalidate();if(event.getX()>canvas.getWidth()-dp(40)){if(canvas.getScrollX()>=canvas.maxScroll()-canvas.cellWidth())canvas.extraColumns+=4;canvas.setPosition((float)canvas.getScrollX()/canvas.cellWidth()+.2f);}else if(event.getX()<dp(40))canvas.setPosition((float)canvas.getScrollX()/canvas.cellWidth()-.2f);}
        if(event.getAction()==DragEvent.ACTION_DROP){GridModel.Item item=payload.item;
            if(item==null){item=new GridModel.Item(-1,payload.entry.key,0,0,1,1);item.name=payload.entry.label;}
            GridModel.Item existing=model.find(item.identity());if(existing!=null)item=existing;
            GridModel.Item target=null;for(GridModel.Item other:model.items)if(other!=item&&column>=other.column&&column<other.column+other.width&&row>=other.row&&row<other.row+other.height){target=other;break;}
            if(target!=null&&item.isApp()&&(target.isApp()||target.isFolder()))model.merge(item,target);else model.move(item,column,row,item.width,item.height);
            pinShortcut(item);store.save(model);render();Haptics.tick(canvas,store.prefs.getInt("haptics",2));}
        if(event.getAction()==DragEvent.ACTION_DRAG_ENDED){canvas.dropColumn=-1;canvas.extraColumns=0;canvas.setPosition((float)canvas.getScrollX()/canvas.cellWidth());canvas.persistPosition();canvas.invalidate();}return true;
    }
    private void addHome(AppCatalog.Entry entry){GridModel.Item item=new GridModel.Item(-1,entry.key,0,0,1,1);item.name=entry.label;GridModel.Item existing=model.find(item.identity());if(existing!=null)item=existing;
        model.move(item,Math.round((float)canvas.getScrollX()/canvas.cellWidth()),0,1,1);store.save(model);canvas.setPosition(item.column);canvas.persistPosition();render();}
    private void pinShortcut(GridModel.Item item){if(!item.kind.equals("shortcut"))return;ComponentName c=ComponentName.unflattenFromString(item.component);if(c==null)return;
        try{Set<String> ids=new HashSet<>();for(GridModel.Item i:model.items){if(i.kind.equals("shortcut")&&i.component.startsWith(c.getPackageName()+"/"))ids.add(i.shortcutId);for(GridModel.Item child:i.children)if(child.kind.equals("shortcut")&&child.component.startsWith(c.getPackageName()+"/"))ids.add(child.shortcutId);}ids.add(item.shortcutId);getSystemService(LauncherApps.class).pinShortcuts(c.getPackageName(),new ArrayList<>(ids),android.os.Process.myUserHandle());}catch(RuntimeException ignored){}}
    private void removeItem(GridModel.Item item){model.detach(item);if(item.isWidget())host.deleteAppWidgetId(item.widgetId);if(item.isNotification())store.prefs.edit().putBoolean("notificationsEnabled",false).apply();store.save(model);render();}
    private void itemMenu(GridModel.Item item){LinearLayout menu=new LinearLayout(this);menu.setOrientation(LinearLayout.VERTICAL);Glass.apply(menu);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(item.isFolder()?item.name:item.isWidget()?"Widget":"Home item").setView(menu).create();
        menu.addView(button("Move",()->{dialog.dismiss();enterEditing();selectMove(item);}));
        if(item.isWidget())menu.addView(button("Resize",()->{dialog.dismiss();resizeSelection=item;enterEditing();render();}));
        if(item.isFolder())menu.addView(button("Rename",()->{dialog.dismiss();rename(item);}));
        menu.addView(button("Remove from Home",()->{dialog.dismiss();removeItem(item);}));dialog.setOnShowListener(d->{contextGesture=true;canvas.manipulating=true;});dialog.setOnDismissListener(d->{contextGesture=false;canvas.manipulating=false;});dialog.show();
    }
    private void enterEditing(){setEditing(true);}
    void setEditing(boolean editing){if(canvas.editing==editing)return;canvas.persistPosition();canvas.editing=editing;bottom.setVisibility(editing?View.GONE:View.VISIBLE);root.setPadding(root.getPaddingLeft(),editing?0:stage.getRootWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()|WindowInsets.Type.displayCutout()).top,root.getPaddingRight(),editing?bottom.getPaddingBottom():0);getWindow().setNavigationBarColor(editing?Design.surface(this):Color.TRANSPARENT);getWindow().getInsetsController().setSystemBarsAppearance(editing&&!Glass.dark(this)?WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS:0,WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        if(editing){
            getWindow().getInsetsController().hide(WindowInsets.Type.statusBars());editingScroll=new ScrollView(this);editingScroll.setVerticalScrollBarEnabled(false);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);editingScroll.addView(body);
            int height=Math.max(dp(400),stage.getHeight());stage.removeView(root);body.addView(root,new LinearLayout.LayoutParams(-1,height));
            editingControls=new LinearLayout(this);editingControls.setOrientation(LinearLayout.VERTICAL);LinearLayout actions=new LinearLayout(this);
            Button addWidget=button("Widgets",this::chooseWidget),apps=button("Apps",()->openDrawerForEditing()),items=button("Items",this::manageItems),done=button("Done",()->setEditing(false));
            for(Button action:new Button[]{addWidget,apps,items,done})actions.addView(action,new LinearLayout.LayoutParams(0,dp(52),1));Design.panel(actions);editingControls.addView(actions,new LinearLayout.LayoutParams(-1,dp(52)));
            Button settings=button("Launcher Settings ↓",()->editingScroll.smoothScrollTo(0,height));Design.control(settings);editingControls.setBackgroundColor(Design.surface(this));editingControls.addView(settings,new LinearLayout.LayoutParams(-1,dp(48)));
            TextView remove=text("Remove");remove.setPadding(0,dp(16),0,dp(16));remove.setOnDragListener((v,e)->{if(!(e.getLocalState()instanceof DragPayload))return false;if(e.getAction()==DragEvent.ACTION_DROP){GridModel.Item item=((DragPayload)e.getLocalState()).item;if(item!=null)removeItem(item);}v.setBackgroundColor(e.getAction()==DragEvent.ACTION_DRAG_ENTERED?0xCCBE6464:0x55444444);return true;});root.addView(remove);root.addView(editingControls);
            editingSettings=new SettingsPanel(this,this::settingsChanged);body.addView(editingSettings);stage.addView(editingScroll,0,new FrameLayout.LayoutParams(-1,-1));
            editingScroll.post(()->{if(editingScroll==null||!canvas.editing)return;int[] position=new int[2],parent=new int[2];root.getLocationOnScreen(position);stage.getLocationOnScreen(parent);root.getLayoutParams().height=Math.max(dp(400),stage.getHeight()-Math.max(0,position[1]-parent[1]));root.requestLayout();});
        }else{
            canvas.moveMode=false;canvas.manipulating=false;canvas.onManipulate=null;canvas.dropColumn=-1;canvas.extraColumns=0;
            getWindow().getInsetsController().show(WindowInsets.Type.statusBars());resizeSelection=null;
            if(editingScroll!=null){((android.view.ViewGroup)root.getParent()).removeView(root);stage.removeView(editingScroll);stage.addView(root,0,new FrameLayout.LayoutParams(-1,-1));editingSettings.close();editingSettings=null;editingScroll=null;}
            while(root.getChildCount()>2)root.removeViewAt(2);root.requestLayout();render();
        }canvas.invalidate();
    }
    private void openDrawerForEditing(){setEditing(false);openDrawer(true);}
    private void settingsChanged(){model.reserveNotifications(store.prefs.getBoolean("notificationsEnabled",true),store.prefs.getInt("notificationWidth",3));store.save(model);render();renderDock();}
    private void updateFolderBadges(){if(canvas==null)return;Map<String,Integer> counts=NotificationRepository.counts();for(GridModel.Item folder:model.items)if(folder.isFolder()){
        TextView label=canvas.findViewWithTag("folder-badge:"+folder.uid);if(label==null)continue;int total=0;Set<String> packages=new HashSet<>();for(GridModel.Item child:folder.children){ComponentName c=ComponentName.unflattenFromString(child.component);if(c!=null&&packages.add(c.getPackageName()))total+=counts.getOrDefault(c.getPackageName(),0);}label.setText(folder.name+(total>0?"  · "+total:""));}}
    private void manageItems(){List<GridModel.Item> items=new ArrayList<>(model.items);new AlertDialog.Builder(this).setTitle("Home items").setItems(items.stream().map(i->{if(i.isNotification())return "Notification widget";if(i.isFolder())return i.name;if(i.isWidget()){AppWidgetProviderInfo p=widgets.getAppWidgetInfo(i.widgetId);return p==null?"Unavailable widget":p.loadLabel(getPackageManager());}AppCatalog.Entry e=catalog.find(i.component);return e==null?i.name:e.label;}).toArray(String[]::new),(d,i)->itemMenu(items.get(i))).show();}
    private void selectMove(GridModel.Item item){if(item.isNotification()){toast("The notification widget stays at the left boundary.");return;}canvas.moveMode=true;canvas.manipulating=true;toast("Drag anywhere on the canvas to place this item.");
        canvas.onManipulate=e->{int column=Math.max(0,(int)(e.getX()+canvas.getScrollX())/canvas.cellWidth()),row=Math.max(0,Math.min(6-item.height,(int)e.getY()/canvas.cellHeight()));canvas.dropColumn=column;canvas.dropRow=row;canvas.invalidate();
            if(e.getActionMasked()==MotionEvent.ACTION_UP){model.move(item,column,row,item.width,item.height);store.save(model);canvas.moveMode=false;canvas.manipulating=false;canvas.dropColumn=-1;render();Haptics.tick(canvas,store.prefs.getInt("haptics",2));}
            else if(e.getActionMasked()==MotionEvent.ACTION_CANCEL){canvas.moveMode=false;canvas.manipulating=false;canvas.dropColumn=-1;canvas.invalidate();}};
    }
    private void handlePin(Intent intent){LauncherApps.PinItemRequest request=getSystemService(LauncherApps.class).getPinItemRequest(intent);if(request==null||!request.isValid())return;
        if(request.getRequestType()==LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT){ShortcutInfo shortcut=request.getShortcutInfo();new AlertDialog.Builder(this).setTitle("Add to Home").setMessage(shortcut.getShortLabel()).setPositiveButton("Add",(d,i)->{
            if(!request.accept())return;ComponentName activity=shortcut.getActivity();if(activity==null){Intent launch=getPackageManager().getLaunchIntentForPackage(shortcut.getPackage());if(launch!=null)activity=launch.getComponent();}if(activity==null)return;
            GridModel.Item item=new GridModel.Item(-4,activity.flattenToString(),0,0,1,1);item.kind="shortcut";item.shortcutId=shortcut.getId();item.name=String.valueOf(shortcut.getShortLabel());GridModel.Item existing=model.find(item.identity());if(existing!=null)item=existing;
            model.move(item,canvas.getScrollX()/canvas.cellWidth(),0,1,1);store.save(model);render();}).setNegativeButton("Cancel",null).show();}
    }
    void chooseWidget() {
        if (pendingId >= 0) {
            new AlertDialog.Builder(this).setMessage("Discard the unfinished widget setup?")
                    .setPositiveButton("Discard", (d, i) -> { cancelPending(); chooseWidget(); }).setNegativeButton("Keep", null).show(); return;
        }
        List<AppWidgetProviderInfo> providers = new ArrayList<>(widgets.getInstalledProviders());
        providers.sort(Comparator.comparing(p -> p.loadLabel(getPackageManager()), String.CASE_INSENSITIVE_ORDER));
        if (providers.isEmpty()) { toast("No widgets are installed."); return; }
        new AlertDialog.Builder(this).setTitle("Add widget")
                .setItems(providers.stream().map(p -> p.loadLabel(getPackageManager())).toArray(String[]::new), (d, i) -> beginWidget(providers.get(i))).show();
    }
    void beginWidget(AppWidgetProviderInfo info) {
        int width = Math.max(1, (info.minWidth + canvas.cellWidth() - 1) / canvas.cellWidth());
        int height = Math.max(1, (info.minHeight + canvas.cellHeight() - 1) / canvas.cellHeight());
        if(info.targetCellWidth>0)width=Math.max(width,info.targetCellWidth);
        if(info.targetCellHeight>0&&info.targetCellHeight<=6)height=Math.max(height,info.targetCellHeight);
        if (width > 64 || height > 6) { toast("This widget does not fit the six-row canvas."); return; }
        pendingId = host.allocateAppWidgetId();
        store.prefs.edit().putInt("pendingWidget", pendingId).putInt("pendingWidth", width).putInt("pendingHeight", height).commit();
        Bundle options = new Bundle(); options.putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN);
        try {
            if (widgets.bindAppWidgetIdIfAllowed(pendingId, info.provider, options)) configurePending();
            else startActivityForResult(new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options), BIND);
        } catch (RuntimeException e) { cancelPending(); toast("This widget could not be added."); }
    }
    private void configurePending() {
        AppWidgetProviderInfo info = widgets.getAppWidgetInfo(pendingId);
        if (info == null) { cancelPending(); return; }
        if (info.configure != null) {
            try {
                // Android 14+ requires the visible sender to opt in when launching this system PendingIntent.
                Bundle options = android.app.ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle();
                host.startAppWidgetConfigureActivityForResult(this, pendingId, 0, CONFIGURE, options);
            }
            catch (RuntimeException e) { cancelPending(); toast("Widget setup is unavailable."); }
        } else finishPending();
    }
    void finishPending() {
        AppWidgetProviderInfo info = widgets.getAppWidgetInfo(pendingId);
        if (info == null) { cancelPending(); return; }
        if (model.widget(pendingId) == null) {
            GridModel.Item item = new GridModel.Item(pendingId, info.provider.flattenToString(), 0, 0,
                    store.prefs.getInt("pendingWidth", 2), store.prefs.getInt("pendingHeight", 2));
            model.place(item, canvas.getScrollX() / canvas.cellWidth()); store.save(model);
        }
        pendingId = -1; store.prefs.edit().remove("pendingWidget").apply(); render(); setEditing(false);
    }
    void cancelPending() {
        if (pendingId >= 0 && model.widget(pendingId) == null) host.deleteAppWidgetId(pendingId);
        pendingId = -1; store.prefs.edit().remove("pendingWidget").apply();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != BIND && request != CONFIGURE) return;
        if (pendingId < 0) return;
        if (result != RESULT_OK) { cancelPending(); return; }
        if (request == BIND) configurePending(); else finishPending();
    }
    TextView text(String content) {
        TextView text = new TextView(this); text.setText(content); text.setGravity(Gravity.CENTER);
        Design.type(text,14,20,400);text.setTextColor(Glass.ink(this));text.setShadowLayer(.7f*getResources().getDisplayMetrics().density,0,0,0x80000000);
        text.setSoundEffectsEnabled(false); return text;
    }
    Button button(String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setSoundEffectsEnabled(false);
        button.setTextColor(Glass.ink(this));Glass.control(button);button.setOnClickListener(v -> action.run()); return button;
    }
    int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

}
