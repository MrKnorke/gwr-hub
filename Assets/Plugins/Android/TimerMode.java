package com.gwrhub.overlay;

/** Betriebsart des Overlay-Timers. */
public enum TimerMode {
    /** Zählt von der eingestellten Dauer auf 00:00 herunter. */
    COUNTDOWN,
    /** Zählt ab 00:00 hoch (Stoppuhr), stoppt spätestens bei 99:59. */
    STOPWATCH
}
