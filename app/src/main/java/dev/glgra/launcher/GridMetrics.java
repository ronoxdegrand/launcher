package dev.glgra.launcher;

import android.content.Context;

/** Seven shared rows plus a search affordance and a small continuation glimpse. */
final class GridMetrics {
    static int row(Context context,int usableHeight){return Math.max(1,(usableHeight-Glass.dp(context,52)-Glass.dp(context,20))/7);}
}
