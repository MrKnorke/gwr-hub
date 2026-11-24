package com.gwrhub.overlay;

/** Formatierung der Timer-Anzeige, ohne Android-Abhängigkeiten. */
public final class TimerFormat {

    private TimerFormat() {
    }

    /**
     * Restzeit als {@code MM:SS}. Es wird auf volle Sekunden aufgerundet, damit
     * {@code 00:00} erst erscheint, wenn der Timer wirklich abgelaufen ist
     * (z. B. 0,2 s → 00:01). Negative Werte ergeben {@code 00:00}, mehr als 99:59 wird begrenzt.
     */
    public static String format(long remainingMs) {
        if (remainingMs <= 0) return "00:00";
        return mmss((remainingMs + 999L) / 1000L);
    }

    /**
     * Verstrichene Zeit (Stoppuhr) als {@code MM:SS}. Es wird auf volle Sekunden abgerundet,
     * wie bei einer normalen Stoppuhr (z. B. 0,9 s → 00:00, 1,0 s → 00:01).
     * Negative Werte ergeben {@code 00:00}, mehr als 99:59 wird begrenzt.
     */
    public static String formatElapsed(long elapsedMs) {
        if (elapsedMs <= 0) return "00:00";
        return mmss(elapsedMs / 1000L);
    }

    private static String mmss(long totalSec) {
        totalSec = Math.min(totalSec, 99L * 60L + 59L);
        return two(totalSec / 60L) + ":" + two(totalSec % 60L);
    }

    /** Kurzes Label für Preset-/Anpass-Buttons, z. B. 30000 → "30s", 300000 → "5m", 90000 → "1m30". */
    public static String shortLabel(long ms) {
        long totalSec = Math.max(0L, ms / 1000L);
        long min = totalSec / 60L;
        long sec = totalSec % 60L;
        if (min == 0) return sec + "s";
        if (sec == 0) return min + "m";
        return min + "m" + two(sec);
    }

    private static String two(long v) {
        return v < 10 ? "0" + v : Long.toString(v);
    }
}
