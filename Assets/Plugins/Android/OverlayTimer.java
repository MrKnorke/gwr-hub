package com.gwrhub.overlay;

/**
 * Reine Timer-Logik ohne Android-Abhängigkeiten (dadurch direkt unit-testbar).
 * Unterstützt Countdown und Stoppuhr ({@link TimerMode}).
 * Die aktuelle Zeit wird als monotone Millisekunden übergeben
 * (in der App {@code SystemClock.elapsedRealtime()}, in Tests ein beliebiger Wert).
 * Läuft rein app-seitig: kein Zugriff auf Spielspeicher oder Spiel-Events.
 */
public final class OverlayTimer {

    public static final long MIN_DURATION_MS = 1_000L;
    /** 99:59, damit die Anzeige immer ins kompakte MM:SS-Format passt. */
    public static final long MAX_DURATION_MS = (99L * 60L + 59L) * 1_000L;

    private TimerMode mode;
    private TimerState state = TimerState.IDLE;
    /** Eingestellte Countdown-Dauer (bleibt auch im Stoppuhr-Modus erhalten). */
    private long durationMs;
    /** Bereits verstrichene Zeit vor dem aktuellen Laufabschnitt. */
    private long accumulatedMs;
    /** Beginn des aktuellen Laufabschnitts (gültig bei RUNNING). */
    private long runStartMs;

    public OverlayTimer(long durationMs) {
        this(TimerMode.COUNTDOWN, durationMs);
    }

    public OverlayTimer(TimerMode mode, long durationMs) {
        this.mode = mode != null ? mode : TimerMode.COUNTDOWN;
        this.durationMs = clampDuration(durationMs);
    }

    public static long clampDuration(long ms) {
        return Math.max(MIN_DURATION_MS, Math.min(ms, MAX_DURATION_MS));
    }

    public TimerMode getMode() {
        return mode;
    }

    public TimerState getState() {
        return state;
    }

    public long getDurationMs() {
        return durationMs;
    }

    /** Obergrenze der verstrichenen Zeit: Countdown-Dauer bzw. 99:59 bei der Stoppuhr. */
    private long capMs() {
        return mode == TimerMode.COUNTDOWN ? durationMs : MAX_DURATION_MS;
    }

    /** Verstrichene Zeit in ms, zwischen 0 und der Obergrenze. */
    public long getElapsedMs(long now) {
        switch (state) {
            case RUNNING:
                return Math.max(0L, Math.min(capMs(), accumulatedMs + (now - runStartMs)));
            case FINISHED:
                return capMs();
            default:
                return accumulatedMs;
        }
    }

    /** Countdown-Restzeit in ms, nie negativ. Im Stoppuhr-Modus die Zeit bis 99:59. */
    public long getRemainingMs(long now) {
        return Math.max(0L, capMs() - getElapsedMs(now));
    }

    /** Was angezeigt wird: Restzeit beim Countdown, verstrichene Zeit bei der Stoppuhr. */
    public long getDisplayMs(long now) {
        return mode == TimerMode.COUNTDOWN ? getRemainingMs(now) : getElapsedMs(now);
    }

    /** Anzeige im passenden Format (Countdown rundet auf, Stoppuhr rundet ab). */
    public String getDisplayText(long now) {
        return mode == TimerMode.COUNTDOWN
                ? TimerFormat.format(getRemainingMs(now))
                : TimerFormat.formatElapsed(getElapsedMs(now));
    }

    /**
     * Fortschritt für den dezenten Indikator (0.0 … 1.0).
     * Countdown: Anteil der verbleibenden Zeit (1.0 = voll).
     * Stoppuhr: Sekundenzeiger, Anteil der laufenden Minute.
     */
    public float getProgress(long now) {
        if (mode == TimerMode.STOPWATCH) {
            if (state == TimerState.FINISHED) return 1f;
            return (getElapsedMs(now) % 60_000L) / 60_000f;
        }
        float p = getRemainingMs(now) / (float) durationMs;
        return Math.max(0f, Math.min(1f, p));
    }

    /** Wechselt die Betriebsart; ein laufender oder pausierter Timer wird dabei zurückgesetzt. */
    public void setMode(TimerMode newMode) {
        if (newMode == null || newMode == mode) return;
        mode = newMode;
        reset();
    }

    /**
     * Setzt eine neue Countdown-Dauer. Ungültige Werte (0, negativ, zu groß) werden auf den
     * gültigen Bereich begrenzt. Im Countdown-Modus geht der Timer auf IDLE; eine laufende
     * Stoppuhr bleibt unberührt, die Dauer wird nur für später gemerkt.
     */
    public void setDuration(long ms) {
        durationMs = clampDuration(ms);
        if (mode == TimerMode.COUNTDOWN) reset();
    }

    /** Startet bzw. setzt fort. Bei FINISHED beginnt ein neuer Durchlauf. */
    public void start(long now) {
        if (state == TimerState.RUNNING) return;
        if (state == TimerState.FINISHED || accumulatedMs >= capMs()) accumulatedMs = 0L;
        runStartMs = now;
        state = TimerState.RUNNING;
    }

    public void pause(long now) {
        if (state != TimerState.RUNNING) return;
        if (tick(now)) return; // war in Wahrheit schon abgelaufen
        accumulatedMs = getElapsedMs(now);
        state = TimerState.PAUSED;
    }

    /** Läuft → Pause, sonst → Start/Fortsetzen. */
    public void toggle(long now) {
        if (state == TimerState.RUNNING) pause(now);
        else start(now);
    }

    /** Neustart von vorn (volle Dauer bzw. 00:00), unabhängig vom aktuellen Zustand. */
    public void restart(long now) {
        reset();
        start(now);
    }

    /** Zurück auf IDLE: volle Dauer bzw. 00:00. */
    public void reset() {
        state = TimerState.IDLE;
        accumulatedMs = 0L;
        runStartMs = 0L;
    }

    /**
     * Ändert die Countdown-Zeit um {@code deltaMs} (im Stoppuhr-Modus ohne Wirkung).
     * IDLE/FINISHED: verändert die eingestellte Dauer (Timer geht auf IDLE).
     * RUNNING/PAUSED: verlängert/verkürzt die laufende Restzeit (mind. 1 s, max. 99:59);
     * die Gesamtdauer wächst bei Bedarf mit, damit der Fortschritt stimmig bleibt.
     */
    public void adjust(long deltaMs, long now) {
        if (mode != TimerMode.COUNTDOWN) return;
        if (state == TimerState.IDLE || state == TimerState.FINISHED
                || (state == TimerState.RUNNING && tick(now))) {
            setDuration(durationMs + deltaMs);
            return;
        }
        long remaining = clampDuration(getRemainingMs(now) + deltaMs);
        if (remaining > durationMs) durationMs = remaining;
        accumulatedMs = durationMs - remaining;
        if (state == TimerState.RUNNING) runStartMs = now;
    }

    /**
     * Muss regelmäßig aufgerufen werden, solange der Timer läuft.
     * @return true genau dann, wenn der Timer bei diesem Aufruf auf FINISHED gewechselt ist
     *         (Countdown bei 00:00, Stoppuhr bei 99:59).
     */
    public boolean tick(long now) {
        if (state == TimerState.RUNNING && accumulatedMs + (now - runStartMs) >= capMs()) {
            state = TimerState.FINISHED;
            accumulatedMs = capMs();
            return true;
        }
        return false;
    }
}
