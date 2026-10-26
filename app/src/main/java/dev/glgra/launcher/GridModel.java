package dev.glgra.launcher;

import java.util.ArrayList;
import java.util.List;

/** Grid coordinates are independent of pixels. The canvas grows by content, never by pages. */
public final class GridModel {
    public static final int ROWS = 6;
    public static final class Item {
        public final int widgetId;
        public final String component;
        public int column, row, width, height;
        public String kind, name = "", shortcutId = "";
        public String uid = java.util.UUID.randomUUID().toString();
        public final List<Item> children = new ArrayList<>();
        public Item(int widgetId, String component, int column, int row, int width, int height) {
            this.widgetId = widgetId; this.component = component; this.column = column;
            this.row = row; this.width = width; this.height = height;
            this.kind = widgetId >= 0 ? "widget" : "app";
        }
        public boolean isWidget() { return widgetId >= 0; }
        public boolean isFolder() { return kind.equals("folder"); }
        public boolean isNotification() { return kind.equals("notifications"); }
        public boolean isApp() { return kind.equals("app"); }
        public String identity() {
            android.content.ComponentName c = android.content.ComponentName.unflattenFromString(component);
            return kind.equals("shortcut") ? component + ":" + shortcutId : isApp() && c != null ? c.getPackageName() : uid;
        }
    }
    public final List<Item> items = new ArrayList<>();
    public boolean fits(Item ignored, int column, int row, int width, int height) {
        if (column < 0 || row < 0 || width < 1 || width > 64 || height < 1 || row + height > ROWS) return false;
        for (Item other : items) {
            if (other != ignored && column < other.column + other.width && column + width > other.column
                    && row < other.row + other.height && row + height > other.row) return false;
        }
        return true;
    }
    public void place(Item item, int startColumn) {
        if (item.width < 1 || item.width > 64 || item.height < 1 || item.height > ROWS) throw new IllegalArgumentException("Invalid item size");
        for (int column = Math.max(0, startColumn); ; column++) {
            for (int row = 0; row <= ROWS - item.height; row++) {
                if (fits(item, column, row, item.width, item.height)) {
                    item.column = column; item.row = row;
                    if (!items.contains(item)) items.add(item);
                    return;
                }
            }
        }
    }
    public int columns() {
        int end = 4;
        for (Item item : items) end = Math.max(end, item.column + item.width);
        return end + 4; // Empty workspace at the right permits placing more content.
    }
    public Item widget(int id) {
        for (Item item : items) if (item.widgetId == id) return item;
        return null;
    }
    public Item notification() { for (Item item : items) if (item.isNotification()) return item; return null; }
    public Item find(String identity) {
        for (Item item : items) {
            if (item.identity().equals(identity)) return item;
            for (Item child : item.children) if (child.identity().equals(identity)) return child;
        }
        return null;
    }
    public void detach(Item item) {
        items.remove(item);
        for (Item folder : new ArrayList<>(items)) if (folder.isFolder() && folder.children.remove(item)) {
            if (folder.children.size() == 1) {
                Item remaining = folder.children.get(0); remaining.column = folder.column; remaining.row = folder.row;
                remaining.width = remaining.height = 1; items.remove(folder); items.add(remaining);
            } else if (folder.children.isEmpty()) items.remove(folder);
        }
    }
    public void move(Item item, int column, int row, int width, int height) {
        if (item.isNotification()) return;
        if (width < 1 || width > 64 || height < 1 || height > 6) throw new IllegalArgumentException("Invalid size");
        Item reserved = notification();
        column = Math.max(reserved == null ? 0 : reserved.width, column); row = Math.max(0, Math.min(6 - height, row));
        detach(item);
        List<Item> displaced = new ArrayList<>();
        for (Item other : items) if (column < other.column + other.width && column + width > other.column
                && row < other.row + other.height && row + height > other.row) displaced.add(other);
        items.removeAll(displaced); item.column = column; item.row = row; item.width = width; item.height = height; items.add(item);
        for (Item other : displaced) place(other, other.column);
    }
    public void reserveNotifications(boolean enabled, int width) {
        Item old = notification(); if (old != null) items.remove(old);
        if (!enabled) return;
        Item reserved = new Item(-2, "builtin:notifications", 0, 0, Math.max(2, Math.min(4, width)), 6);
        reserved.kind = "notifications"; if (old != null) reserved.uid = old.uid;
        List<Item> displaced = new ArrayList<>();
        for (Item item : items) if (item.column < reserved.width) displaced.add(item);
        items.removeAll(displaced); items.add(0, reserved);
        for (Item item : displaced) place(item, Math.max(reserved.width, item.column));
    }
    public void merge(Item dragged, Item target) {
        if (dragged == target || !dragged.isApp() || (!target.isApp() && !target.isFolder())) return;
        if (target.isFolder() && target.children.contains(dragged)) return;
        detach(dragged);
        if (target.isFolder()) { target.children.add(dragged); return; }
        Item folder = new Item(-3, "folder:" + java.util.UUID.randomUUID(), target.column, target.row, 2, 2);
        folder.kind = "folder"; folder.name = "Folder";
        detach(target); folder.children.add(target); folder.children.add(dragged);
        move(folder, folder.column, folder.row, 2, 2);
    }
}
