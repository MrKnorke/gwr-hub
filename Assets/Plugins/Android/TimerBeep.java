package com.gwrhub.overlay;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

/**
 * Kurzer Signalton (1 s) für den Timer: Sinus mit kurzem Einschwingen und weichem Ausklingen,
 * damit er nicht schrill wirkt. Wird zur Laufzeit erzeugt, braucht also keine Audiodatei.
 * Läuft über den Benachrichtigungs-Kanal: folgt dessen Lautstärke und bleibt im Lautlos-Modus
 * stumm. Fordert keinen Audio-Fokus an, der Spielsound läuft also ungestört weiter.
 */
final class TimerBeep {

    static final long DURATION_MS = 1_000L;

    private static final int SAMPLE_RATE = 44_100;
    private static final double FREQ_HZ = 880.0;
    private static final double ATTACK_S = 0.01;
    private static final double RELEASE_S = 0.08;
    private static final double VOLUME = 0.6;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static short[] pcm;

    private TimerBeep() {}

    /** Spielt den Ton einmal ab; Fehler (z. B. kein Audiogerät) werden still ignoriert. */
    static void play() {
        try {
            short[] samples = samples();
            final AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(samples.length * 2)
                    .build();
            track.write(samples, 0, samples.length);
            track.play();
            HANDLER.postDelayed(() -> {
                try {
                    track.stop();
                } catch (IllegalStateException ignored) {
                    // schon gestoppt
                }
                track.release();
            }, DURATION_MS + 250L);
        } catch (Exception ignored) {
            // Timer funktioniert auch ohne Ton
        }
    }

    private static short[] samples() {
        if (pcm != null) return pcm;
        int n = (int) (SAMPLE_RATE * DURATION_MS / 1000L);
        double total = n / (double) SAMPLE_RATE;
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) SAMPLE_RATE;
            double env = Math.exp(-2.5 * t);
            if (t < ATTACK_S) env *= t / ATTACK_S;
            if (t > total - RELEASE_S) env *= (total - t) / RELEASE_S;
            double wave = Math.sin(2 * Math.PI * FREQ_HZ * t)
                    + 0.25 * Math.sin(2 * Math.PI * 2 * FREQ_HZ * t);
            out[i] = (short) (wave / 1.25 * env * VOLUME * Short.MAX_VALUE);
        }
        pcm = out;
        return out;
    }
}
