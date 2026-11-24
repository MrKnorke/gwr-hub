package com.gwrhub.overlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tasten-Shortcuts als kleine, verschiebbare Bubbles über dem Spiel (wie der GW-Button).
 * Antippen schickt die Taste(n) ans Spiel, Ziehen verschiebt die Bubble. Liste und
 * Positionen werden in den SharedPreferences gespeichert, die Positionen getrennt für Hoch-
 * und Querformat.
 *
 * Die Bubbles sind nicht fokussierbar, damit der Fokus (und damit der Tastendruck) beim
 * Spiel bleibt.
 */
final class ShortcutBubbles {

    /** Ein gespeicherter Shortcut. */
    static final class Shortcut {
        final String text;   // wie eingegeben, z. B. "strg+c"
        final String label;  // Tastenname, z. B. "Strg+C"
        final int[] keyCodes;
        /** Eigener Text der Bubble, z. B. "Held 1" ("" = Tastenname anzeigen). */
        String displayText = "";
        /** Ausgeblendet: Bubble erscheint nicht über dem Spiel, bleibt aber gespeichert. */
        boolean hidden;
        /** Aktuell angezeigte Position (an den Bildschirmrand angepasst). */
        int x;
        int y;
        /** Gespeicherte Positionen je Ausrichtung; has* = false, solange noch keine gesetzt ist. */
        int portX, portY, landX, landY;
        boolean hasPort, hasLand;
        TextView view;
        WindowManager.LayoutParams params;

        Shortcut(String text, KeyNames.Result parsed, int x, int y) {
            this.text = text;
            this.label = parsed.label;
            this.keyCodes = parsed.keyCodes;
            this.x = x;
            this.y = y;
        }

        /** Aktuelle Position als die der angegebenen Ausrichtung merken. */
        void store(boolean landscape) {
            if (landscape) {
                landX = x;
                landY = y;
                hasLand = true;
            } else {
                portX = x;
                portY = y;
                hasPort = true;
            }
        }

        /**
         * Gespeicherte Position der Ausrichtung übernehmen; fehlt sie, die der anderen
         * Ausrichtung (die Bubble bleibt dann, wo sie war).
         */
        void restore(boolean landscape) {
            boolean useLand = landscape ? hasLand : !hasPort;
            if (useLand && hasLand) {
                x = landX;
                y = landY;
            } else if (hasPort) {
                x = portX;
                y = portY;
            }
        }

        /** Text, den die Bubble zeigt. */
        String shownText() {
            return displayText.isEmpty() ? label : displayText;
        }
    }

    /** Höchstlänge des eigenen Bubble-Texts. */
    static final int MAX_DISPLAY_TEXT = 24;

    interface Host {
        int overlayType();

        int dp(float v);
    }

    private static final String KEY_SHORTCUTS = "key_shortcuts";
    private static final String KEY_ENABLED = "key_shortcuts_enabled";
    private static final int SIZE_DP = 44;
    private static final int COLOR_BUBBLE = 0xE07A1010;
    private static final int COLOR_PRESSED = 0xF0C9A23F;
    private static final int COLOR_GOLD = 0xFFC9A23F;
    private static final int COLOR_TEXT = 0xFFEDE6D6;

    private final Context context;
    private final WindowManager windowManager;
    private final SharedPreferences prefs;
    private final KeySender sender;
    private final Host host;
    private final List<Shortcut> shortcuts = new ArrayList<>();
    private boolean visible = false;
    /** Hauptschalter: aus = keine Bubble über dem Spiel, Shortcuts bleiben gespeichert. */
    private boolean enabled;

    ShortcutBubbles(Context context, WindowManager windowManager, SharedPreferences prefs,
                    KeySender sender, Host host) {
        this.context = context;
        this.windowManager = windowManager;
        this.prefs = prefs;
        this.sender = sender;
        this.host = host;
        this.enabled = prefs.getBoolean(KEY_ENABLED, true);
        load();
    }

    List<Shortcut> list() {
        return Collections.unmodifiableList(shortcuts);
    }

    /**
     * Legt einen neuen Shortcut an. Die Bubble erscheint neben dem GW-Button (versetzt, falls
     * schon andere da sind) und kann danach verschoben werden.
     *
     * @return Fehlermeldung oder null bei Erfolg
     */
    String add(String text, int nearX, int nearY) {
        KeyNames.Result parsed = KeyNames.parse(text);
        if (!parsed.isValid()) return parsed.error;
        for (Shortcut s : shortcuts) {
            if (s.label.equals(parsed.label)) return "\"" + parsed.label + "\" already exists";
        }
        int step = host.dp(SIZE_DP + 8);
        int n = shortcuts.size();
        // Unter/neben dem GW-Button stapeln: erst eine Spalte nach unten, dann daneben
        int perColumn = 5;
        int x = nearX + step * (1 + n / perColumn);
        int y = nearY + step * (n % perColumn);
        Shortcut s = new Shortcut(text.trim(), parsed, x, y);
        shortcuts.add(s);
        createView(s);
        s.store(isLandscape());
        save();
        return null;
    }

    void remove(Shortcut s) {
        if (!shortcuts.remove(s)) return;
        detach(s);
        save();
    }

    /**
     * Setzt den eigenen Text der Bubble (leer = Tastenname). Die Bubble wird so breit wie
     * der Text und bleibt dabei im sichtbaren Bereich.
     */
    void setDisplayText(Shortcut s, String text) {
        String t = text == null ? "" : text.trim();
        if (t.length() > MAX_DISPLAY_TEXT) t = t.substring(0, MAX_DISPLAY_TEXT);
        if (t.equals(s.displayText)) return;
        s.displayText = t;
        if (s.view != null) {
            applyText(s.view, s.shownText());
            // Neue Breite steht erst nach dem Layout fest; dann am Rand halten
            s.view.post(() -> {
                clamp(s);
                safeUpdate(s);
            });
        }
        save();
    }

    /** Einzelne Bubble aus-/einblenden, ohne den Shortcut zu löschen. */
    void setHidden(Shortcut s, boolean hidden) {
        if (s.hidden == hidden) return;
        s.hidden = hidden;
        if (hidden) detach(s);
        else if (visible && enabled) attach(s);
        save();
    }

    boolean isEnabled() {
        return enabled;
    }

    /** Alle Shortcuts ein-/ausschalten, ohne sie zu löschen (wird gespeichert). */
    void setEnabled(boolean on) {
        if (enabled == on) return;
        enabled = on;
        prefs.edit().putBoolean(KEY_ENABLED, on).apply();
        setVisible(visible);
    }

    /** Bubbles ein-/ausblenden (z. B. ausblenden, solange das Hub-Popup offen ist). */
    void setVisible(boolean show) {
        visible = show;
        for (Shortcut s : shortcuts) {
            if (show && enabled && !s.hidden) attach(s);
            else detach(s);
        }
    }

    /** Nach Drehen des Bildschirms: gespeicherte Position der neuen Ausrichtung übernehmen. */
    void onConfigurationChanged() {
        boolean land = isLandscape();
        for (Shortcut s : shortcuts) {
            if (s.params == null) continue;
            s.restore(land);
            clamp(s);
            safeUpdate(s);
        }
    }

    void release() {
        for (Shortcut s : shortcuts) detach(s);
    }

    // ------------------------------------------------------------------ Views

    private void createView(Shortcut s) {
        TextView v = new TextView(context);
        applyText(v, s.shownText());
        v.setTextColor(COLOR_TEXT);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        v.setSingleLine(true);
        v.setIncludeFontPadding(false);
        v.setMinWidth(host.dp(SIZE_DP));
        v.setPadding(host.dp(10), 0, host.dp(10), 0);
        v.setAlpha(0.85f);
        style(v, false);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, host.dp(SIZE_DP), host.overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        s.view = v;
        s.params = lp;
        s.restore(isLandscape());
        clamp(s);
        v.setOnTouchListener(new DragOrTap(s));
        if (visible && enabled && !s.hidden) attach(s);
    }

    /** Text setzen; kurze Texte groß (runde Bubble), längere kleiner (Pille, so breit wie der Text). */
    private static void applyText(TextView v, String text) {
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, text.length() <= 3 ? 15 : 12);
    }

    private void style(TextView v, boolean pressed) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(host.dp(SIZE_DP / 2f)); // Kreis bzw. Pille bei langen Namen
        bg.setColor(pressed ? COLOR_PRESSED : COLOR_BUBBLE);
        bg.setStroke(host.dp(2), pressed ? COLOR_TEXT : COLOR_GOLD);
        v.setBackground(bg);
        v.setTextColor(pressed ? 0xFF1C1A17 : COLOR_TEXT);
    }

    private void attach(Shortcut s) {
        if (s.view == null) createView(s);
        if (s.view.isAttachedToWindow() || s.view.getParent() != null) return;
        s.params.x = s.x;
        s.params.y = s.y;
        try {
            windowManager.addView(s.view, s.params);
        } catch (Exception ignored) {
        }
    }

    private void detach(Shortcut s) {
        if (s.view == null) return;
        try {
            if (s.view.isAttachedToWindow() || s.view.getParent() != null) {
                windowManager.removeView(s.view);
            }
        } catch (Exception ignored) {
        }
    }

    private boolean isLandscape() {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        return dm.widthPixels > dm.heightPixels;
    }

    private void clamp(Shortcut s) {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int w = s.view != null && s.view.getWidth() > 0 ? s.view.getWidth() : host.dp(SIZE_DP);
        int h = host.dp(SIZE_DP);
        s.x = Math.max(0, Math.min(s.x, dm.widthPixels - w));
        s.y = Math.max(0, Math.min(s.y, dm.heightPixels - h));
        if (s.params != null) {
            s.params.x = s.x;
            s.params.y = s.y;
        }
    }

    private void safeUpdate(Shortcut s) {
        try {
            if (s.view != null && s.view.isAttachedToWindow()) {
                windowManager.updateViewLayout(s.view, s.params);
            }
        } catch (Exception ignored) {
        }
    }

    /** Ziehen verschiebt die Bubble, kurzes Antippen sendet die Taste(n). */
    private final class DragOrTap implements View.OnTouchListener {
        private final Shortcut s;
        private final int touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        private int startX, startY;
        private float downX, downY;
        private boolean dragging;

        DragOrTap(Shortcut s) {
            this.s = s;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = s.x;
                    startY = s.y;
                    downX = e.getRawX();
                    downY = e.getRawY();
                    dragging = false;
                    style(s.view, true);
                    s.view.setAlpha(1f);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX;
                    float dy = e.getRawY() - downY;
                    if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                        dragging = true;
                        style(s.view, false);
                    }
                    if (dragging) {
                        s.x = startX + (int) dx;
                        s.y = startY + (int) dy;
                        clamp(s);
                        safeUpdate(s);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    style(s.view, false);
                    s.view.setAlpha(0.85f);
                    if (dragging) savePosition(s);
                    else sender.send(s.keyCodes);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    style(s.view, false);
                    s.view.setAlpha(0.85f);
                    if (dragging) savePosition(s);
                    return true;
            }
            return false;
        }
    }

    // ------------------------------------------------------------------ Speichern

    /** Nach dem Verschieben: Position nur für die aktuelle Ausrichtung speichern. */
    private void savePosition(Shortcut s) {
        s.store(isLandscape());
        save();
    }

    /*
     * Format je Shortcut: text, name (optional), hidden (nur wenn ausgeblendet), px/py (Hochformat) und lx/ly (Querformat),
     * jeweils nur wenn gesetzt. Ältere Versionen speicherten eine gemeinsame Position x/y;
     * sie gilt dann für beide Ausrichtungen.
     */
    private void load() {
        String json = prefs.getString(KEY_SHORTCUTS, null);
        if (json == null) return;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String text = o.optString("text", "");
                KeyNames.Result parsed = KeyNames.parse(text);
                if (!parsed.isValid()) continue;
                Shortcut s = new Shortcut(text, parsed, o.optInt("x", 0), o.optInt("y", 0));
                s.displayText = o.optString("name", "").trim();
                s.hidden = o.optBoolean("hidden", false);
                if (o.has("px") || o.has("lx")) {
                    if (o.has("px")) {
                        s.portX = o.optInt("px", 0);
                        s.portY = o.optInt("py", 0);
                        s.hasPort = true;
                    }
                    if (o.has("lx")) {
                        s.landX = o.optInt("lx", 0);
                        s.landY = o.optInt("ly", 0);
                        s.hasLand = true;
                    }
                } else {
                    s.store(false); // alte gemeinsame Position für beide übernehmen
                    s.store(true);
                }
                shortcuts.add(s);
                createView(s);
            }
        } catch (Exception ignored) {
            // beschädigte Daten -> ohne Shortcuts starten
        }
    }

    private void save() {
        JSONArray arr = new JSONArray();
        try {
            for (Shortcut s : shortcuts) {
                JSONObject o = new JSONObject();
                o.put("text", s.text);
                if (!s.displayText.isEmpty()) o.put("name", s.displayText);
                if (s.hidden) o.put("hidden", true);
                if (s.hasPort) {
                    o.put("px", s.portX);
                    o.put("py", s.portY);
                }
                if (s.hasLand) {
                    o.put("lx", s.landX);
                    o.put("ly", s.landY);
                }
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        prefs.edit().putString(KEY_SHORTCUTS, arr.toString()).apply();
    }
}
