package com.gwrhub.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Foreground-Service, der über anderen Apps (z. B. Guild Wars Reforged) zeichnet:
 *  - kleiner, verschiebbarer Button oben links
 *  - Popup mit mehreren Seiten (PvX Wiki, GW Wiki, Kamadan, GW Market, Karte, Teams) in je eigener WebView
 * Beim Minimieren wird das Popup nur aus dem Fenster genommen; die WebViews bleiben
 * erhalten (inkl. Verlauf). Zusätzlich wird die aktuelle URL je Tab gespeichert, damit sie
 * auch nach einem Neustart des Overlays wieder geöffnet wird.
 */
public class OverlayService extends Service {

    static volatile boolean isRunning = false;

    static final String ACTION_STOP = "com.gwrhub.overlay.STOP";

    private static final String CHANNEL_ID = "gwrhub_overlay";
    private static final int NOTIFICATION_ID = 4711;
    private static final int FGS_TYPE_SPECIAL_USE = 0x40000000; // ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

    private static final String PREFS = "gwrhub_overlay";
    private static final String KEY_TAB = "tab";
    private static final String KEY_URL_PREFIX = "url_";
    private static final String KEY_BTN_X = "btn_x";
    private static final String KEY_BTN_Y = "btn_y";

    // Reihenfolge nicht ändern: Index = Schlüssel für gespeicherte URL / aktiven Tab.
    // Neue Seiten immer hinten anhängen.
    private static final String[] TAB_TITLES = {
            "PvX Wiki", "GW Wiki", "Kamadan", "GW Market", "Karte", "Teams"
    };
    private static final String[] TAB_HOME = {
            "https://gwpvx.fandom.com/wiki/Main_Page",
            "https://wiki.guildwars.com/wiki/Main_Page",
            "https://kamadan.gwtoolbox.com/",
            "https://gwmarket.net/",
            "https://gwrevival.com/map/?c=1&x=3903&y=4791&z=5",
            "https://gwskillbar.com/teams"
    };

    // Farben im Guild-Wars-Stil
    private static final int COLOR_BG = 0xF21C1A17;
    private static final int COLOR_HEADER = 0xFF2A2620;
    private static final int COLOR_GOLD = 0xFFC9A23F;
    private static final int COLOR_TEXT = 0xFFEDE6D6;
    private static final int COLOR_BUBBLE = 0xE07A1010;
    private static final int COLOR_BUBBLE_ACTIVE = 0xF0A31818;

    private WindowManager windowManager;
    private SharedPreferences prefs;

    private TextView bubble;
    private WindowManager.LayoutParams bubbleParams;

    private PanelLayout panel;
    private WindowManager.LayoutParams panelParams;
    private boolean panelShown = false;

    private final WebView[] webViews = new WebView[TAB_TITLES.length];
    private final boolean[] tabLoaded = new boolean[TAB_TITLES.length];
    private final TextView[] tabButtons = new TextView[TAB_TITLES.length];
    private ProgressBar progressBar;
    private HorizontalScrollView tabScroller;
    private int currentTab = 0;

    // ------------------------------------------------------------------ Lifecycle

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundWithNotification();

        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }

        isRunning = true;
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        currentTab = clampTab(prefs.getInt(KEY_TAB, 0));
        createBubble();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (bubble != null) {
            clampBubble();
            safeUpdate(bubble, bubbleParams);
        }
        if (panelShown) {
            applyPanelSize();
            safeUpdate(panel, panelParams);
        }
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        saveState();
        if (panelShown) safeRemove(panel);
        panelShown = false;
        if (bubble != null) safeRemove(bubble);
        for (int i = 0; i < webViews.length; i++) {
            if (webViews[i] != null) {
                webViews[i].stopLoading();
                webViews[i].destroy();
                webViews[i] = null;
            }
        }
        try {
            CookieManager.getInstance().flush();
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ Notification

    private void startForegroundWithNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "GWR Hub Overlay", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Hält das Wiki-Overlay im Spiel aktiv");
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

        Intent stopIntent = new Intent(this, OverlayService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent, piFlags);

        PendingIntent openPi = null;
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            openPi = PendingIntent.getActivity(this, 2, launch, piFlags);
        }

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("GWR Hub Overlay aktiv")
                .setContentText("Tippe auf den Button oben links für Wikis, Handel & Karte")
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Overlay beenden", stopPi);
        if (openPi != null) b.setContentIntent(openPi);
        Notification n = b.build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, FGS_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    // ------------------------------------------------------------------ Bubble (Button oben links)

    private void createBubble() {
        bubble = new TextView(this);
        bubble.setText("GW");
        bubble.setTextColor(COLOR_GOLD);
        bubble.setTypeface(Typeface.DEFAULT_BOLD);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        bubble.setGravity(Gravity.CENTER);
        updateBubbleStyle();

        bubbleParams = new WindowManager.LayoutParams(
                dp(44), dp(44), overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = prefs.getInt(KEY_BTN_X, dp(8));
        bubbleParams.y = prefs.getInt(KEY_BTN_Y, dp(8));
        clampBubble();

        final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        bubble.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;
            private float downX, downY;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = bubbleParams.x;
                        startY = bubbleParams.y;
                        downX = e.getRawX();
                        downY = e.getRawY();
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                            dragging = true;
                        }
                        if (dragging) {
                            bubbleParams.x = startX + (int) dx;
                            bubbleParams.y = startY + (int) dy;
                            clampBubble();
                            safeUpdate(bubble, bubbleParams);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (dragging) {
                            prefs.edit()
                                    .putInt(KEY_BTN_X, bubbleParams.x)
                                    .putInt(KEY_BTN_Y, bubbleParams.y)
                                    .apply();
                        } else {
                            togglePanel();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                }
                return false;
            }
        });

        windowManager.addView(bubble, bubbleParams);
    }

    private void updateBubbleStyle() {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(panelShown ? COLOR_BUBBLE_ACTIVE : COLOR_BUBBLE);
        bg.setStroke(dp(2), COLOR_GOLD);
        bubble.setBackground(bg);
        bubble.setAlpha(panelShown ? 1f : 0.85f);
    }

    private void clampBubble() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int size = dp(44);
        bubbleParams.x = Math.max(0, Math.min(bubbleParams.x, dm.widthPixels - size));
        bubbleParams.y = Math.max(0, Math.min(bubbleParams.y, dm.heightPixels - size));
    }

    // ------------------------------------------------------------------ Panel (Popup)

    private void togglePanel() {
        if (panelShown) hidePanel();
        else showPanel();
    }

    private void showPanel() {
        if (panel == null) buildPanel();
        if (panelShown) return;

        applyPanelSize();
        windowManager.addView(panel, panelParams);
        panelShown = true;

        // Button wieder obenauf legen, damit er nie vom Popup verdeckt wird
        safeRemove(bubble);
        windowManager.addView(bubble, bubbleParams);
        updateBubbleStyle();

        for (WebView wv : webViews) {
            if (wv != null) wv.onResume();
        }
        selectTab(currentTab);
    }

    /** Minimieren: Popup ausblenden, WebViews und deren Verlauf bleiben erhalten. */
    private void hidePanel() {
        if (!panelShown) return;
        saveState();
        for (WebView wv : webViews) {
            if (wv != null) wv.onPause();
        }
        safeRemove(panel);
        panelShown = false;
        updateBubbleStyle();
    }

    private void applyPanelSize() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        boolean landscape = dm.widthPixels > dm.heightPixels;
        if (landscape) {
            panelParams.width = (int) (dm.widthPixels * 0.62f);
            panelParams.height = (int) (dm.heightPixels * 0.90f);
        } else {
            panelParams.width = (int) (dm.widthPixels * 0.96f);
            panelParams.height = (int) (dm.heightPixels * 0.72f);
        }
    }

    private void buildPanel() {
        panel = new PanelLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_BG);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), COLOR_GOLD);
        panel.setBackground(bg);
        panel.setClipToOutline(true);
        panel.setPadding(dp(1), dp(1), dp(1), dp(1));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        panel.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // --- Kopfzeile: Tabs + Navigation
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(COLOR_HEADER);
        header.setPadding(dp(4), 0, dp(4), 0);
        column.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        // Tabs horizontal scrollbar, damit alle Seiten auch im schmalen Popup Platz haben
        tabScroller = new HorizontalScrollView(this);
        tabScroller.setHorizontalScrollBarEnabled(false);
        tabScroller.setFillViewport(false);
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);
        tabScroller.addView(tabRow, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        header.addView(tabScroller, new LinearLayout.LayoutParams(0, dp(42), 1f));

        for (int i = 0; i < TAB_TITLES.length; i++) {
            final int index = i;
            TextView tab = makeHeaderButton(TAB_TITLES[i]);
            tab.setPadding(dp(12), 0, dp(12), 0);
            tab.setOnClickListener(v -> selectTab(index));
            tabButtons[i] = tab;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
            lp.setMarginEnd(dp(4));
            tabRow.addView(tab, lp);
        }

        addNavButton(header, "←", v -> {
            WebView wv = webViews[currentTab];
            if (wv != null && wv.canGoBack()) wv.goBack();
        });
        addNavButton(header, "→", v -> {
            WebView wv = webViews[currentTab];
            if (wv != null && wv.canGoForward()) wv.goForward();
        });
        addNavButton(header, "↻", v -> {
            WebView wv = webViews[currentTab];
            if (wv != null) wv.reload();
        });
        addNavButton(header, "⌂", v -> {
            WebView wv = webViews[currentTab];
            if (wv != null) wv.loadUrl(TAB_HOME[currentTab]);
        });
        addNavButton(header, "—", v -> hidePanel()); // Minimieren

        // --- Ladebalken
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(COLOR_GOLD));
        column.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        // --- WebViews
        FrameLayout content = new FrameLayout(this);
        content.setBackgroundColor(Color.WHITE);
        column.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        for (int i = 0; i < TAB_TITLES.length; i++) {
            WebView wv = createWebView(i);
            wv.setVisibility(View.GONE);
            webViews[i] = wv;
            content.addView(wv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        panelParams = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                overlayType(),
                // fokussierbar (Tastatur für die Wiki-Suche), Touches außerhalb gehen ans Spiel
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.CENTER;
        panelParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
    }

    private WebView createWebView(final int tabIndex) {
        WebView wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        // z. B. Logins/Einbettungen auf GW Market oder der Karte
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // http(s) in der WebView öffnen, alles andere (intent:, mailto: …) ignorieren
                return !(url.startsWith("http://") || url.startsWith("https://"));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && url.startsWith("http") && prefs != null) {
                    prefs.edit().putString(KEY_URL_PREFIX + tabIndex, url).apply();
                }
            }
        });
        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (tabIndex == currentTab && progressBar != null) {
                    progressBar.setProgress(newProgress);
                    progressBar.setVisibility(newProgress >= 100 ? View.INVISIBLE : View.VISIBLE);
                }
            }
        });
        return wv;
    }

    private void selectTab(int index) {
        currentTab = clampTab(index);
        for (int i = 0; i < webViews.length; i++) {
            boolean active = i == currentTab;
            if (webViews[i] != null) webViews[i].setVisibility(active ? View.VISIBLE : View.GONE);
            styleTab(tabButtons[i], active);
        }
        scrollTabIntoView(currentTab);
        WebView wv = webViews[currentTab];
        if (wv != null && !tabLoaded[currentTab]) {
            tabLoaded[currentTab] = true;
            String saved = prefs.getString(KEY_URL_PREFIX + currentTab, null);
            wv.loadUrl(saved != null ? saved : TAB_HOME[currentTab]);
        }
        if (wv != null && progressBar != null) {
            int p = wv.getProgress();
            progressBar.setProgress(p);
            progressBar.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
        }
        prefs.edit().putInt(KEY_TAB, currentTab).apply();
    }

    private void scrollTabIntoView(int index) {
        if (tabScroller == null || tabButtons[index] == null) return;
        final TextView tab = tabButtons[index];
        tabScroller.post(() -> {
            int left = tab.getLeft() - dp(8);
            int right = tab.getRight() + dp(8);
            int x = tabScroller.getScrollX();
            int w = tabScroller.getWidth();
            if (left < x) tabScroller.smoothScrollTo(Math.max(0, left), 0);
            else if (right > x + w) tabScroller.smoothScrollTo(right - w, 0);
        });
    }

    private void saveState() {
        if (prefs == null) return;
        SharedPreferences.Editor ed = prefs.edit();
        ed.putInt(KEY_TAB, currentTab);
        for (int i = 0; i < webViews.length; i++) {
            WebView wv = webViews[i];
            if (wv != null && tabLoaded[i]) {
                String url = wv.getUrl();
                if (url != null && url.startsWith("http")) ed.putString(KEY_URL_PREFIX + i, url);
            }
        }
        ed.apply();
    }

    // ------------------------------------------------------------------ UI-Helfer

    private TextView makeHeaderButton(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(COLOR_TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setClickable(true);
        return tv;
    }

    private void addNavButton(LinearLayout header, String label, View.OnClickListener l) {
        TextView b = makeHeaderButton(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setOnClickListener(l);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(6));
        bg.setColor(0x22FFFFFF);
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(32));
        lp.setMarginStart(dp(4));
        header.addView(b, lp);
    }

    private void styleTab(TextView tab, boolean active) {
        if (tab == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(6));
        if (active) {
            bg.setColor(COLOR_GOLD);
            tab.setTextColor(0xFF1C1A17);
            tab.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            bg.setColor(0x22FFFFFF);
            tab.setTextColor(COLOR_TEXT);
            tab.setTypeface(Typeface.DEFAULT);
        }
        tab.setBackground(bg);
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private int clampTab(int i) {
        return Math.max(0, Math.min(i, TAB_TITLES.length - 1));
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void safeRemove(View v) {
        try {
            if (v != null && v.isAttachedToWindow()) windowManager.removeView(v);
        } catch (Exception ignored) {
        }
    }

    private void safeUpdate(View v, WindowManager.LayoutParams lp) {
        try {
            if (v != null && v.isAttachedToWindow()) windowManager.updateViewLayout(v, lp);
        } catch (Exception ignored) {
        }
    }

    /** Root des Popups: Zurück-Taste = im Wiki zurück, sonst minimieren. */
    private class PanelLayout extends FrameLayout {
        PanelLayout(Context ctx) {
            super(ctx);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    WebView wv = webViews[currentTab];
                    if (wv != null && wv.canGoBack()) wv.goBack();
                    else hidePanel();
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
    }
}
