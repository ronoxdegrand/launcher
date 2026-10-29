package dev.glgra.launcher;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;

/** Product typography and spacing roles. Opaque reading surfaces complement wallpaper glass. */
final class Design {
    static int ink(Context c){return Glass.dark(c)?0xFFF2F4F8:0xFF20242C;}
    static int secondary(Context c){return Glass.dark(c)?0xFFBCC2CD:0xFF5B626E;}
    static int surface(Context c){return Glass.dark(c)?0xFF181C23:0xFFF3F5F9;}
    static int accent(Context c){return c.getColor(Glass.dark(c)?android.R.color.system_accent1_200:android.R.color.system_accent1_600);}
    static void type(TextView view,int size,int line,int weight){
        view.setTextSize(size);view.setTypeface(Typeface.create(Typeface.create("sans-serif",Typeface.NORMAL),weight,false));
        view.setIncludeFontPadding(false);view.setLetterSpacing(.01f);
        view.setLineHeight(Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,line,view.getResources().getDisplayMetrics())));
        view.setShadowLayer(0,0,0,0);view.setSoundEffectsEnabled(false);
    }
    static void label(TextView view){type(view,12,16,500);}
    static void caption(TextView view){type(view,12,16,600);view.setTextColor(0xDDF2F4F8);}
    static void panel(View root){
        Context c=root.getContext();android.graphics.drawable.GradientDrawable background=Glass.shape(c,Glass.ROUNDED,surface(c));
        background.setStroke(1,Glass.dark(c)?0x28FFFFFF:0x18000000);root.setBackground(background);reading(root);
    }
    private static void reading(View view){
        if(view instanceof TextView text){text.setTextColor(ink(view.getContext()));text.setShadowLayer(0,0,0,0);
            if(view instanceof Button && !(view instanceof CompoundButton))control((Button)view);}
        if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++)reading(group.getChildAt(i));
    }
    static void control(Button button){
        Context c=button.getContext();type(button,14,20,500);button.setTextColor(ink(c));button.setAllCaps(false);
        button.setMinHeight(Glass.dp(c,48));button.setMinimumHeight(Glass.dp(c,48));button.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
        button.setPadding(Glass.dp(c,16),Glass.dp(c,12),Glass.dp(c,16),Glass.dp(c,12));
        GradientDrawable mask=Glass.shape(c,Glass.ROUNDED,0xFFFFFFFF);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(Glass.dark(c)?0x1AFFFFFF:0x14000000),null,mask));
        button.setStateListAnimator(null);button.setElevation(0);
    }
    static void press(View view){view.setForeground(new RippleDrawable(ColorStateList.valueOf(0x18FFFFFF),null,Glass.shape(view.getContext(),Glass.ROUNDED,0xFFFFFFFF)));}
    static TextView more(Context c,String value){TextView text=new TextView(c);text.setText(value);type(text,22,28,500);text.setTextColor(Glass.ink(c));text.setGravity(Gravity.CENTER);text.setMinimumHeight(Glass.dp(c,48));
        text.setBackground(new RippleDrawable(ColorStateList.valueOf(0x20FFFFFF),null,Glass.shape(c,Glass.ROUNDED,0xFFFFFFFF)));return text;}
}
