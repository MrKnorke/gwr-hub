package com.gwrhub.overlay;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * Eigenständiges Programm, das {@link KeySender} über Shizuku bzw. Root mit
 * {@code app_process} startet (läuft dann mit Shell-/Root-Rechten, nicht in der App).
 *
 * Warum nicht einfach {@code input keyevent}? Der Befehl schickt jede Taste ohne
 * Meta-Status, also mit ausgeschaltetem Num Lock. Ziffernblock-Tasten gelten dann als
 * Navigationstasten (Num 1 = Ende) und kommen im Spiel nicht als "1 (ZB)" an. Hier wird
 * dieselbe Systemfunktion wie bei {@code input} aufgerufen, aber mit Num Lock an, den
 * gedrückten Modifikatoren (Strg/Alt/Shift/Win) im Meta-Status und dem Scancode der Taste
 * auf einer deutschen Tastatur ({@link KeyNames#scanCode}).
 *
 * Protokoll über stdin/stdout: nach dem Start eine Zeile {@link #READY}; danach pro Zeile
 * Keycodes durch Leerzeichen getrennt (Kombination: alle drücken, rückwärts loslassen),
 * Antwort jeweils {@code KeySender.RC_MARKER + exitCode}, bei Fehlern vorher eine Zeile
 * "error: ...". "exit" beendet das Programm.
 */
public final class KeyInjector {

    static final String READY = "__GWRHUB_READY";

    /** InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH (wie bei {@code input}). */
    private static final int MODE_WAIT_FOR_FINISH = 2;

    private final Object inputManager;
    private final Method injectInputEvent;

    private KeyInjector() throws Exception {
        Class<?> cls;
        Object im;
        try {
            // Ab Android 14 liegt die Funktion in InputManagerGlobal
            cls = Class.forName("android.hardware.input.InputManagerGlobal");
            im = cls.getMethod("getInstance").invoke(null);
        } catch (ClassNotFoundException e) {
            cls = Class.forName("android.hardware.input.InputManager");
            im = cls.getMethod("getInstance").invoke(null);
        }
        inputManager = im;
        injectInputEvent = cls.getMethod("injectInputEvent", InputEvent.class, int.class);
    }

    public static void main(String[] args) {
        PrintStream out = System.out;
        KeyInjector injector;
        try {
            injector = new KeyInjector();
        } catch (Throwable t) {
            System.err.println("KeyInjector init failed: " + t);
            System.exit(1);
            return;
        }
        out.println(READY);
        out.flush();

        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.equals("exit")) break;
                int rc;
                try {
                    rc = injector.press(parseCodes(line)) ? 0 : 1;
                    if (rc != 0) out.println("error: key event was rejected");
                } catch (Throwable t) {
                    Throwable cause = t.getCause() != null ? t.getCause() : t;
                    out.println("error: " + cause);
                    rc = 1;
                }
                out.println(KeySender.RC_MARKER + rc);
                out.flush();
            }
        } catch (Exception ignored) {
            // stdin geschlossen -> beenden
        }
        System.exit(0);
    }

    static int[] parseCodes(String line) {
        String[] parts = line.trim().split("\\s+");
        int[] codes = new int[parts.length];
        for (int i = 0; i < parts.length; i++) codes[i] = Integer.parseInt(parts[i]);
        return codes;
    }

    /** Drückt alle Tasten der Reihe nach und lässt sie rückwärts wieder los. */
    private boolean press(int[] codes) throws Exception {
        long down = SystemClock.uptimeMillis();
        int meta = KeyEvent.META_NUM_LOCK_ON;
        boolean ok = true;
        for (int code : codes) {
            ok &= inject(KeyEvent.ACTION_DOWN, code, meta, down);
            meta |= modifierMeta(code);
        }
        for (int i = codes.length - 1; i >= 0; i--) {
            meta &= ~modifierMeta(codes[i]);
            ok &= inject(KeyEvent.ACTION_UP, codes[i], meta, down);
        }
        return ok;
    }

    private boolean inject(int action, int code, int meta, long downTime) throws Exception {
        // Scancode wie bei einer echten deutschen Tastatur, für Spiele, die die Tastenposition lesen
        KeyEvent ev = new KeyEvent(downTime, SystemClock.uptimeMillis(), action, code, 0, meta,
                KeyCharacterMap.VIRTUAL_KEYBOARD, KeyNames.scanCode(code), 0,
                InputDevice.SOURCE_KEYBOARD);
        return (Boolean) injectInputEvent.invoke(inputManager, ev, MODE_WAIT_FOR_FINISH);
    }

    static int modifierMeta(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_CTRL_LEFT: return KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_RIGHT_ON;
            case KeyEvent.KEYCODE_ALT_LEFT: return KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
            case KeyEvent.KEYCODE_ALT_RIGHT: return KeyEvent.META_ALT_ON | KeyEvent.META_ALT_RIGHT_ON;
            case KeyEvent.KEYCODE_SHIFT_LEFT: return KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_RIGHT_ON;
            case KeyEvent.KEYCODE_META_LEFT: return KeyEvent.META_META_ON | KeyEvent.META_META_LEFT_ON;
            case KeyEvent.KEYCODE_META_RIGHT: return KeyEvent.META_META_ON | KeyEvent.META_META_RIGHT_ON;
            default: return 0;
        }
    }
}
