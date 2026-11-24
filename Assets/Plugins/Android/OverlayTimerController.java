package com.gwrhub.overlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * Zentrale Timer-Instanz (Countdown oder Stoppuhr) für den geöffneten Hub und die
 * Mini-Anzeige unter dem Button.
 * Kümmert sich um das Ticken auf dem Main-Thread, das Speichern der zuletzt gewählten Dauer
 * und das kurze Signal beim Ablauf. Die eigentliche Logik steckt in {@link OverlayTimer}.
 */
final class OverlayTimerController {

    interface Listener {
        /** Zustand oder Restzeit hat sich geändert, Anzeige aktualisieren. */
        void onTimerChanged();
    }

    static final long DEFAULT_DURATION_MS = 5L * 60L * 1000L;

    private static final String KEY_DURATION = "timer_duration_ms";
    private static final String KEY_MODE = "timer_mode";
    private static final long TICK_MS = 200L;
    private static final long BLINK_MS = 500L;
    /** So lange blinkt die 00:00-Anzeige, danach bleibt sie nur hervorgehoben. */
    private static final long BLINK_TOTAL_MS = 10_000L;
    /** Während der Timer läuft, kommt nach jeweils so viel Laufzeit ein kurzer Signalton. */
    private static final long BEEP_INTERVAL_MS = 30_000L;

    private final Context context;
    private final SharedPreferences prefs;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OverlayTimer timer;

    private long finishedAt;
    private boolean released;
    /** Zuletzt erreichter 30-s-Abschnitt der Laufzeit (für den Intervall-Ton). */
    private long lastBeepSlot;

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (released) return;
            long now = now();
            if (timer.tick(now)) {
                onFinished(now);
            } else if (timer.getState() == TimerState.RUNNING) {
                long slot = beepSlot(now);
                if (slot > lastBeepSlot) TimerBeep.play();
                lastBeepSlot = slot;
            }
            listener.onTimerChanged();
            scheduleNextTick();
        }
    };

    OverlayTimerController(Context context, SharedPreferences prefs, Listener listener) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.listener = listener;
        this.timer = new OverlayTimer(loadMode(), prefs.getLong(KEY_DURATION, DEFAULT_DURATION_MS));
    }

    long now() {
        return SystemClock.elapsedRealtime();
    }

    TimerState getState() {
        return timer.getState();
    }

    TimerMode getMode() {
        return timer.getMode();
    }

    long getRemainingMs() {
        return timer.getRemainingMs(now());
    }

    float getProgress() {
        return timer.getProgress(now());
    }

    String getDisplayText() {
        return timer.getDisplayText(now());
    }

    /** Für die Ablauf-Hervorhebung: true = "an"-Phase des Blinkens (oder Blinken vorbei). */
    boolean isFinishedBlinkOn() {
        if (timer.getState() != TimerState.FINISHED) return false;
        long since = now() - finishedAt;
        if (since >= BLINK_TOTAL_MS) return true;
        return (since / BLINK_MS) % 2 == 0;
    }

    void toggle() {
        long now = now();
        TimerState before = timer.getState();
        timer.toggle(now);
        checkFinishedDuringAction(before, now);
        changed();
    }

    void restart() {
        timer.restart(now());
        changed();
    }

    void reset() {
        timer.reset();
        changed();
    }

    void setDuration(long ms) {
        timer.setDuration(ms);
        saveDuration();
        changed();
    }

    void adjust(long deltaMs) {
        long now = now();
        TimerState before = timer.getState();
        timer.adjust(deltaMs, now);
        checkFinishedDuringAction(before, now);
        if (before == TimerState.IDLE || before == TimerState.FINISHED
                || timer.getState() == TimerState.IDLE) saveDuration();
        changed();
    }

    /** Countdown ↔ Stoppuhr; setzt den Timer zurück und merkt sich die Wahl. */
    void setMode(TimerMode mode) {
        if (mode == timer.getMode()) return;
        timer.setMode(mode);
        prefs.edit().putString(KEY_MODE, mode.name()).apply();
        changed();
    }

    /** Service wird beendet: Handler-Callbacks entfernen, keine Updates mehr. */
    void release() {
        released = true;
        handler.removeCallbacksAndMessages(null);
    }

    private void changed() {
        if (released) return;
        // Nach Start, Reset, ±-Anpassung usw. neu einrasten, damit kein Ton nachgeholt wird
        lastBeepSlot = beepSlot(now());
        handler.removeCallbacks(tickRunnable);
        listener.onTimerChanged();
        scheduleNextTick();
    }

    private void scheduleNextTick() {
        handler.removeCallbacks(tickRunnable);
        TimerState s = timer.getState();
        if (s == TimerState.RUNNING) {
            handler.postDelayed(tickRunnable, TICK_MS);
        } else if (s == TimerState.FINISHED && now() - finishedAt < BLINK_TOTAL_MS) {
            handler.postDelayed(tickRunnable, BLINK_MS / 2);
        }
        // IDLE / PAUSED / ausgeblinkt: nichts zu tun, kein Dauer-Polling
    }

    private TimerMode loadMode() {
        try {
            return TimerMode.valueOf(prefs.getString(KEY_MODE, TimerMode.COUNTDOWN.name()));
        } catch (IllegalArgumentException e) {
            return TimerMode.COUNTDOWN;
        }
    }

    private void saveDuration() {
        prefs.edit().putLong(KEY_DURATION, timer.getDurationMs()).apply();
    }

    private long beepSlot(long now) {
        return timer.getElapsedMs(now) / BEEP_INTERVAL_MS;
    }

    /** Pause/± kann einen eigentlich schon abgelaufenen Timer erst beenden: Signal nachholen. */
    private void checkFinishedDuringAction(TimerState before, long now) {
        if (before == TimerState.RUNNING && timer.getState() == TimerState.FINISHED) {
            onFinished(now);
        }
    }

    /** Ablauf: Blinken starten, kurzer Signalton (statt des Intervall-Tons) und Vibration. */
    private void onFinished(long now) {
        finishedAt = now;
        TimerBeep.play();
        vibrateFinished();
    }

    /** Kurzes, dezentes Vibrationssignal; kein Fokuswechsel, das Spiel läuft weiter. */
    @SuppressWarnings("deprecation")
    private void vibrateFinished() {
        try {
            Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(new long[]{0, 180, 120, 180}, -1));
            } else {
                v.vibrate(new long[]{0, 180, 120, 180}, -1);
            }
        } catch (Exception ignored) {
            // z. B. fehlende Berechtigung auf exotischen ROMs: Timer funktioniert trotzdem
        }
    }
}
