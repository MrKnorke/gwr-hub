package com.gwrhub.overlay;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit-Tests für {@link OverlayTimer} und {@link TimerFormat}.
 * Bewusst ohne JUnit/Gradle, weil Unity die Android-Plugins selbst baut:
 * Start über Tests/run-timer-tests.ps1 (braucht nur ein JDK).
 * Liegt außerhalb von Assets/, damit Unity die Tests nicht in die APK kompiliert.
 */
public final class TimerTests {

    private static final long MIN = 60_000L;
    private static final long SEC = 1_000L;

    private interface Case {
        void run() throws Exception;
    }

    private static final List<String> names = new ArrayList<>();
    private static final List<Case> cases = new ArrayList<>();

    private static void test(String name, Case c) {
        names.add(name);
        cases.add(c);
    }

    public static void main(String[] args) {
        // ------------------------------------------------------------ OverlayTimer
        test("new timer is IDLE with full duration", () -> {
            OverlayTimer t = new OverlayTimer(5 * MIN);
            eq(TimerState.IDLE, t.getState());
            eq(5 * MIN, t.getDurationMs());
            eq(5 * MIN, t.getRemainingMs(12345));
            eq(1f, t.getProgress(0));
        });

        test("running timer counts down", () -> {
            OverlayTimer t = new OverlayTimer(MIN);
            t.start(1_000);
            eq(TimerState.RUNNING, t.getState());
            eq(MIN, t.getRemainingMs(1_000));
            eq(MIN - 15 * SEC, t.getRemainingMs(16_000));
            eq(0.75f, t.getProgress(16_000));
        });

        test("start while running is a no-op", () -> {
            OverlayTimer t = new OverlayTimer(MIN);
            t.start(0);
            t.start(30 * SEC);
            eq(30 * SEC, t.getRemainingMs(30 * SEC));
        });

        test("pause freezes remaining time, resume continues", () -> {
            OverlayTimer t = new OverlayTimer(MIN);
            t.start(0);
            t.pause(20 * SEC);
            eq(TimerState.PAUSED, t.getState());
            eq(40 * SEC, t.getRemainingMs(20 * SEC));
            eq(40 * SEC, t.getRemainingMs(10 * MIN)); // Zeit vergeht, Restzeit bleibt
            t.start(10 * MIN);
            eq(TimerState.RUNNING, t.getState());
            eq(30 * SEC, t.getRemainingMs(10 * MIN + 10 * SEC));
        });

        test("toggle alternates pause and resume", () -> {
            OverlayTimer t = new OverlayTimer(MIN);
            t.toggle(0);
            eq(TimerState.RUNNING, t.getState());
            t.toggle(5 * SEC);
            eq(TimerState.PAUSED, t.getState());
            t.toggle(100 * SEC);
            eq(TimerState.RUNNING, t.getState());
            eq(55 * SEC, t.getRemainingMs(100 * SEC));
        });

        test("pause on IDLE does nothing", () -> {
            OverlayTimer t = new OverlayTimer(MIN);
            t.pause(10);
            eq(TimerState.IDLE, t.getState());
            eq(MIN, t.getRemainingMs(10));
        });

        test("tick switches to FINISHED exactly once", () -> {
            OverlayTimer t = new OverlayTimer(10 * SEC);
            t.start(0);
            eq(false, t.tick(9_999));
            eq(TimerState.RUNNING, t.getState());
            eq(true, t.tick(10_000));
            eq(TimerState.FINISHED, t.getState());
            eq(false, t.tick(20_000));
            eq(0L, t.getRemainingMs(20_000));
            eq(0f, t.getProgress(20_000));
        });

        test("remaining never goes negative, even before tick", () -> {
            OverlayTimer t = new OverlayTimer(10 * SEC);
            t.start(0);
            eq(0L, t.getRemainingMs(60 * SEC));
            eq("00:00", TimerFormat.format(t.getRemainingMs(60 * SEC)));
        });

        test("pause after expiry finishes instead of pausing", () -> {
            OverlayTimer t = new OverlayTimer(10 * SEC);
            t.start(0);
            t.pause(11 * SEC);
            eq(TimerState.FINISHED, t.getState());
        });

        test("start after FINISHED begins a fresh run", () -> {
            OverlayTimer t = new OverlayTimer(10 * SEC);
            t.start(0);
            t.tick(10 * SEC);
            t.start(50 * SEC);
            eq(TimerState.RUNNING, t.getState());
            eq(10 * SEC, t.getRemainingMs(50 * SEC));
        });

        test("reset returns to IDLE with full duration", () -> {
            OverlayTimer t = new OverlayTimer(MIN);
            t.start(0);
            t.pause(30 * SEC);
            t.reset();
            eq(TimerState.IDLE, t.getState());
            eq(MIN, t.getRemainingMs(99 * MIN));
            t.start(0);
            t.tick(MIN);
            t.reset();
            eq(TimerState.IDLE, t.getState());
            eq(MIN, t.getRemainingMs(0));
        });

        test("restart begins again from full duration", () -> {
            OverlayTimer t = new OverlayTimer(MIN);
            t.start(0);
            t.restart(45 * SEC);
            eq(TimerState.RUNNING, t.getState());
            eq(MIN, t.getRemainingMs(45 * SEC));
        });

        test("zero, negative and oversized durations are clamped", () -> {
            eq(OverlayTimer.MIN_DURATION_MS, new OverlayTimer(0).getDurationMs());
            eq(OverlayTimer.MIN_DURATION_MS, new OverlayTimer(-5 * MIN).getDurationMs());
            eq(OverlayTimer.MAX_DURATION_MS, new OverlayTimer(500 * MIN).getDurationMs());
            OverlayTimer t = new OverlayTimer(MIN);
            t.setDuration(0);
            eq(OverlayTimer.MIN_DURATION_MS, t.getDurationMs());
            t.start(0);
            eq(true, t.tick(SEC)); // läuft trotzdem sauber ab
        });

        test("setDuration while running resets to IDLE", () -> {
            OverlayTimer t = new OverlayTimer(MIN);
            t.start(0);
            t.setDuration(2 * MIN);
            eq(TimerState.IDLE, t.getState());
            eq(2 * MIN, t.getRemainingMs(30 * SEC));
        });

        test("adjust in IDLE changes duration, clamped at 1s", () -> {
            OverlayTimer t = new OverlayTimer(MIN);
            t.adjust(10 * SEC, 0);
            eq(70 * SEC, t.getDurationMs());
            t.adjust(-10 * MIN, 0);
            eq(OverlayTimer.MIN_DURATION_MS, t.getDurationMs());
            eq(TimerState.IDLE, t.getState());
        });

        test("adjust while running extends remaining time and duration", () -> {
            OverlayTimer t = new OverlayTimer(MIN);
            t.start(0);
            t.adjust(MIN, 30 * SEC); // 30 s Rest + 60 s
            eq(TimerState.RUNNING, t.getState());
            eq(90 * SEC, t.getRemainingMs(30 * SEC));
            eq(90 * SEC, t.getDurationMs());
        });

        test("adjust while running never drops below 1s", () -> {
            OverlayTimer t = new OverlayTimer(MIN);
            t.start(0);
            t.adjust(-MIN, 30 * SEC);
            eq(TimerState.RUNNING, t.getState());
            eq(SEC, t.getRemainingMs(30 * SEC));
            eq(MIN, t.getDurationMs()); // Gesamtdauer schrumpft nicht
        });

        test("adjust while paused changes paused remaining", () -> {
            OverlayTimer t = new OverlayTimer(MIN);
            t.start(0);
            t.pause(50 * SEC);
            t.adjust(10 * SEC, 70 * SEC);
            eq(TimerState.PAUSED, t.getState());
            eq(20 * SEC, t.getRemainingMs(80 * SEC));
        });

        test("adjust after FINISHED sets new IDLE duration", () -> {
            OverlayTimer t = new OverlayTimer(MIN);
            t.start(0);
            t.tick(MIN);
            t.adjust(10 * SEC, 2 * MIN);
            eq(TimerState.IDLE, t.getState());
            eq(70 * SEC, t.getRemainingMs(2 * MIN));
        });

        test("adjust on expired-but-unticked timer acts like FINISHED", () -> {
            OverlayTimer t = new OverlayTimer(MIN);
            t.start(0);
            t.adjust(10 * SEC, 5 * MIN);
            eq(TimerState.IDLE, t.getState());
            eq(70 * SEC, t.getDurationMs());
        });

        // ------------------------------------------------------------ Stoppuhr
        test("stopwatch starts IDLE at 00:00", () -> {
            OverlayTimer t = new OverlayTimer(TimerMode.STOPWATCH, 5 * MIN);
            eq(TimerMode.STOPWATCH, t.getMode());
            eq(TimerState.IDLE, t.getState());
            eq(0L, t.getElapsedMs(10 * MIN));
            eq(0L, t.getDisplayMs(10 * MIN));
            eq("00:00", t.getDisplayText(10 * MIN));
        });

        test("stopwatch counts up and displays elapsed time", () -> {
            OverlayTimer t = new OverlayTimer(TimerMode.STOPWATCH, 5 * MIN);
            t.start(1_000);
            eq(TimerState.RUNNING, t.getState());
            eq(75 * SEC, t.getElapsedMs(76_000));
            eq("01:15", t.getDisplayText(76_000));
            eq("00:00", t.getDisplayText(1_900)); // 0,9 s → abgerundet
            eq(false, t.tick(10 * MIN)); // kein Ablauf bei eingestellter Countdown-Dauer
        });

        test("stopwatch pause/resume keeps elapsed time", () -> {
            OverlayTimer t = new OverlayTimer(TimerMode.STOPWATCH, MIN);
            t.start(0);
            t.pause(20 * SEC);
            eq(TimerState.PAUSED, t.getState());
            eq(20 * SEC, t.getElapsedMs(5 * MIN));
            t.toggle(5 * MIN);
            eq(TimerState.RUNNING, t.getState());
            eq(30 * SEC, t.getElapsedMs(5 * MIN + 10 * SEC));
        });

        test("stopwatch reset and restart go back to 00:00", () -> {
            OverlayTimer t = new OverlayTimer(TimerMode.STOPWATCH, MIN);
            t.start(0);
            t.reset();
            eq(TimerState.IDLE, t.getState());
            eq(0L, t.getElapsedMs(MIN));
            t.start(0);
            t.restart(3 * MIN);
            eq(TimerState.RUNNING, t.getState());
            eq(SEC, t.getElapsedMs(3 * MIN + SEC));
        });

        test("stopwatch stops at 99:59 and reports finish once", () -> {
            OverlayTimer t = new OverlayTimer(TimerMode.STOPWATCH, MIN);
            t.start(0);
            eq("99:59", t.getDisplayText(200 * MIN)); // vor dem Tick schon begrenzt
            eq(true, t.tick(OverlayTimer.MAX_DURATION_MS));
            eq(TimerState.FINISHED, t.getState());
            eq(false, t.tick(OverlayTimer.MAX_DURATION_MS + MIN));
            eq("99:59", t.getDisplayText(300 * MIN));
            t.start(0); // neuer Lauf nach Ablauf
            eq(0L, t.getElapsedMs(0));
        });

        test("stopwatch progress is the seconds hand", () -> {
            OverlayTimer t = new OverlayTimer(TimerMode.STOPWATCH, MIN);
            eq(0f, t.getProgress(0));
            t.start(0);
            eq(0.5f, t.getProgress(30 * SEC));
            eq(0.25f, t.getProgress(MIN + 15 * SEC));
        });

        test("adjust has no effect on stopwatch", () -> {
            OverlayTimer t = new OverlayTimer(TimerMode.STOPWATCH, MIN);
            t.start(0);
            t.adjust(10 * MIN, 30 * SEC);
            eq(30 * SEC, t.getElapsedMs(30 * SEC));
            eq(MIN, t.getDurationMs());
        });

        test("setDuration in stopwatch mode keeps it running", () -> {
            OverlayTimer t = new OverlayTimer(TimerMode.STOPWATCH, MIN);
            t.start(0);
            t.setDuration(10 * MIN);
            eq(TimerState.RUNNING, t.getState());
            eq(20 * SEC, t.getElapsedMs(20 * SEC));
            eq(10 * MIN, t.getDurationMs()); // für den Countdown gemerkt
        });

        test("switching mode resets and keeps countdown duration", () -> {
            OverlayTimer t = new OverlayTimer(2 * MIN);
            t.start(0);
            t.setMode(TimerMode.STOPWATCH);
            eq(TimerState.IDLE, t.getState());
            eq("00:00", t.getDisplayText(30 * SEC));
            t.start(0);
            t.setMode(TimerMode.COUNTDOWN);
            eq(TimerState.IDLE, t.getState());
            eq("02:00", t.getDisplayText(30 * SEC));
        });

        test("setMode with same mode or null changes nothing", () -> {
            OverlayTimer t = new OverlayTimer(TimerMode.STOPWATCH, MIN);
            t.start(0);
            t.setMode(TimerMode.STOPWATCH);
            t.setMode(null);
            eq(TimerState.RUNNING, t.getState());
            eq(5 * SEC, t.getElapsedMs(5 * SEC));
            eq(TimerMode.COUNTDOWN, new OverlayTimer(null, MIN).getMode());
        });

        test("countdown display text rounds up", () -> {
            OverlayTimer t = new OverlayTimer(MIN);
            t.start(0);
            eq("01:00", t.getDisplayText(1));
            eq("00:01", t.getDisplayText(MIN - 1));
            eq(MIN - 1, t.getDisplayMs(1));
        });

        // ------------------------------------------------------------ TimerFormat
        test("formatElapsed rounds down", () -> {
            eq("00:00", TimerFormat.formatElapsed(0));
            eq("00:00", TimerFormat.formatElapsed(999));
            eq("00:01", TimerFormat.formatElapsed(1_000));
            eq("00:59", TimerFormat.formatElapsed(59_999));
            eq("01:00", TimerFormat.formatElapsed(MIN));
            eq("00:00", TimerFormat.formatElapsed(-5));
            eq("99:59", TimerFormat.formatElapsed(1_000 * MIN));
        });

        test("format MM:SS basics", () -> {
            eq("05:00", TimerFormat.format(5 * MIN));
            eq("01:05", TimerFormat.format(65 * SEC));
            eq("00:09", TimerFormat.format(9 * SEC));
            eq("10:00", TimerFormat.format(10 * MIN));
        });

        test("format rounds partial seconds up", () -> {
            eq("00:01", TimerFormat.format(1));
            eq("00:01", TimerFormat.format(999));
            eq("00:01", TimerFormat.format(1_000));
            eq("00:02", TimerFormat.format(1_001));
            eq("01:00", TimerFormat.format(59_001));
        });

        test("format zero and negative as 00:00", () -> {
            eq("00:00", TimerFormat.format(0));
            eq("00:00", TimerFormat.format(-1));
            eq("00:00", TimerFormat.format(Long.MIN_VALUE));
        });

        test("format caps at 99:59", () -> {
            eq("99:59", TimerFormat.format(OverlayTimer.MAX_DURATION_MS));
            eq("99:59", TimerFormat.format(1_000 * MIN));
        });

        test("shortLabel for buttons", () -> {
            eq("30s", TimerFormat.shortLabel(30 * SEC));
            eq("1m", TimerFormat.shortLabel(MIN));
            eq("15m", TimerFormat.shortLabel(15 * MIN));
            eq("1m30", TimerFormat.shortLabel(90 * SEC));
            eq("1m05", TimerFormat.shortLabel(65 * SEC));
            eq("0s", TimerFormat.shortLabel(-5));
        });

        int failed = 0;
        for (int i = 0; i < cases.size(); i++) {
            try {
                cases.get(i).run();
                System.out.println("  ok    " + names.get(i));
            } catch (Throwable e) {
                failed++;
                System.out.println("  FAIL  " + names.get(i) + "\n        " + e.getMessage());
            }
        }
        System.out.println();
        System.out.println((cases.size() - failed) + "/" + cases.size() + " tests passed");
        if (failed > 0) System.exit(1);
    }

    private static void eq(Object expected, Object actual) {
        boolean same = expected instanceof Float && actual instanceof Float
                ? Math.abs((Float) expected - (Float) actual) < 1e-4f
                : expected.equals(actual);
        if (!same) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
