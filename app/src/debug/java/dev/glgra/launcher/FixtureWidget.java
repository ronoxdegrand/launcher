package dev.glgra.launcher;
import android.app.PendingIntent;
import android.appwidget.AppWidgetProvider;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** Debug-only provider: tests real RemoteViews, configuration and PendingIntent delivery. */
public final class FixtureWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            RemoteViews view = new RemoteViews(context.getPackageName(), R.layout.fixture_widget);
            view.setOnClickPendingIntent(R.id.fixture_action, PendingIntent.getActivity(context, id,
                    new Intent(context, SettingsActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            manager.updateAppWidget(id, view);
        }
    }
}
