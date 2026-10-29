package dev.glgra.launcher;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.*;

/** Shared geometry makes a dock app become a search result without changing columns. */
final class AppTile extends LinearLayout {
    final TextView title;
    AppTile(Context context, AppCatalog.Entry entry, int count, boolean named) {
        super(context); setOrientation(VERTICAL); setGravity(Gravity.CENTER);
        setPadding(Glass.dp(context,4), Glass.dp(context,16),Glass.dp(context,4),0); setMinimumHeight(0);
        setContentDescription(entry.label); setSoundEffectsEnabled(false);
        FrameLayout image=new FrameLayout(context);
        ImageView icon=new ImageView(context);
        icon.setImageDrawable(entry.icon.getConstantState()==null?entry.icon:entry.icon.getConstantState().newDrawable());
        FrameLayout.LayoutParams ip=new FrameLayout.LayoutParams(Glass.dp(context,48),Glass.dp(context,48),Gravity.CENTER);
        image.addView(icon,ip); addView(image,new LinearLayout.LayoutParams(Glass.dp(context,60),Glass.dp(context,52)));
        if(count>0){
            TextView badge=new TextView(context);badge.setText(count>99?"99+":String.valueOf(count));Design.type(badge,10,12,600);
            badge.setTypeface(null,Typeface.BOLD);badge.setTextColor(0xFFFFFFFF);badge.setGravity(Gravity.CENTER);
            GradientDrawable fill=new GradientDrawable();fill.setColor(0xFFD35363);fill.setCornerRadius(Glass.dp(context,12));fill.setStroke(Glass.dp(context,2),Glass.dark(context)?0xFF20282D:0xFFF0F4F7);badge.setBackground(fill);
            FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(Glass.dp(context,count>99?24:20),Glass.dp(context,20),Gravity.TOP|Gravity.RIGHT);image.addView(badge,bp);
        }
        title=new TextView(context);title.setText(entry.label);title.setTextSize(12);title.setTextColor(Glass.ink(context));
        Design.label(title);title.setGravity(Gravity.CENTER);title.setLines(getResources().getConfiguration().fontScale>1.15f?1:2);title.setEllipsize(TextUtils.TruncateAt.END);title.setIncludeFontPadding(false);title.setShadowLayer(.7f*getResources().getDisplayMetrics().density,0,0,0x80000000);
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-1,-2);tp.topMargin=Glass.dp(context,4);addView(title,tp);
        Design.press(this);title.setAlpha(named?1:0);setClipChildren(false);
    }
}
