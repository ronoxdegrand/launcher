package dev.glgra.launcher;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Measured design acceptance: continuity, interruption, type, touch targets and reading contrast. */
public final class DesignTests extends Instrumentation {
    private void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private void main(Runnable action){AtomicReference<Throwable> failure=new AtomicReference<>();runOnMainSync(()->{try{action.run();}catch(Throwable t){failure.set(t);}});if(failure.get()!=null)throw new AssertionError(failure.get());}
    @SuppressWarnings("unchecked") private <T>T field(Object o,String name){try{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return(T)f.get(o);}catch(Exception e){throw new AssertionError(e);}}
    private void await(BooleanSupplier condition,String message){long end=SystemClock.uptimeMillis()+10000;while(SystemClock.uptimeMillis()<end){AtomicBoolean result=new AtomicBoolean();main(()->result.set(condition.getAsBoolean()));if(result.get())return;SystemClock.sleep(50);}throw new AssertionError(message);}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private double luminance(int color){double sum=0;double[] weights={.2126,.7152,.0722};for(int i=0;i<3;i++){double value=((color>>(16-i*8))&255)/255d;sum+=weights[i]*(value<=.04045?value/12.92:Math.pow((value+.055)/1.055,2.4));}return sum;}
    private double contrast(int a,int b){double x=luminance(a),y=luminance(b);return(Math.max(x,y)+.05)/(Math.min(x,y)+.05);}
    private void shot(String name)throws Exception{waitForIdleSync();SystemClock.sleep(120);android.graphics.Bitmap bitmap=getUiAutomation().takeScreenshot();java.io.File file=new java.io.File(getTargetContext().getExternalFilesDir("design-v025"),name+".png");try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out),"Save screenshot");}finally{bitmap.recycle();}}
    private TextView findText(View root,String value,boolean prefix){if(root instanceof TextView text&&(prefix?text.getText().toString().startsWith(value):text.getText().toString().equals(value)))return text;if(root instanceof android.view.ViewGroup group)for(int i=0;i<group.getChildCount();i++){TextView found=findText(group.getChildAt(i),value,prefix);if(found!=null)return found;}return null;}
    @Override public void onStart(){Bundle result=new Bundle();try{
        for(int mode:new int[]{Configuration.UI_MODE_NIGHT_NO,Configuration.UI_MODE_NIGHT_YES}){
            Configuration config=new Configuration(getTargetContext().getResources().getConfiguration());config.uiMode=(config.uiMode&~Configuration.UI_MODE_NIGHT_MASK)|mode;Context themed=getTargetContext().createConfigurationContext(config);
            check(contrast(Design.ink(themed),Design.surface(themed))>=4.5,"Primary reading text contrast");check(contrast(Design.secondary(themed),Design.surface(themed))>=4.5,"Secondary reading text contrast");
        }
        HomeActivity home=(HomeActivity)startActivitySync(new Intent(getTargetContext(),HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        await(()->home.hasWindowFocus()&&home.catalog.all().size()>=4&&home.canvas.gridRow>0,"Home ready");waitForIdleSync();
        main(()->{FrameLayout hint=(FrameLayout)home.bottom.getChildAt(0);check(hint.getChildAt(0).getHeight()>=Glass.dp(home,48),"Search target >=48dp");home.openDrawer(false);});
        await(()->(Float)field(home,"drawerReveal")>=.999f,"Spring settles open");
        LinearLayout results=field(home.drawer,"results");HorizontalScrollView strip=field(home.drawer,"resultStrip");
        int[] resting=new int[2],originTop=new int[1];
        main(()->{Motion.Spring spring=field(home,"drawerMotion");if(spring!=null)spring.cancel();Rect origin=new Rect();home.bottom.getGlobalVisibleRect(origin);originTop[0]=origin.top+Glass.dp(home,52);strip.getLocationOnScreen(resting);home.drawer.setReveal(0,origin);int[] actual=new int[2];strip.getLocationOnScreen(actual);check(Math.abs(actual[1]-originTop[0])<=2,"First search row begins at dock row");home.drawer.setReveal(.5f,origin);strip.getLocationOnScreen(actual);check(actual[1]<originTop[0]&&actual[1]>resting[1],"Icons travel between dock and search");Rect visible=new Rect();check(results.getChildAt(0).getGlobalVisibleRect(visible)&&visible.height()>0,"Transition icon is visible, not just positioned");});
        shot("transition-halfway");
        main(()->{Rect origin=new Rect();home.bottom.getGlobalVisibleRect(origin);home.drawer.setReveal(1,origin);for(int i=0;i<4;i++){View tile=results.getChildAt(i);check(tile.getHeight()==home.canvas.cellHeight(),"Search row grid preserved");}home.drawer.search.setText("masse");});shot("search");
        main(()->{home.drawer.search.setText("");home.drawer.search.requestFocus();home.drawer.search.clearFocus();});
        await(()->(Boolean)field(home.drawer,"browsing"),"Category presentation ready");shot("categories");
        main(()->{LinearLayout categories=field(home.drawer,"categories");TextView more=findText(categories,"+",true);if(more!=null)more.performClick();});
        SystemClock.sleep(320);
        main(()->{if(!((String)field(home.drawer,"expanded")).isEmpty()){TextView collapse=findText(home.drawer,"−",false);check(collapse!=null&&collapse.getHeight()>=Glass.dp(home,48),"Expanded category collapse is a full touch target");collapse.performClick();check(((String)field(home.drawer,"expanded")).isEmpty(),"Category collapses");}});
        main(()->home.closeDrawer());
        if(android.animation.ValueAnimator.areAnimatorsEnabled()){
            main(()->home.openDrawer(false));SystemClock.sleep(90);
            main(()->{try{float before=field(home,"drawerReveal");java.lang.reflect.Method method=HomeActivity.class.getDeclaredMethod("animateDrawer",boolean.class);method.setAccessible(true);method.invoke(home,false);check(Math.abs((Float)field(home,"drawerReveal")-before)<.001f,"Retarget keeps current position");}catch(Exception e){throw new AssertionError(e);}});
            SystemClock.sleep(60);main(()->home.openDrawer(false));await(()->(Float)field(home,"drawerReveal")>=.999f,"Interrupted spring settles open");main(home::closeDrawer);
        }
        shot("home");
        main(()->((LinearLayout)home.bottom.getChildAt(1)).getChildAt(0).performLongClick());await(()->{PopupWindow popup=field(home,"contextPopup");Rect visible=new Rect();return popup!=null&&popup.isShowing()&&popup.getContentView().getGlobalVisibleRect(visible)&&visible.height()==popup.getContentView().getHeight();},"App menu visible");SystemClock.sleep(300);shot("app-menu");main(()->{PopupWindow popup=field(home,"contextPopup");if(popup!=null)popup.dismiss();});
        main(()->home.setEditing(true));await(()->home.canvas.editing&&!home.canvas.isLayoutRequested()&&!home.stage.getRootWindowInsets().isVisible(WindowInsets.Type.statusBars()),"Editing workspace ready");SystemClock.sleep(350);main(()->{LinearLayout controls=field(home,"editingControls");View settingsRow=controls.getChildAt(1);int[] location=new int[2];settingsRow.getLocationOnScreen(location);int[] stageOrigin=new int[2];home.stage.getLocationOnScreen(stageOrigin);int bottom=stageOrigin[1]+home.stage.getHeight()-home.stage.getRootWindowInsets().getInsets(WindowInsets.Type.navigationBars()).bottom;check(location[1]+settingsRow.getHeight()<=bottom+2,"Editing footer avoids navigation: "+location[1]+"+"+settingsRow.getHeight()+" > "+bottom);});shot("editing");main(()->home.setEditing(false));
        main(()->{try{GridModel.Item folder=new GridModel.Item(-2,"",0,0,2,2);folder.kind="folder";folder.name="Everyday";for(int i=0;i<4;i++){AppCatalog.Entry app=home.catalog.all().get(i);folder.children.add(new GridModel.Item(-1,app.key,0,0,1,1));}java.lang.reflect.Method open=HomeActivity.class.getDeclaredMethod("openFolder",GridModel.Item.class,View.class);open.setAccessible(true);open.invoke(home,folder,home.bottom);}catch(Exception e){throw new AssertionError(e);}});
        await(()->{PopupWindow popup=field(home,"folderPopup");return popup!=null&&popup.isShowing()&&popup.getContentView().getAlpha()==1;},"Folder settles");shot("folder");main(()->{PopupWindow popup=field(home,"folderPopup");popup.dismiss();});
        SettingsActivity settings=(SettingsActivity)startActivitySync(new Intent(getTargetContext(),SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));await(settings::hasWindowFocus,"Settings ready");shot("settings");main(settings::finish);
        result.putString("stream","PASS reading contrast in both themes, >=48dp Home search target, shared grid, actual dock-to-search icon travel, interruption without position jump, settled spring and screenshots.\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable t){result.putString("stream",android.util.Log.getStackTraceString(t));finish(Activity.RESULT_CANCELED,result);}}
}
