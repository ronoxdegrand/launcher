package dev.glgra.launcher;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.View;

/** Neutral translucent glass; blur pixels come from the compositor, never a white palette image. */
final class Glass {
    static final int ROUNDED=0,DOCK=1,NOTIFICATIONS=2,SQUARE=3;
    static int dp(Context c,int value){return Math.round(c.getResources().getDisplayMetrics().density*value);}
    static boolean dark(Context c){return(c.getResources().getConfiguration().uiMode&0x30)==0x20;}
    static int ink(Context c){return 0xFFF5F6F8;}
    static boolean blurAvailable(Context c){return c.getSystemService(android.view.WindowManager.class).isCrossWindowBlurEnabled();}
    static GradientDrawable shape(Context c,int type,int color){GradientDrawable fill=new GradientDrawable();fill.setColor(color);float r=dp(c,28);fill.setCornerRadii(switch(type){case DOCK->new float[]{r,r,r,r,0,0,0,0};case NOTIFICATIONS->new float[]{0,0,r,r,r,r,0,0};case SQUARE->new float[8];default->new float[]{r,r,r,r,r,r,r,r};});return fill;}
    static void apply(View view){surface(view,ROUNDED);}
    static void applyDock(View view){surface(view,DOCK);}
    static void applyNotifications(View view){surface(view,NOTIFICATIONS);}
    static void applySquare(View view){surface(view,SQUARE);}
    private static void surface(View view,int type){Context c=view.getContext();boolean live=type!=ROUNDED&&blurAvailable(c);int color=live?(dark(c)?0x48000000:0x30000000):(dark(c)?0xEB202329:0xDB34383E);GradientDrawable background=shape(c,type,color);view.setBackground(background);view.setElevation(0);view.setSoundEffectsEnabled(false);view.setClipToOutline(false);}
    static void control(View view){Context c=view.getContext();GradientDrawable fill=shape(c,ROUNDED,dark(c)?0x42000000:0x28000000);fill.setStroke(Math.max(1,dp(c,1)),0x38FFFFFF);view.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(0x22FFFFFF),fill,null));view.setStateListAnimator(null);view.setElevation(0);view.setSoundEffectsEnabled(false);}
    static void backdrop(android.view.Window window,View canvas,boolean active){boolean enabled=active&&blurAvailable(canvas.getContext());android.view.WindowManager.LayoutParams p=window.getAttributes();p.setBlurBehindRadius(enabled?dp(canvas.getContext(),28):0);if(enabled)p.flags|=android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND;else p.flags&=~android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND;window.setAttributes(p);canvas.setRenderEffect(enabled?RenderEffect.createBlurEffect(dp(canvas.getContext(),14),dp(canvas.getContext(),14),Shader.TileMode.CLAMP):null);}
}
