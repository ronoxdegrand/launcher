package dev.glgra.launcher;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.View;

/** One downsampled blur per wallpaper/theme change; never renders a blur continuously. */
final class Glass {
    private static Bitmap cached;
    private static int wallpaperId = -1;
    static int dp(Context c, int value) { return Math.round(c.getResources().getDisplayMetrics().density * value); }
    static boolean dark(Context c) { return (c.getResources().getConfiguration().uiMode & 0x30) == 0x20; }
    static int ink(Context c) { return dark(c) ? 0xFFF1F6F5 : 0xFF19332F; }
    static void apply(View view) {
        apply(view,24);
    }
    static void applySquare(View view) { apply(view,0); }
    private static void apply(View view,int radius) {
        Context context = view.getContext();
        WallpaperManager wm = WallpaperManager.getInstance(context);
        int id = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
        if (id != wallpaperId) {
            wallpaperId = id; cached = null;
            try {
                // Android 14+ no longer exposes wallpaper pixels to an ordinary launcher.
                // A cached palette surface is the public-API fallback; never request all-files access for blur.
                android.app.WallpaperColors colors=wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
                Drawable source = colors==null?null:new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{colors.getPrimaryColor().toArgb(),colors.getSecondaryColor()==null?colors.getPrimaryColor().toArgb():colors.getSecondaryColor().toArgb()});
                if (source != null) {
                    Bitmap image = Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888); Canvas canvas = new Canvas(image);
                    source.setBounds(0, 0, 40, 80); source.draw(canvas);
                    // Repeated small box filtering at 40x80 avoids hardware buffers and expensive full-resolution copies.
                    int[] pixels = new int[3200], result = new int[3200]; image.getPixels(pixels,0,40,0,0,40,80);
                    for (int pass = 0; pass < 4; pass++) {
                        for (int y=0;y<80;y++) for(int x=0;x<40;x++) {
                            int r=0,g=0,b=0,n=0;
                            for(int dy=-2;dy<=2;dy++) for(int dx=-2;dx<=2;dx++) {
                                int p=pixels[Math.max(0,Math.min(79,y+dy))*40+Math.max(0,Math.min(39,x+dx))];
                                r+=Color.red(p);g+=Color.green(p);b+=Color.blue(p);n++;
                            }
                            result[y*40+x]=Color.rgb(r/n,g/n,b/n);
                        }
                        int[] swap=pixels;pixels=result;result=swap;
                    }
                    image.setPixels(pixels,0,40,0,0,40,80); cached=image;
                }
            } catch (SecurityException ignored) { /* Modern Android may hide wallpaper pixels from ordinary apps. */ }
        }
        GradientDrawable tint = new GradientDrawable(); tint.setCornerRadius(dp(context, radius));
        tint.setColor(dark(context) ? 0xE0283434 : 0xE6F3F7F4);
        Drawable background = tint;
        if (cached != null) {
            BitmapDrawable blurred = new BitmapDrawable(context.getResources(),cached); blurred.setGravity(android.view.Gravity.FILL);
            background = new LayerDrawable(new Drawable[]{blurred,tint});
        }
        view.setBackground(background);view.setOutlineProvider(new android.view.ViewOutlineProvider(){public void getOutline(View target,android.graphics.Outline outline){outline.setRoundRect(0,0,target.getWidth(),target.getHeight(),dp(context,radius));}});view.setClipToOutline(true); view.setSoundEffectsEnabled(false);
    }
}
