package dev.glgra.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

final class LayoutStore {
    final SharedPreferences prefs;
    LayoutStore(Context context) { prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE); }
    GridModel load() {
        GridModel model = new GridModel();
        try {
            JSONArray items = new JSONArray(prefs.getString("layout", "[]"));
            for (int i = 0; i < items.length(); i++) {
                GridModel.Item item = decode(items.getJSONObject(i));
                if (model.fits(null, item.column, item.row, item.width, item.height)) model.items.add(item);
            }
        } catch (org.json.JSONException ignored) { /* Retain valid entries; never erase the stored source. */ }
        return model;
    }
    void save(GridModel model) {
        JSONArray items = new JSONArray();
        try {
            for (GridModel.Item item : model.items) {
                items.put(encode(item));
            }
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        prefs.edit().putString("layout", items.toString()).apply();
    }
    private GridModel.Item decode(JSONObject v) throws org.json.JSONException {
        GridModel.Item item = new GridModel.Item(v.getInt("id"), v.getString("component"), v.getInt("x"), v.getInt("y"), v.getInt("w"), v.getInt("h"));
        item.kind = v.optString("kind", item.kind); item.name = v.optString("name", "");
        item.shortcutId = v.optString("shortcut", ""); item.uid = v.optString("uid", item.uid);
        JSONArray children = v.optJSONArray("children");
        if (children != null) for (int j = 0; j < children.length(); j++) item.children.add(decode(children.getJSONObject(j)));
        return item;
    }
    private JSONObject encode(GridModel.Item item) throws org.json.JSONException {
        JSONArray children = new JSONArray(); for (GridModel.Item child : item.children) children.put(encode(child));
        return new JSONObject().put("id", item.widgetId).put("component", item.component).put("x", item.column).put("y", item.row)
                .put("w", item.width).put("h", item.height).put("kind", item.kind).put("name", item.name).put("shortcut", item.shortcutId)
                .put("uid", item.uid).put("children", children);
    }
}
