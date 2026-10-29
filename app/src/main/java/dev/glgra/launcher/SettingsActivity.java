package dev.glgra.launcher;
import android.app.Activity;
import android.os.Bundle;
import android.widget.ScrollView;
public final class SettingsActivity extends Activity {
    private SettingsPanel panel;
    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setDecorFitsSystemWindows(false);getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Design.surface(this)));getWindow().setNavigationBarContrastEnforced(false);ScrollView scroll=new ScrollView(this);panel=new SettingsPanel(this);scroll.addView(panel);setContentView(scroll);getWindow().getInsetsController().setSystemBarsAppearance(Glass.dark(this)?0:android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);scroll.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets safe=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());scroll.setPadding(safe.left,safe.top,safe.right,safe.bottom);return insets;});}
    @Override protected void onDestroy(){panel.close();super.onDestroy();}
}
