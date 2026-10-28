package dev.glgra.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import java.util.function.Supplier;

/** Public, non-interactive child windows behind Home provide real regional wallpaper blur. */
final class SurfaceBlur implements AutoCloseable {
    private final Activity activity;
    private final View root;
    private final Supplier<View> notification;
    private final View dock;
    private final Dialog[] windows=new Dialog[2];
    private final Rect[] previous={new Rect(),new Rect()};
    private boolean active;
    private final ViewTreeObserver.OnGlobalLayoutListener layout=this::sync;
    private final ViewTreeObserver.OnScrollChangedListener scroll=this::sync;
    SurfaceBlur(Activity activity,View root,View dock,Supplier<View> notification){this.activity=activity;this.root=root;this.dock=dock;this.notification=notification;root.getViewTreeObserver().addOnGlobalLayoutListener(layout);root.getViewTreeObserver().addOnScrollChangedListener(scroll);}
    void active(boolean value){active=value;sync();}
    void sync(){update(0,dock);update(1,notification.get());}
    int visibleCount(){int count=0;for(Dialog window:windows)if(window!=null&&window.isShowing()&&window.getWindow().getDecorView().getVisibility()==View.VISIBLE)count++;return count;}
    private void update(int index,View target){
        Rect bounds=new Rect();
        boolean visible=active&&Glass.blurAvailable(activity)&&root.isAttachedToWindow()&&target!=null&&target.isShown()&&target.getAlpha()>0&&target.getGlobalVisibleRect(bounds)&&!bounds.isEmpty();
        if(!visible){if(windows[index]!=null)windows[index].hide();previous[index].setEmpty();return;}
        if(windows[index]==null){
            Dialog dialog=new Dialog(activity,R.style.BlurRegionTheme);dialog.setContentView(new View(activity));Window window=dialog.getWindow();
            window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA);
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
            window.setNavigationBarContrastEnforced(false);window.setStatusBarContrastEnforced(false);
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            window.setDecorFitsSystemWindows(false);window.setBackgroundDrawable(Glass.shape(activity,index==0?Glass.DOCK:Glass.NOTIFICATIONS,0x01000000));
            WindowManager.LayoutParams params=window.getAttributes();params.token=activity.getWindow().getDecorView().getWindowToken();params.gravity=Gravity.TOP|Gravity.LEFT;params.windowAnimations=0;params.setFitInsetsTypes(0);window.setAttributes(params);
            window.setBackgroundBlurRadius(Glass.dp(activity,28));windows[index]=dialog;
        }
        Dialog dialog=windows[index];
        if(!dialog.isShowing()||dialog.getWindow().getDecorView().getVisibility()!=View.VISIBLE)dialog.show();
        if(!bounds.equals(previous[index])){Window window=dialog.getWindow();WindowManager.LayoutParams params=window.getAttributes();params.x=bounds.left;params.y=bounds.top;params.width=bounds.width();params.height=bounds.height();window.setAttributes(params);previous[index].set(bounds);}
    }
    public void close(){active=false;if(root.getViewTreeObserver().isAlive()){root.getViewTreeObserver().removeOnGlobalLayoutListener(layout);root.getViewTreeObserver().removeOnScrollChangedListener(scroll);}for(Dialog window:windows)if(window!=null)window.dismiss();}
}
