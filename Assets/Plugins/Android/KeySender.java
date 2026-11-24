package com.gwrhub.overlay;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

/**
 * Schickt Tastendrücke an die App im Vordergrund (das Spiel).
 *
 * Normale Apps dürfen unter Android keine Tasten in fremde Apps einspeisen (dafür braucht es
 * die System-Berechtigung INJECT_EVENTS). Das geht nur mit Shell- oder Root-Rechten:
 *  1. über Shizuku (ohne Root, Shizuku-App muss laufen und GWR Hub erlauben), sonst
 *  2. über Root ({@code su}).
 * In dieser Shell läuft bevorzugt {@link KeyInjector} (per {@code app_process} aus der APK),
 * der Tasten mit eingeschaltetem Num Lock schickt, damit Ziffernblock-Tasten als Ziffern
 * ankommen. Startet er nicht, wird auf den Shell-Befehl {@code input keyevent} zurückgefallen.
 * Die Shell bleibt geöffnet, damit schnelle Tastendrücke nicht jedes Mal einen neuen Prozess
 * brauchen. Nach jedem Befehl wird der Exit-Code samt Ausgabe gelesen, damit Fehler sichtbar
 * werden. Alles läuft auf eigenen Threads; Rückmeldungen kommen auf dem UI-Thread.
 */
final class KeySender {

    enum Status {
        /** Shizuku läuft und GWR Hub hat die Berechtigung. */
        SHIZUKU_READY,
        /** Shizuku läuft, GWR Hub ist aber noch nicht erlaubt. */
        SHIZUKU_NEEDS_PERMISSION,
        /** Shizuku läuft, ist aber zu alt (vor v11). */
        SHIZUKU_OUTDATED,
        /** Shizuku-App ist installiert, der Dienst läuft aber nicht (nach Neustart neu starten). */
        SHIZUKU_NOT_RUNNING,
        /** Kein laufendes Shizuku, aber ein su-Binary ist vorhanden. */
        ROOT,
        /** Weder Shizuku installiert noch Root. */
        UNAVAILABLE
    }

    interface Listener {
        /** Status geändert (z. B. Shizuku gestartet oder Berechtigung erteilt). */
        void onStatusChanged();

        /** Senden fehlgeschlagen; Meldung für den Nutzer. */
        void onSendFailed(String message);
    }

    /** Ergebnis eines einzelnen Sendevorgangs (z. B. für den Test-Button). */
    interface ResultCallback {
        /**
         * @param ok      Taste wurde erfolgreich eingespeist
         * @param message Fehlermeldung (nur bei !ok)
         * @param millis  Dauer vom Antippen bis zur Bestätigung durch die Shell
         */
        void onResult(boolean ok, String message, long millis);
    }

    static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    /** Harmlose Taste für den Test (linke Umschalttaste). */
    static final int TEST_KEY_CODE = 59;

    private static final String TAG = "GWRHub.Keys";
    private static final int SHIZUKU_REQUEST_CODE = 4712;
    private static final long RESULT_TIMEOUT_MS = 5000;
    static final String RC_MARKER = "__GWRHUB_RC:";
    /** Wartezeit auf den Start von {@link KeyInjector} (bei Root ggf. inkl. Freigabe-Dialog). */
    private static final long INJECTOR_START_SHIZUKU_MS = 5000;
    private static final long INJECTOR_START_ROOT_MS = 20000;
    private static final String[] SU_PATHS = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/sbin/su", "/vendor/bin/su", "/debug_ramdisk/su"
    };

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    /** Nur auf dem Worker-Thread verwendet. */
    private Shell shell;
    /** Root wurde verweigert -> nicht bei jedem Tastendruck erneut fragen. */
    private volatile boolean rootDenied;
    /** {@link KeyInjector} ließ sich nicht starten -> nur noch {@code input keyevent}. */
    private volatile boolean injectorUnavailable;
    /** Aktuell genutzter Weg, für die Diagnose. */
    private volatile String injectionMode = "not started yet";

    private final Shizuku.OnBinderReceivedListener binderReceived = this::notifyStatus;
    private final Shizuku.OnBinderDeadListener binderDead = this::notifyStatus;
    private final Shizuku.OnRequestPermissionResultListener permissionResult =
            (requestCode, grantResult) -> {
                if (requestCode == SHIZUKU_REQUEST_CODE) notifyStatus();
            };

    KeySender(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceived);
            Shizuku.addBinderDeadListener(binderDead);
            Shizuku.addRequestPermissionResultListener(permissionResult);
        } catch (Throwable t) {
            // Shizuku-Bibliothek fehlt/inkompatibel -> nur Root
            Log.w(TAG, "Shizuku library unavailable", t);
        }
    }

    void release() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceived);
            Shizuku.removeBinderDeadListener(binderDead);
            Shizuku.removeRequestPermissionResultListener(permissionResult);
        } catch (Throwable ignored) {
        }
        worker.execute(this::closeShell);
        worker.shutdown();
    }

    Status getStatus() {
        if (shizukuRunning()) {
            if (shizukuOutdated()) return Status.SHIZUKU_OUTDATED;
            return shizukuGranted() ? Status.SHIZUKU_READY : Status.SHIZUKU_NEEDS_PERMISSION;
        }
        if (hasSuBinary() && !rootDenied) return Status.ROOT;
        if (isShizukuInstalled()) return Status.SHIZUKU_NOT_RUNNING;
        return Status.UNAVAILABLE;
    }

    boolean isShizukuInstalled() {
        return shizukuAppVersion() != null;
    }

    /** Kurzer technischer Steckbrief für die Fehlersuche (wird im Keys-Tab angezeigt). */
    String diagnostics() {
        StringBuilder sb = new StringBuilder();
        String app = shizukuAppVersion();
        sb.append("Shizuku app: ").append(app != null ? "v" + app : "not installed");

        sb.append("\nShizuku service: ");
        if (shizukuRunning()) {
            sb.append("running");
            try {
                int uid = Shizuku.getUid();
                sb.append(uid == 0 ? " (root)" : uid == 2000 ? " (adb)" : " (uid " + uid + ")");
                sb.append(", API ").append(Shizuku.getVersion());
            } catch (Throwable ignored) {
            }
            sb.append(shizukuOutdated() ? ", too old" : "");
            sb.append(", permission ").append(shizukuGranted() ? "granted" : "not granted");
        } else {
            sb.append("not running");
        }

        sb.append("\nRoot (su): ").append(hasSuBinary()
                ? (rootDenied ? "found, denied" : "found") : "not found");
        sb.append("\nKey injection: ").append(injectionMode);
        sb.append("\nAndroid ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")");
        return sb.toString();
    }

    /** Fragt bei Shizuku nach der Berechtigung (Shizuku zeigt dafür einen eigenen Dialog). */
    void requestShizukuPermission() {
        try {
            if (shizukuRunning() && !shizukuGranted()) Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
        } catch (Throwable t) {
            listener.onSendFailed("Shizuku permission request failed: " + t.getMessage());
        }
    }

    /** Drückt die Tasten (bei mehreren gleichzeitig, z. B. Strg+C); Fehler kommen als Toast. */
    void send(int[] keyCodes) {
        send(keyCodes, null);
    }

    /**
     * Drückt die Tasten. Mit {@code callback} kommt das Ergebnis (Erfolg oder Fehler) dorthin,
     * sonst werden nur Fehler über {@link Listener#onSendFailed} gemeldet.
     */
    void send(int[] keyCodes, ResultCallback callback) {
        if (keyCodes == null || keyCodes.length == 0) return;
        final Pending p = new Pending(keyCodes, buildCommand(keyCodes, Build.VERSION.SDK_INT), callback);
        worker.execute(() -> runCommand(p));
    }

    /** Schickt eine harmlose Taste und meldet, ob das Einspeisen geklappt hat. */
    void sendTest(ResultCallback callback) {
        send(new int[]{TEST_KEY_CODE}, callback);
    }

    /** Shell-Befehl für die Tasten; Kombinationen gibt es als eigenen Befehl erst ab Android 12. */
    static String buildCommand(int[] keyCodes, int sdkInt) {
        StringBuilder sb = new StringBuilder();
        if (keyCodes.length == 1) {
            sb.append("input keyevent ").append(keyCodes[0]);
        } else if (sdkInt >= 31) {
            sb.append("input keycombination");
            for (int c : keyCodes) sb.append(' ').append(c);
        } else {
            // Ältere Android-Versionen: Tasten nacheinander (Modifikatoren wirken dann nicht)
            sb.append("input keyevent");
            for (int c : keyCodes) sb.append(' ').append(c);
        }
        return sb.toString();
    }

    /**
     * Wertet Exit-Code und Ausgabe von {@code input} aus. Ältere {@code input}-Versionen
     * melden Fehler (z. B. unbekannter Befehl) nur als Text und beenden sich trotzdem mit 0.
     *
     * @return null bei Erfolg, sonst eine Fehlermeldung
     */
    static String evaluateResult(int exitCode, String output) {
        String out = output == null ? "" : output.trim();
        String lower = out.toLowerCase(Locale.ROOT);
        boolean errorText = lower.contains("error") || lower.contains("exception")
                || lower.contains("unknown") || lower.contains("not found")
                || lower.contains("permission denied") || lower.contains("usage:");
        if (exitCode == 0 && !errorText) return null;
        if (out.isEmpty()) return "input failed (exit code " + exitCode + ")";
        // Erste aussagekräftige Zeile reicht für den Toast
        String first = out.split("\n", 2)[0].trim();
        if (first.length() > 160) first = first.substring(0, 160) + "…";
        return "input failed: " + first;
    }

    // ------------------------------------------------------------------ Worker-Thread

    private void runCommand(Pending p) {
        Status status = getStatus();
        switch (status) {
            case SHIZUKU_NEEDS_PERMISSION:
                deliver(p, false, "Allow GWR Hub in Shizuku first (Keys tab → Allow in Shizuku)");
                return;
            case SHIZUKU_OUTDATED:
                deliver(p, false, "Shizuku is too old. Please update Shizuku.");
                return;
            case SHIZUKU_NOT_RUNNING:
                deliver(p, false, "Shizuku isn't running. Open Shizuku and tap Start.");
                return;
            case UNAVAILABLE:
                deliver(p, false, rootDenied
                        ? "Root access was denied. Start Shizuku or allow root for GWR Hub."
                        : "Sending keys needs Shizuku or root. See the Keys tab.");
                return;
            default:
                break;
        }
        boolean useShizuku = status == Status.SHIZUKU_READY;

        // Ein Versuch mit bestehender Shell, bei Schreibfehler einmal mit neuer Shell
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            Shell s = null;
            try {
                s = ensureShell(useShizuku);
                s.execute(p);
                main.postDelayed(() -> deliver(p, false, "No answer from "
                        + (useShizuku ? "Shizuku" : "root shell") + " after "
                        + (RESULT_TIMEOUT_MS / 1000) + " s"), RESULT_TIMEOUT_MS);
                return;
            } catch (Exception e) {
                last = e;
                Log.w(TAG, "Shell write failed (attempt " + (attempt + 1) + ")", e);
                // Wurde der Auftrag schon von der sterbenden Shell beantwortet, nicht wiederholen
                if (s != null && !s.pending.remove(p)) return;
                closeShell();
            }
        }
        if (!useShizuku) {
            rootDenied = true;
            notifyStatus();
            deliver(p, false, "Root access was denied or failed");
        } else {
            deliver(p, false, "Couldn't send key via Shizuku: "
                    + (last != null ? last.getMessage() : "unknown error"));
        }
    }

    private Shell ensureShell(boolean useShizuku) throws Exception {
        if (shell != null && shell.shizuku == useShizuku && shell.isAlive()) return shell;
        closeShell();
        if (!injectorUnavailable) {
            Shell s = openShell(useShizuku, true);
            if (s.awaitInjector(useShizuku ? INJECTOR_START_SHIZUKU_MS : INJECTOR_START_ROOT_MS)) {
                injectionMode = "direct (Num Lock on)";
                shell = s;
                return s;
            }
            String err = s.stderrText();
            boolean died = !s.isAlive();
            s.close();
            // su ohne Meldung beendet -> Root verweigert, nicht der Injector kaputt
            if (!useShizuku && died && err.isEmpty()) throw new IOException("su exited");
            Log.w(TAG, "KeyInjector unavailable, falling back to 'input': " + err);
            injectorUnavailable = true;
        }
        Shell s = openShell(useShizuku, false);
        injectionMode = "input command (Num Lock off, numpad keys may not work)";
        shell = s;
        return s;
    }

    /** Startet sh (Shizuku) bzw. su; mit {@code injector} läuft darin KeyInjector. */
    private Shell openShell(boolean useShizuku, boolean injector) throws Exception {
        Process p = useShizuku ? newShizukuProcess() : Runtime.getRuntime().exec("su");
        Shell s = new Shell(p, useShizuku, injector);
        if (!useShizuku) {
            // su fragt ggf. per Dialog nach; wird sofort verweigert, beendet sich der Prozess
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {
            }
            if (!s.isAlive()) {
                s.close();
                throw new IOException("su exited");
            }
        }
        if (injector) {
            // Die APK ist für Shell und Root lesbar; app_process startet daraus KeyInjector.main
            String apk = context.getApplicationInfo().sourceDir;
            s.writeLine("CLASSPATH=\"" + apk + "\" exec app_process /system/bin "
                    + KeyInjector.class.getName());
        }
        return s;
    }

    private void closeShell() {
        if (shell != null) shell.close();
        shell = null;
    }

    /**
     * Shizuku.newProcess ist ab API 13 nicht mehr öffentlich, funktioniert aber weiterhin
     * (offizieller Weg für den Umstieg von "su"); daher per Reflection.
     */
    private static Process newShizukuProcess() throws Exception {
        Method m = Shizuku.class.getDeclaredMethod(
                "newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (Process) m.invoke(null, new String[]{"sh"}, null, null);
    }

    // ------------------------------------------------------------------ Shell

    /** Ein Sendeauftrag; wird genau einmal beantwortet (Ergebnis, Fehler oder Timeout). */
    private static final class Pending {
        /** Zeile für KeyInjector: Keycodes durch Leerzeichen getrennt. */
        final String injectorLine;
        /** Fallback-Befehl für die Shell ({@code input ...}). */
        final String command;
        final ResultCallback callback;
        final long startedAt = SystemClock.elapsedRealtime();
        final AtomicBoolean done = new AtomicBoolean();

        Pending(int[] keyCodes, String command, ResultCallback callback) {
            StringBuilder sb = new StringBuilder();
            for (int c : keyCodes) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(c);
            }
            this.injectorLine = sb.toString();
            this.command = command;
            this.callback = callback;
        }
    }

    /**
     * Offene Shell (Shizuku oder su), entweder mit laufendem {@link KeyInjector} oder als
     * normale Shell für {@code input}. Aufträge laufen der Reihe nach; nach jedem kommt eine
     * Markierung mit dem Exit-Code, an der der Lese-Thread erkennt, welcher Auftrag fertig ist
     * (Warteschlange in derselben Reihenfolge).
     */
    private final class Shell {
        final Process process;
        final OutputStream in;
        final boolean shizuku;
        final boolean injector;
        final ConcurrentLinkedQueue<Pending> pending = new ConcurrentLinkedQueue<>();
        private final StringBuilder stderr = new StringBuilder();
        private final CountDownLatch started = new CountDownLatch(1);
        private volatile boolean injectorReady;
        private volatile boolean anySuccess;

        Shell(Process process, boolean shizuku, boolean injector) {
            this.process = process;
            this.in = process.getOutputStream();
            this.shizuku = shizuku;
            this.injector = injector;
            startThread("out", this::readStdout);
            startThread("err", this::readStderr);
        }

        /** Nur vom Worker-Thread. */
        void execute(Pending p) throws IOException {
            pending.add(p);
            writeLine(injector ? p.injectorLine
                    : p.command + " 2>&1; echo " + RC_MARKER + "$?");
        }

        void writeLine(String line) throws IOException {
            in.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            in.flush();
        }

        /** Wartet, bis KeyInjector bereit ist; false bei Timeout oder beendetem Prozess. */
        boolean awaitInjector(long timeoutMs) {
            try {
                started.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
            if (!injectorReady) {
                // stderr-Thread kurz nachlaufen lassen, damit die Fehlermeldung vollständig ist
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
            }
            return injectorReady;
        }

        String stderrText() {
            synchronized (stderr) {
                return stderr.toString().trim();
            }
        }

        boolean isAlive() {
            try {
                process.exitValue();
                return false;
            } catch (IllegalThreadStateException running) {
                return true;
            }
        }

        void close() {
            try {
                in.write("exit\n".getBytes(StandardCharsets.UTF_8));
                in.flush();
            } catch (Exception ignored) {
            }
            try {
                in.close();
            } catch (Exception ignored) {
            }
            try {
                process.destroy();
            } catch (Exception ignored) {
            }
        }

        private void startThread(String name, Runnable r) {
            Thread t = new Thread(r, "GWRHub-KeyShell-" + name);
            t.setDaemon(true);
            t.start();
        }

        private void readStdout() {
            StringBuilder output = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (injector && !injectorReady && line.contains(KeyInjector.READY)) {
                        injectorReady = true;
                        output.setLength(0);
                        started.countDown();
                        continue;
                    }
                    int idx = line.indexOf(RC_MARKER);
                    if (idx < 0) {
                        if (output.length() < 4000) output.append(line).append('\n');
                        continue;
                    }
                    if (idx > 0) output.append(line, 0, idx);
                    int rc = parseInt(line.substring(idx + RC_MARKER.length()).trim());
                    Pending p = pending.poll();
                    String error = evaluateResult(rc, output.toString());
                    if (error == null) anySuccess = true;
                    else Log.w(TAG, "'" + (p == null ? "?" : injector ? p.injectorLine : p.command)
                            + "' -> rc=" + rc
                            + ", output: " + output.toString().trim());
                    if (p != null) deliver(p, error == null, error);
                    output.setLength(0);
                }
            } catch (IOException ignored) {
                // Shell wurde geschlossen
            }
            started.countDown();
            onShellEnded(output.toString().trim());
        }

        private void readStderr() {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    Log.w(TAG, "shell stderr: " + line);
                    synchronized (stderr) {
                        if (stderr.length() < 2000) stderr.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
            }
        }

        /** Shell ist beendet: offene Aufträge mit Fehler beantworten. */
        private void onShellEnded(String leftover) {
            if (pending.isEmpty()) return;
            String detail;
            synchronized (stderr) {
                detail = (stderr.toString() + "\n" + leftover).trim();
            }
            String message;
            if (!shizuku && !anySuccess) {
                rootDenied = true;
                notifyStatus();
                message = "Root access was denied or failed";
            } else {
                message = (shizuku ? "Shizuku" : "Root") + " shell closed unexpectedly";
            }
            if (!detail.isEmpty()) {
                String first = detail.split("\n", 2)[0];
                if (first.length() > 160) first = first.substring(0, 160) + "…";
                message += ": " + first;
            }
            Pending p;
            while ((p = pending.poll()) != null) deliver(p, false, message);
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ Hilfen

    private static boolean shizukuRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean shizukuOutdated() {
        try {
            return Shizuku.isPreV11();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean shizukuGranted() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Versionsname der Shizuku-App oder null, wenn nicht installiert. */
    private String shizukuAppVersion() {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return info.versionName != null ? info.versionName : "?";
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean hasSuBinary() {
        for (String path : SU_PATHS) {
            if (new File(path).exists()) return true;
        }
        return false;
    }

    private void notifyStatus() {
        main.post(listener::onStatusChanged);
    }

    /** Beantwortet einen Auftrag genau einmal (UI-Thread). */
    private void deliver(Pending p, boolean ok, String message) {
        if (!p.done.compareAndSet(false, true)) return;
        long millis = SystemClock.elapsedRealtime() - p.startedAt;
        if (!ok) Log.w(TAG, "Send failed: " + message);
        main.post(() -> {
            if (p.callback != null) p.callback.onResult(ok, message, millis);
            else if (!ok) listener.onSendFailed(message);
        });
    }
}
