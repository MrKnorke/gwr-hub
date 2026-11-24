package com.gwrhub.overlay;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * Unsichtbare Activity, die sich nach einem kurzen Moment selbst schließt.
 *
 * Shizuku schickt seinen Binder nur, wenn ein Prozess der App startet oder in den Vordergrund
 * kommt. Lief GWR Hub (Overlay-Service) schon, bevor Shizuku gestartet wurde, kommt der Binder
 * nie an. Diese Activity holt den Prozess kurz in den Vordergrund, ohne das Spiel zu verlassen,
 * damit Shizuku den Binder nachliefert ("Reconnect" im Keys-Tab).
 */
public class ShizukuWakeActivity extends Activity {

    /** Lange genug, damit Android den Vordergrund-Wechsel meldet. */
    private static final long STAY_MS = 400;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable close = this::finish;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(close);
        handler.postDelayed(close, STAY_MS);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(close);
        super.onDestroy();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }
}
