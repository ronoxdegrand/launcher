package dev.glgra.launcher;
import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
public final class FixtureConfigActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); setResult(RESULT_CANCELED);
        Button accept = new Button(this); accept.setText("Add test widget"); accept.setId(R.id.fixture_accept); accept.setSoundEffectsEnabled(false);
        accept.setOnClickListener(v -> {
            int id = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
            new FixtureWidget().onUpdate(this, AppWidgetManager.getInstance(this), new int[]{id});
            setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)); finish();
        }); setContentView(accept);
    }
}
