package com.gwrhub.overlay;

/** Zustand des manuellen Overlay-Countdowns. */
public enum TimerState {
    /** Dauer eingestellt, aber nicht gestartet. */
    IDLE,
    /** Läuft gerade herunter. */
    RUNNING,
    /** Angehalten, Restzeit bleibt erhalten. */
    PAUSED,
    /** Bei 00:00 angekommen. */
    FINISHED
}
