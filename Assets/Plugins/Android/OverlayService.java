package com.gwrhub.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.PathShape;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import java.io.IOException;
import java.io.InputStream;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Foreground-Service, der über anderen Apps (z. B. Guild Wars Reforged) zeichnet:
 *  - kleiner, verschiebbarer Button oben links
 *  - Popup mit mehreren Seiten (PvX Wiki, GW Wiki, Kamadan, GW Market, Karte, Teams, Today,
 *    YouTube) in je eigener WebView
 * Beim Minimieren wird das Popup nur aus dem Fenster genommen; die WebViews bleiben
 * erhalten (inkl. Verlauf), laufende Videos/Audios werden pausiert. Zusätzlich wird die
 * aktuelle URL je Tab gespeichert, damit sie auch nach einem Neustart des Overlays wieder
 * geöffnet wird.
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
    // Positionen getrennt für Hoch- und Querformat: Schlüssel + SUFFIX_PORT / SUFFIX_LAND.
    // Der Schlüssel ohne Suffix ist die alte, gemeinsame Position (nur noch als Fallback gelesen).
    private static final String KEY_BTN_X = "btn_x";
    private static final String KEY_BTN_Y = "btn_y";
    private static final String KEY_TIMER_BAR = "timer_bar";
    private static final String KEY_MINI_X = "mini_x";
    private static final String KEY_MINI_Y = "mini_y";
    private static final String SUFFIX_PORT = "_port";
    private static final String SUFFIX_LAND = "_land";

    // Mini-Player (Bild-in-Bild für YouTube): Breite in dp, Seitenverhältnis 16:9
    private static final int MINI_MIN_W_DP = 200;
    private static final int MINI_MAX_W_DP = 360;
    private static final int MINI_MARGIN_DP = 12;
    private static final long MINI_CONTROLS_MS = 3000L;

    private static final int BUBBLE_SIZE_DP = 44;
    private static final int BADGE_W_DP = 58;
    private static final int BADGE_H_DP = 24;
    private static final int BADGE_GAP_DP = 3;

    // Preset-Dauern (Timer-Leiste im Hub) und Schritte zum Anpassen
    private static final long[] TIMER_PRESETS_MS = {
            30_000L, 60_000L, 2 * 60_000L, 5 * 60_000L, 10 * 60_000L,
            15 * 60_000L, 20 * 60_000L, 30 * 60_000L
    };
    private static final long[] TIMER_STEPS_MS = {-60_000L, 60_000L, -10_000L, 10_000L};

    // Reihenfolge nicht ändern: Index = Schlüssel für gespeicherte URL / aktiven Tab.
    // Neue Seiten immer hinten anhängen.
    private static final String[] TAB_TITLES = {
            "PvX Wiki", "GW Wiki", "Kamadan", "GW Market", "Map", "Teams", "Today", "YouTube", "Keys"
    };
    private static final String[] TAB_HOME = {
            "https://gwpvx.fandom.com/wiki/Main_Page",
            "https://wiki.guildwars.com/wiki/Main_Page",
            "https://kamadan.gwtoolbox.com/",
            "https://gwmarket.net/",
            "https://gwrevival.com/map/?c=1&x=3903&y=4791&z=5",
            "https://gwskillbar.com/teams",
            "https://foogw.github.io/",
            YouTubeLinks.DEFAULT_SEARCH_URL,
            null // Keys: eigene Ansicht statt Webseite
    };
    /** Index des YouTube-Tabs in TAB_TITLES / TAB_HOME. */
    private static final int TAB_YOUTUBE = 7;
    /** Index des Tabs für Tasten-Shortcuts (keine WebView). */
    private static final int TAB_KEYS = 8;

    /** Pausiert alle HTML5-Videos/-Audios der Seite (YouTube spielt in einem &lt;video&gt;-Element). */
    private static final String JS_PAUSE_MEDIA =
            "(function(){var m=document.querySelectorAll('video,audio');"
                    + "for(var i=0;i<m.length;i++){try{m[i].pause();}catch(e){}}})();";

    /**
     * true, wenn gerade ein Video läuft. Stumme Vorschau-Videos (Suchliste/Startseite) zählen
     * nicht, auf Video-Seiten (/watch, /shorts, /live) schon.
     */
    private static final String JS_IS_PLAYING =
            "(function(){var w=/^\\/(watch|shorts|live)/.test(location.pathname);"
                    + "var m=document.querySelectorAll('video');"
                    + "for(var i=0;i<m.length;i++){var v=m[i];"
                    + "if(!v.paused&&!v.ended&&(w||!v.muted))return true;}"
                    + "return false;})();";

    /**
     * Teil von {@link #JS_PIP_ENTER}, einmal je Seite: Im Mini-Player ist das Fenster nicht
     * fokussierbar; YouTube wertet den Fokus-/Sichtbarkeitsverlust als „App im Hintergrund“ und
     * pausiert (Hintergrund-Wiedergabe ist auf m.youtube.com gesperrt). Daher solange die Seite
     * als sichtbar und fokussiert melden und blur/visibilitychange verschlucken, soweit möglich
     * (früher direkt auf window registrierte Listener erreicht das nicht – dort helfen
     * {@link HubWebView}, das gar kein echtes blur entstehen lässt, und der Wächter unten).
     * Wiedergabe läuft über die Player-API (movie_player.playVideo/pauseVideo), damit YouTubes
     * eigener Zustand mitzieht – ein bloßes video.play() hält der Player sofort wieder an.
     * Pausiert die Seite trotzdem selbst, wird fortgesetzt (höchstens 4× in 10 s, dann ▶).
     */
    private static final String JS_PIP_GUARD =
            "if(!window.__gwrPipGuard){window.__gwrPipGuard=1;"
                    + "var on=function(){return h.classList.contains('gwr-pip');};"
                    + "var P=Document.prototype;"
                    + "var fake=function(n,val){var x=Object.getOwnPropertyDescriptor(P,n);if(!x||!x.get)return;"
                    + "try{Object.defineProperty(d,n,{configurable:true,"
                    + "get:function(){return on()?val:x.get.call(d);}});}catch(e){}};"
                    + "fake('hidden',false);fake('webkitHidden',false);"
                    + "fake('visibilityState','visible');fake('webkitVisibilityState','visible');"
                    + "var hf=d.hasFocus;d.hasFocus=function(){return on()||hf.call(d);};"
                    + "['visibilitychange','webkitvisibilitychange','blur','focusout','pagehide','freeze']"
                    + ".forEach(function(t){var f=function(e){if(on())e.stopImmediatePropagation();};"
                    + "window.addEventListener(t,f,true);d.addEventListener(t,f,true);});"
                    + "window.__gwrPipVideo=function(){return d.querySelector('video.gwr-pip-video')"
                    + "||d.querySelector('video');};"
                    + "window.__gwrPipPlay=function(){var mp=d.getElementById('movie_player');"
                    + "if(mp&&typeof mp.playVideo==='function'){try{mp.playVideo();}catch(e){}}"
                    + "var v=window.__gwrPipVideo();"
                    + "if(v&&v.paused&&!v.ended){var p=v.play();if(p&&p.catch)p.catch(function(){});}};"
                    + "window.__gwrPipPause=function(){var mp=d.getElementById('movie_player');"
                    + "if(mp&&typeof mp.pauseVideo==='function'){try{mp.pauseVideo();}catch(e){}}"
                    + "var v=window.__gwrPipVideo();if(v&&!v.paused)v.pause();};"
                    + "d.addEventListener('pause',function(e){var v=e.target;"
                    + "if(!on()||!window.__gwrPipWant||!v||v.tagName!=='VIDEO'"
                    + "||!v.classList.contains('gwr-pip-video')||v.ended)return;"
                    + "var now=Date.now(),hs=(window.__gwrPipHist||[]).filter(function(t){return now-t<10000;});"
                    + "if(hs.length>=4){window.__gwrPipWant=false;window.__gwrPipHist=[];"
                    + "console.log('GWRPiP: video keeps getting paused, giving up');return;}"
                    + "hs.push(now);window.__gwrPipHist=hs;console.log('GWRPiP: paused by page, resuming');"
                    + "setTimeout(function(){if(on()&&window.__gwrPipWant&&v.paused&&!v.ended)"
                    + "window.__gwrPipPlay();},300);},true);}";

    /**
     * Mini-Player: blendet alles außer dem laufenden Video aus und legt es über die ganze
     * (kleine) WebView. Vorfahren des Videos bekommen transform/contain entfernt, sonst wäre
     * position:fixed auf den Vorfahren statt auf den Viewport bezogen. Wechselt YouTube das
     * &lt;video&gt;-Element (nächstes Video, Werbung), wird das neue beim Start markiert.
     */
    private static final String JS_PIP_ENTER =
            "(function(){var d=document,h=d.documentElement;"
                    + "if(!d.getElementById('gwr-pip-style')){var s=d.createElement('style');"
                    + "s.id='gwr-pip-style';s.textContent="
                    + "'html.gwr-pip,html.gwr-pip body{overflow:hidden!important;background:#000!important}"
                    + "html.gwr-pip body *{visibility:hidden!important}"
                    + "html.gwr-pip .gwr-pip-anc{transform:none!important;filter:none!important;"
                    + "contain:none!important;perspective:none!important;will-change:auto!important;"
                    + "clip-path:none!important;backdrop-filter:none!important;"
                    + "content-visibility:visible!important}"
                    + "html.gwr-pip video.gwr-pip-video{visibility:visible!important;position:fixed!important;"
                    + "left:0!important;top:0!important;right:auto!important;bottom:auto!important;"
                    + "width:100vw!important;height:100vh!important;max-width:none!important;"
                    + "max-height:none!important;margin:0!important;transform:none!important;"
                    + "object-fit:contain!important;background:#000!important;z-index:2147483647!important}';"
                    + "(d.head||h).appendChild(s);}"
                    + "function pick(){var m=d.querySelectorAll('video'),b=null;"
                    + "for(var i=0;i<m.length;i++){var v=m[i];if(!v.paused&&!v.ended)return v;"
                    + "if(!b&&v.classList.contains('html5-main-video'))b=v;}return b||m[0]||null;}"
                    + "function mark(){var v=pick();if(!v)return;"
                    + "var o=d.querySelectorAll('.gwr-pip-video,.gwr-pip-anc');"
                    + "for(var i=0;i<o.length;i++){o[i].classList.remove('gwr-pip-video','gwr-pip-anc');}"
                    + "v.classList.add('gwr-pip-video');"
                    + "for(var p=v.parentElement;p&&p!==h;p=p.parentElement)p.classList.add('gwr-pip-anc');}"
                    + "h.classList.add('gwr-pip');mark();window.scrollTo(0,0);"
                    + "window.__gwrPipWant=true;window.__gwrPipHist=[];"
                    + "if(!window.__gwrPipListener){window.__gwrPipListener=function(e){"
                    + "var t=e.target;if(h.classList.contains('gwr-pip')&&t&&t.tagName==='VIDEO'"
                    + "&&!t.classList.contains('gwr-pip-video'))mark();};"
                    + "d.addEventListener('playing',window.__gwrPipListener,true);}"
                    + JS_PIP_GUARD + "})();";

    /** Mini-Player beenden: Seite sieht wieder normal aus. */
    private static final String JS_PIP_LEAVE =
            "(function(){var d=document;d.documentElement.classList.remove('gwr-pip');"
                    + "window.__gwrPipWant=false;"
                    + "var o=d.querySelectorAll('.gwr-pip-video,.gwr-pip-anc');"
                    + "for(var i=0;i<o.length;i++){o[i].classList.remove('gwr-pip-video','gwr-pip-anc');}})();";

    /** Läuft das Video im Mini-Player? */
    private static final String JS_PIP_IS_PLAYING =
            "(function(){var v=document.querySelector('video.gwr-pip-video')||document.querySelector('video');"
                    + "return !!(v&&!v.paused&&!v.ended);})();";

    /** Mini-Player: Video fortsetzen, falls die Seite es beim Verkleinern angehalten hat. */
    private static final String JS_PIP_RESUME =
            "(function(){var v=document.querySelector('video.gwr-pip-video');"
                    + "if(v&&v.paused&&!v.ended&&window.__gwrPipWant!==false){"
                    + "if(window.__gwrPipPlay)window.__gwrPipPlay();"
                    + "else{var p=v.play();if(p&&p.catch)p.catch(function(){});}}})();";

    /** Mini-Player: Wiedergabe umschalten. @return true, wenn das Video danach läuft. */
    private static final String JS_PIP_TOGGLE =
            "(function(){var v=document.querySelector('video.gwr-pip-video')||document.querySelector('video');"
                    + "if(!v)return false;if(v.paused||v.ended){window.__gwrPipWant=true;window.__gwrPipHist=[];"
                    + "if(window.__gwrPipPlay)window.__gwrPipPlay();"
                    + "else{var p=v.play();if(p&&p.catch)p.catch(function(){});}return true;}"
                    + "window.__gwrPipWant=false;"
                    + "if(window.__gwrPipPause)window.__gwrPipPause();else v.pause();return false;})();";

    // Farben im Guild-Wars-Stil
    private static final int COLOR_BG = 0xF21C1A17;
    private static final int COLOR_HEADER = 0xFF2A2620;
    private static final int COLOR_GOLD = 0xFFC9A23F;
    private static final int COLOR_TEXT = 0xFFEDE6D6;
    private static final int COLOR_BUBBLE = 0xE07A1010;
    private static final int COLOR_BUBBLE_ACTIVE = 0xF0A31818;
    private static final int COLOR_STOPWATCH = 0xFF8FB8D8; // Akzent für die Stoppuhr

    private WindowManager windowManager;
    private SharedPreferences prefs;

    // Liegt in Assets/StreamingAssets und landet so im "assets/"-Ordner der APK
    private static final String BUBBLE_ICON_ASSET = "gwr_hub_button.png";

    private ImageView bubble;
    private WindowManager.LayoutParams bubbleParams;

    private PanelLayout panel;
    private WindowManager.LayoutParams panelParams;
    /** Popup in voller Größe sichtbar (Hub geöffnet). */
    private boolean panelShown = false;
    /**
     * Popup als kleiner Video-Player (Bild-in-Bild) sichtbar. Dasselbe Fenster wie das Popup,
     * nur verkleinert und nicht fokussierbar: Die WebView bleibt angezeigt, also läuft das
     * Video mit Ton weiter, und das Spiel bekommt Tastatur und alle Touches außerhalb.
     */
    private boolean miniShown = false;
    private View panelHeader;
    private FrameLayout miniLayer;
    private FrameLayout miniControls;
    private TextView miniPlayPause;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable hideMiniControls = () -> setMiniControlsVisible(false);
    /** Hat der Nutzer im Mini-Player selbst pausiert/gestartet? Dann nicht automatisch fortsetzen. */
    private boolean miniUserToggled = false;
    private final Runnable resumeMiniPlayback = () -> {
        WebView yt = this.webViews[TAB_YOUTUBE];
        if (miniShown && !miniUserToggled && yt != null) yt.evaluateJavascript(JS_PIP_RESUME, null);
    };

    /** Hat der Nutzer die YouTube-Seite schon berührt? Erst dann Autoplay mit Ton erlauben. */
    private boolean youTubeTouched = false;

    private final WebView[] webViews = new WebView[TAB_TITLES.length];
    private final boolean[] tabLoaded = new boolean[TAB_TITLES.length];
    private final TextView[] tabButtons = new TextView[TAB_TITLES.length];
    /** Fehler beim Laden der Hauptseite je Tab (null = kein Fehler). */
    private final TabError[] tabErrors = new TabError[TAB_TITLES.length];
    private ProgressBar progressBar;
    private HorizontalScrollView tabScroller;
    private TextView externalButton;
    private int currentTab = 0;

    // Inhaltsbereich unter der Kopfzeile: WebViews, Fehleranzeige, ggf. Vollbild-Video
    private FrameLayout contentFrame;
    private LinearLayout errorView;
    private TextView errorMessage;
    private TextView errorDetail;

    // Vollbild-Video (z. B. YouTube-Vollbildtaste) – füllt den Inhaltsbereich des Popups
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    // Timer: zentraler State für Hub-Leiste und Mini-Anzeige unter dem Button
    private OverlayTimerController timerController;
    private TimerBadgeView timerBadge;
    private WindowManager.LayoutParams timerBadgeParams;
    private boolean timerBadgeShown = false;
    private LinearLayout timerBar;
    private TextView timerBarTime;
    private TextView timerBarToggle;
    private TextView timerBarButton;
    private TextView timerModeCountdown;
    private TextView timerModeStopwatch;
    /** Presets und ±-Schritte, nur im Countdown-Modus sichtbar. */
    private final java.util.List<View> countdownOnlyChips = new java.util.ArrayList<>();

    // Tasten-Shortcuts: Keys-Tab + verschiebbare Bubbles, die Tasten ans Spiel senden
    private KeySender keySender;
    private ShortcutBubbles shortcutBubbles;
    private LinearLayout keysView;
    private EditText keyInput;
    private TextView keyFeedback;
    private TextView keyStatus;
    private TextView keyStatusAction;
    private TextView keyDiag;
    private TextView keyReconnect;
    private Switch keysEnabledSwitch;
    private TextView keyListTitle;
    /** Reconnect wurde angetippt, Ergebnis steht noch aus. */
    private boolean shizukuReconnecting;
    private final Runnable shizukuReconnectCheck = this::finishShizukuReconnect;
    private TextView keyTestResult;
    private LinearLayout keyList;
    /** Navigationsknöpfe (← → ↻ ⌂), im Keys-Tab ausgeblendet. */
    private final java.util.List<View> webNavButtons = new java.util.ArrayList<>();

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
        timerController = new OverlayTimerController(this, prefs, this::updateTimerUi);
        createBubble();
        createTimerBadge();
        updateTimerUi();
        createShortcuts();
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
            loadBubblePosition(); // gespeicherte Position der neuen Ausrichtung
            safeUpdate(bubble, bubbleParams);
            positionTimerBadge();
        }
        if (shortcutBubbles != null) shortcutBubbles.onConfigurationChanged();
        if (panelShown) {
            applyPanelSize();
            safeUpdate(panel, panelParams);
        } else if (miniShown) {
            applyMiniSize();
            updatePanelWindow();
        }
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        saveState();
        if (timerController != null) timerController.release();
        hideTimerBadge();
        if (shortcutBubbles != null) shortcutBubbles.release();
        if (keySender != null) keySender.release();
        ui.removeCallbacksAndMessages(null);
        if (panelShown || miniShown) safeRemove(panel);
        panelShown = false;
        miniShown = false;
        if (bubble != null) safeRemove(bubble);
        exitFullscreen();
        for (int i = 0; i < webViews.length; i++) {
            WebView wv = webViews[i];
            if (wv == null) continue;
            webViews[i] = null;
            // Laden/Wiedergabe stoppen, Clients lösen und die WebView aus dem Layout nehmen,
            // bevor sie zerstört wird – sonst können Audio/Netzwerk kurz weiterlaufen.
            wv.stopLoading();
            wv.setWebChromeClient(null);
            wv.setWebViewClient(new WebViewClient());
            ViewGroup parent = (ViewGroup) wv.getParent();
            if (parent != null) parent.removeView(wv);
            wv.removeAllViews();
            wv.destroy();
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
            channel.setDescription("Keeps the wiki overlay active in-game");
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
                .setContentTitle("GWR Hub overlay active")
                .setContentText("Tap the button in the top left for wikis, trade & map")
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop overlay", stopPi);
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
        bubble = new ImageView(this);
        bubble.setScaleType(ImageView.ScaleType.FIT_XY);
        Bitmap icon = loadCircularIcon(BUBBLE_ICON_ASSET);
        if (icon != null) bubble.setImageBitmap(icon);
        updateBubbleStyle();

        bubbleParams = new WindowManager.LayoutParams(
                dp(BUBBLE_SIZE_DP), dp(BUBBLE_SIZE_DP), overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        loadBubblePosition();

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
                            positionTimerBadge(); // Timer-Anzeige wandert mit
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (dragging) {
                            saveOrientedPosition(KEY_BTN_X, KEY_BTN_Y, bubbleParams.x, bubbleParams.y);
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
        bubble.setBackground(bg); // Fallback, falls das Bild fehlt

        // Goldener Ring über dem Bild
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(dp(2), panelShown ? COLOR_TEXT : COLOR_GOLD);
        bubble.setForeground(ring);
        bubble.setAlpha(panelShown ? 1f : 0.85f);
    }

    /** Lädt ein Bild aus den APK-Assets (Unity StreamingAssets) und schneidet es kreisförmig aus. */
    private Bitmap loadCircularIcon(String assetName) {
        Bitmap src;
        try (InputStream in = getAssets().open(assetName)) {
            src = BitmapFactory.decodeStream(in);
        } catch (IOException e) {
            return null;
        }
        if (src == null) return null;

        int size = Math.min(src.getWidth(), src.getHeight());
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        BitmapShader shader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setTranslate(-(src.getWidth() - size) / 2f, -(src.getHeight() - size) / 2f);
        shader.setLocalMatrix(m);
        paint.setShader(shader);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        src.recycle();
        return out;
    }

    /** Setzt den Button auf die gespeicherte Position der aktuellen Ausrichtung. */
    private void loadBubblePosition() {
        bubbleParams.x = getOrientedInt(KEY_BTN_X, dp(8));
        bubbleParams.y = getOrientedInt(KEY_BTN_Y, dp(8));
        clampBubble();
    }

    private boolean isLandscape() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return dm.widthPixels > dm.heightPixels;
    }

    /**
     * Liest eine Position für die aktuelle Ausrichtung. Ist für sie noch keine gespeichert,
     * gilt die der anderen Ausrichtung, dann die alte gemeinsame, dann {@code def}.
     */
    private int getOrientedInt(String key, int def) {
        boolean land = isLandscape();
        String own = key + (land ? SUFFIX_LAND : SUFFIX_PORT);
        String other = key + (land ? SUFFIX_PORT : SUFFIX_LAND);
        if (prefs.contains(own)) return prefs.getInt(own, def);
        if (prefs.contains(other)) return prefs.getInt(other, def);
        return prefs.getInt(key, def);
    }

    /** Speichert eine Position nur für die aktuelle Ausrichtung. */
    private void saveOrientedPosition(String keyX, String keyY, int x, int y) {
        String suffix = isLandscape() ? SUFFIX_LAND : SUFFIX_PORT;
        prefs.edit()
                .putInt(keyX + suffix, x)
                .putInt(keyY + suffix, y)
                .apply();
    }

    private void clampBubble() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int size = dp(BUBBLE_SIZE_DP);
        bubbleParams.x = Math.max(0, Math.min(bubbleParams.x, dm.widthPixels - size));
        bubbleParams.y = Math.max(0, Math.min(bubbleParams.y, dm.heightPixels - size));
    }

    // ------------------------------------------------------------------ Panel (Popup)

    private void togglePanel() {
        if (panelShown) minimizePanel();
        else showPanel(); // auch aus dem Mini-Player zurück in den Hub
    }

    private void showPanel() {
        if (panel == null) buildPanel();
        if (panelShown) return;

        boolean fromMini = miniShown;
        leaveMiniLayout();
        applyPanelSize();
        if (fromMini) updatePanelWindow(); // Fenster ist schon da, nur wieder groß machen
        else windowManager.addView(panel, panelParams);
        panelShown = true;

        // Button wieder obenauf legen, damit er nie vom Popup verdeckt wird
        safeRemove(bubble);
        windowManager.addView(bubble, bubbleParams);
        updateBubbleStyle();
        updateTimerUi(); // Mini-Anzeige ausblenden, Hub-Leiste aktualisieren
        // Shortcut-Bubbles solange ausblenden: Tasten gingen sonst ans Popup statt ans Spiel
        if (shortcutBubbles != null) shortcutBubbles.setVisible(false);
        updateKeysTab();

        // Nur der sichtbare Tab läuft weiter (onResume/onPause je Tab in selectTab)
        selectTab(currentTab);
    }

    /**
     * Minimieren per „—“, Zurück-Taste oder GW-Button. Läuft im YouTube-Tab gerade ein Video,
     * spielt es als kleiner Player über dem Spiel weiter, sonst wird das Popup ausgeblendet.
     */
    private void minimizePanel() {
        if (!panelShown) return;
        WebView yt = webViews[TAB_YOUTUBE];
        if (currentTab != TAB_YOUTUBE || yt == null || !tabLoaded[TAB_YOUTUBE]) {
            hidePanel();
            return;
        }
        exitFullscreen(); // Video läuft danach im normalen Player der Seite weiter
        yt.evaluateJavascript(JS_IS_PLAYING, result -> {
            // Inzwischen anders entschieden (z. B. doppelt getippt, Tab gewechselt)?
            if (!panelShown || currentTab != TAB_YOUTUBE) return;
            if ("true".equals(result)) enterMiniPlayer();
            else hidePanel();
        });
    }

    /** Minimieren: Popup ausblenden, WebViews und deren Verlauf bleiben erhalten. */
    private void hidePanel() {
        if (miniShown) {
            closeMiniPlayer();
            return;
        }
        if (!panelShown) return;
        saveState();
        // Laufende Videos (YouTube) sollen nicht unsichtbar im Hintergrund weiterspielen
        exitFullscreen();
        for (int i = 0; i < webViews.length; i++) pauseMedia(i);
        for (WebView wv : webViews) {
            if (wv != null) wv.onPause();
        }
        safeRemove(panel);
        panelShown = false;
        updateBubbleStyle();
        updateTimerUi(); // Timer-Anzeige unter dem Button einblenden
        if (shortcutBubbles != null) shortcutBubbles.setVisible(true);
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

    // ------------------------------------------------------------------ Mini-Player (Bild-in-Bild)

    /** Hub wird zum kleinen, verschiebbaren Video-Player; das Spiel bekommt die Kontrolle zurück. */
    private void enterMiniPlayer() {
        WebView yt = webViews[TAB_YOUTUBE];
        if (!panelShown || yt == null) return;
        saveState();
        hideKeyboard();
        exitFullscreen();
        // Nur das YouTube-Video läuft weiter; alle anderen Tabs wie beim normalen Minimieren
        for (int i = 0; i < webViews.length; i++) {
            if (i == TAB_YOUTUBE || webViews[i] == null) continue;
            pauseMedia(i);
            webViews[i].onPause();
        }
        panelShown = false;
        miniShown = true;
        applyMiniLayout(true);
        applyMiniSize();
        updatePanelWindow();
        yt.evaluateJavascript(JS_PIP_ENTER, null);
        // Manche Player pausieren beim Fokusverlust/Größenwechsel: einmal nachsehen und fortsetzen
        miniUserToggled = false;
        ui.postDelayed(resumeMiniPlayback, 800);
        setMiniControlsVisible(true); // kurz zeigen, damit man sieht, wie man ihn bedient

        updateBubbleStyle();
        updateTimerUi(); // Timer-Anzeige unter dem Button einblenden
        if (shortcutBubbles != null) shortcutBubbles.setVisible(true);
    }

    /** ✕ im Mini-Player: Video anhalten und Player schließen (wie normales Minimieren). */
    private void closeMiniPlayer() {
        if (!miniShown) return;
        pauseMedia(TAB_YOUTUBE);
        leaveMiniLayout();
        WebView yt = webViews[TAB_YOUTUBE];
        if (yt != null) yt.onPause();
        safeRemove(panel);
        saveState();
        updateBubbleStyle();
        updateTimerUi();
    }

    /** Stellt das normale Popup-Layout wieder her (Fenster bleibt, wie es ist). */
    private void leaveMiniLayout() {
        if (!miniShown) return;
        miniShown = false;
        ui.removeCallbacks(hideMiniControls);
        ui.removeCallbacks(resumeMiniPlayback);
        WebView yt = webViews[TAB_YOUTUBE];
        if (yt != null) yt.evaluateJavascript(JS_PIP_LEAVE, null);
        applyMiniLayout(false);
    }

    /** Schaltet Kopfzeile, Leisten, Fensterflags usw. zwischen Popup und Mini-Player um. */
    private void applyMiniLayout(boolean mini) {
        if (panel == null) return;
        if (panelHeader != null) panelHeader.setVisibility(mini ? View.GONE : View.VISIBLE);
        if (timerBar != null) {
            timerBar.setVisibility(!mini && prefs.getBoolean(KEY_TIMER_BAR, false)
                    ? View.VISIBLE : View.GONE);
        }
        if (progressBar != null) progressBar.setVisibility(mini ? View.GONE : View.INVISIBLE);
        if (keysView != null && mini) keysView.setVisibility(View.GONE);
        if (errorView != null && mini) errorView.setVisibility(View.GONE);
        if (contentFrame != null) contentFrame.setBackgroundColor(mini ? Color.BLACK : Color.WHITE);
        if (miniLayer != null) miniLayer.setVisibility(mini ? View.VISIBLE : View.GONE);
        if (!mini) setMiniControlsVisible(false);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(mini ? Color.BLACK : COLOR_BG);
        bg.setCornerRadius(dp(mini ? 14 : 10));
        bg.setStroke(dp(mini ? 2 : 1), COLOR_GOLD);
        panel.setBackground(bg);
        int pad = dp(mini ? 2 : 1);
        panel.setPadding(pad, pad, pad, pad);

        // FLAG_HARDWARE_ACCELERATED setzt addView() nur beim ersten Hinzufügen; hier mitgeben,
        // damit das Video im Mini-Player nicht ohne GPU gezeichnet wird.
        int base = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        if (mini) {
            // nicht fokussierbar: Tastatur und Zurück-Taste gehen wieder ans Spiel
            panelParams.flags = base | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            panelParams.gravity = Gravity.TOP | Gravity.START;
        } else {
            panelParams.flags = base;
            panelParams.gravity = Gravity.CENTER;
            panelParams.x = 0;
            panelParams.y = 0;
        }
    }

    /** Größe (16:9) und Position des Mini-Players; Standard: rechts, vertikal mittig. */
    private void applyMiniSize() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        boolean landscape = dm.widthPixels > dm.heightPixels;
        int margin = dp(MINI_MARGIN_DP);
        int w = (int) (dm.widthPixels * (landscape ? 0.32f : 0.62f));
        w = Math.max(dp(MINI_MIN_W_DP), Math.min(w, dp(MINI_MAX_W_DP)));
        w = Math.min(w, dm.widthPixels - 2 * margin);
        int border = 2 * dp(2);
        panelParams.width = w;
        panelParams.height = (w - border) * 9 / 16 + border;
        panelParams.x = getOrientedInt(KEY_MINI_X, dm.widthPixels - panelParams.width - margin);
        panelParams.y = getOrientedInt(KEY_MINI_Y, (dm.heightPixels - panelParams.height) / 2);
        clampMini();
    }

    private void clampMini() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        panelParams.x = Math.max(0, Math.min(panelParams.x, dm.widthPixels - panelParams.width));
        panelParams.y = Math.max(0, Math.min(panelParams.y, dm.heightPixels - panelParams.height));
    }

    /** Fenstergröße/-flags des Popups übernehmen (Fenster ist bereits hinzugefügt). */
    private void updatePanelWindow() {
        try {
            windowManager.updateViewLayout(panel, panelParams);
        } catch (Exception ignored) {
        }
    }

    /**
     * Bedienelemente über dem Video: ⤢ zurück in den Hub, ▶/❚❚, ✕ schließen. Die Ebene fängt
     * alle Touches ab: Ziehen verschiebt den Player, Tippen blendet die Knöpfe ein/aus.
     */
    private FrameLayout buildMiniLayer() {
        FrameLayout layer = new FrameLayout(this);
        final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        layer.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;
            private float downX, downY;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = panelParams.x;
                        startY = panelParams.y;
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
                        if (dragging && miniShown) {
                            panelParams.x = startX + (int) dx;
                            panelParams.y = startY + (int) dy;
                            clampMini();
                            updatePanelWindow();
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (dragging) {
                            saveOrientedPosition(KEY_MINI_X, KEY_MINI_Y, panelParams.x, panelParams.y);
                        } else {
                            setMiniControlsVisible(miniControls.getVisibility() != View.VISIBLE);
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                }
                return false;
            }
        });

        miniControls = new FrameLayout(this);
        miniControls.setBackgroundColor(0x80000000);
        miniControls.setVisibility(View.GONE);
        layer.addView(miniControls, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView expand = new ImageView(this);
        expand.setImageDrawable(makeExpandIcon());
        expand.setScaleType(ImageView.ScaleType.CENTER);
        expand.setBackground(miniButtonBackground());
        expand.setOnClickListener(v -> showPanel());
        FrameLayout.LayoutParams expandLp = new FrameLayout.LayoutParams(dp(36), dp(36),
                Gravity.TOP | Gravity.START);
        expandLp.setMargins(dp(6), dp(6), 0, 0);
        miniControls.addView(expand, expandLp);

        TextView close = makeHeaderButton("✕");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        close.setBackground(miniButtonBackground());
        close.setOnClickListener(v -> closeMiniPlayer());
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(dp(36), dp(36),
                Gravity.TOP | Gravity.END);
        closeLp.setMargins(0, dp(6), dp(6), 0);
        miniControls.addView(close, closeLp);

        miniPlayPause = makeHeaderButton("❚❚");
        miniPlayPause.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        miniPlayPause.setBackground(miniButtonBackground());
        miniPlayPause.setOnClickListener(v -> toggleMiniPlayback());
        miniControls.addView(miniPlayPause, new FrameLayout.LayoutParams(dp(48), dp(48),
                Gravity.CENTER));
        return layer;
    }

    private GradientDrawable miniButtonBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x99000000);
        bg.setStroke(dp(1), COLOR_GOLD);
        return bg;
    }

    /** Knöpfe ein-/ausblenden; während das Video läuft, verschwinden sie nach kurzer Zeit. */
    private void setMiniControlsVisible(boolean show) {
        if (miniControls == null) return;
        ui.removeCallbacks(hideMiniControls);
        miniControls.setVisibility(show && miniShown ? View.VISIBLE : View.GONE);
        if (!show || !miniShown) return;
        WebView yt = webViews[TAB_YOUTUBE];
        if (yt == null) return;
        yt.evaluateJavascript(JS_PIP_IS_PLAYING, r -> onMiniPlaybackState("true".equals(r)));
    }

    private void toggleMiniPlayback() {
        WebView yt = webViews[TAB_YOUTUBE];
        if (yt == null) return;
        miniUserToggled = true;
        yt.evaluateJavascript(JS_PIP_TOGGLE, r -> onMiniPlaybackState("true".equals(r)));
    }

    private void onMiniPlaybackState(boolean playing) {
        if (!miniShown || miniPlayPause == null) return;
        miniPlayPause.setText(playing ? "❚❚" : "▶");
        ui.removeCallbacks(hideMiniControls);
        // pausiert: Knöpfe stehen lassen, damit man direkt weiterspielen kann
        if (playing && miniControls.getVisibility() == View.VISIBLE) {
            ui.postDelayed(hideMiniControls, MINI_CONTROLS_MS);
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
        panelHeader = header;

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
            if (i == TAB_YOUTUBE) {
                tab.setCompoundDrawablesRelative(makePlayIcon(), null, null, null);
                tab.setCompoundDrawablePadding(dp(6));
            }
            tab.setOnClickListener(v -> selectTab(index));
            tabButtons[i] = tab;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
            lp.setMarginEnd(dp(4));
            tabRow.addView(tab, lp);
        }

        webNavButtons.add(addNavButton(header, "←", v -> {
            if (exitFullscreen()) return;
            WebView wv = webViews[currentTab];
            if (wv != null && wv.canGoBack()) wv.goBack();
        }));
        webNavButtons.add(addNavButton(header, "→", v -> {
            WebView wv = webViews[currentTab];
            if (wv != null && wv.canGoForward()) wv.goForward();
        }));
        webNavButtons.add(addNavButton(header, "↻", v -> {
            WebView wv = webViews[currentTab];
            if (wv != null) wv.reload();
        }));
        webNavButtons.add(addNavButton(header, "⌂", v -> {
            exitFullscreen();
            WebView wv = webViews[currentTab];
            if (wv != null) wv.loadUrl(TAB_HOME[currentTab]); // YouTube: Standardsuche
        }));
        // Nur im YouTube-Tab sichtbar (siehe selectTab): aktuelle Seite in YouTube-App/Browser
        externalButton = addNavButton(header, "↗", v -> openCurrentPageExternally());
        externalButton.setVisibility(View.GONE);
        timerBarButton = addNavButton(header, "⏱", v -> toggleTimerBar());
        addNavButton(header, "—", v -> minimizePanel()); // Minimieren (YouTube: Mini-Player)

        // --- Timer-Leiste (über ⏱ ein-/ausblendbar)
        timerBar = buildTimerBar();
        timerBar.setVisibility(prefs.getBoolean(KEY_TIMER_BAR, false) ? View.VISIBLE : View.GONE);
        column.addView(timerBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        // --- Ladebalken
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(COLOR_GOLD));
        column.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        // --- WebViews
        contentFrame = new FrameLayout(this);
        contentFrame.setBackgroundColor(Color.WHITE);
        column.addView(contentFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        for (int i = 0; i < TAB_TITLES.length; i++) {
            if (TAB_HOME[i] == null) continue; // kein Web-Tab (Keys)
            WebView wv = createWebView(i);
            wv.setVisibility(View.GONE);
            webViews[i] = wv;
            contentFrame.addView(wv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // --- Keys-Tab: Tasten-Shortcuts anlegen/entfernen
        keysView = buildKeysView();
        keysView.setVisibility(View.GONE);
        contentFrame.addView(keysView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // --- Fehleranzeige (über der WebView des aktiven Tabs)
        errorView = buildErrorView();
        errorView.setVisibility(View.GONE);
        contentFrame.addView(errorView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // --- Bedienebene des Mini-Players (nur im Bild-in-Bild-Modus sichtbar)
        miniLayer = buildMiniLayer();
        miniLayer.setVisibility(View.GONE);
        panel.addView(miniLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        panelParams = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                overlayType(),
                // fokussierbar (Tastatur für die Wiki-Suche), Touches außerhalb gehen ans Spiel
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.CENTER;
        panelParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
    }

    /**
     * WebView, die im Mini-Player weiter meldet, ihr Fenster habe den Fokus. Das Mini-Fenster ist
     * nicht fokussierbar (Tastatur/Zurück gehen ans Spiel); ohne diesen Kniff bekäme die Seite
     * „blur“ und YouTube hielte das Video an.
     */
    private final class HubWebView extends WebView {
        HubWebView(Context context) {
            super(context);
        }

        @Override
        public void onWindowFocusChanged(boolean hasWindowFocus) {
            super.onWindowFocusChanged(hasWindowFocus || miniShown);
        }

        @Override
        public boolean hasWindowFocus() {
            return miniShown || super.hasWindowFocus();
        }
    }

    private WebView createWebView(final int tabIndex) {
        WebView wv = new HubWebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true); // kein Autoplay mit Ton beim Wiederherstellen
        // Normaler HTTP-Cache: schnelle Tab-Wechsel/Zurück, aber keine veralteten Seiten
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // z. B. Logins/Einbettungen auf GW Market oder der Karte
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
        if (tabIndex == TAB_YOUTUBE) setUpYouTubeWebView(wv);

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(view, request.getUrl().toString());
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (tabErrors[tabIndex] != null) {
                    tabErrors[tabIndex] = null;
                    if (tabIndex == currentTab) updateErrorView();
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                // Nur Fehler der Hauptseite anzeigen, nicht von Bildern/Werbung/Tracking
                if (request == null || !request.isForMainFrame()) return;
                tabErrors[tabIndex] = new TabError(request.getUrl().toString(),
                        error.getErrorCode(), String.valueOf(error.getDescription()));
                if (tabIndex == currentTab) updateErrorView();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (isRestorable(tabIndex, url) && prefs != null) {
                    prefs.edit().putString(KEY_URL_PREFIX + tabIndex, url).apply();
                }
                // Neue Seite im Mini-Player geladen: wieder nur das Video zeigen
                if (miniShown && tabIndex == TAB_YOUTUBE) view.evaluateJavascript(JS_PIP_ENTER, null);
            }
        });
        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (tabIndex == currentTab && progressBar != null && !miniShown) {
                    progressBar.setProgress(newProgress);
                    progressBar.setVisibility(newProgress >= 100 ? View.INVISIBLE : View.VISIBLE);
                }
            }

            // Vollbild-Taste im Video-Player: Video füllt den Inhaltsbereich des Popups
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (tabIndex != currentTab || customView != null || contentFrame == null
                        || miniShown) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                view.setBackgroundColor(Color.BLACK);
                contentFrame.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }
        });
        return wv;
    }

    /**
     * YouTube-Tab: Autoplay mit Ton erlauben, sobald der Nutzer die Seite berührt hat. YouTube
     * startet ein angetipptes Video erst, wenn die Seite geladen ist – dauert das neben dem Spiel
     * länger, ist die Geste verfallen und der Start scheitert. Bis zur ersten Berührung bleibt
     * die Geste Pflicht, ein wiederhergestelltes Video startet also nicht von selbst mit Ton.
     *
     * Bewusst kein eigener Audio-Fokus und kein erzwungenes H.264: Die WebView holt sich beim
     * Abspielen selbst den Audio-Fokus. Ein zweiter Fokus-Request derselben App nimmt ihn ihr
     * wieder weg, Chromium pausiert dann das Video und setzt es fort, sobald er frei wird – im
     * Wechsel mit Play/Pause-Meldungen ergibt das ein Dauer-Ruckeln.
     */
    private void setUpYouTubeWebView(WebView wv) {
        wv.setOnTouchListener((v, e) -> {
            if (!youTubeTouched && e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                youTubeTouched = true;
                ((WebView) v).getSettings().setMediaPlaybackRequiresUserGesture(false);
            }
            return false; // Touch normal an die Seite weitergeben
        });
    }

    /**
     * Entscheidet, wo ein angeklickter Link geöffnet wird. http(s) bleibt in der WebView des Tabs.
     * Versuche, die YouTube-App zu öffnen (intent://, vnd.youtube:), werden in die passende
     * YouTube-Seite im Tab übersetzt; alle anderen Schemes (Play Store, mailto: …) werden ignoriert.
     *
     * @return true, wenn die WebView die URL selbst NICHT laden soll
     */
    private boolean handleNavigation(WebView view, String url) {
        if (YouTubeLinks.isWebUrl(url)) return false;
        String inTab = YouTubeLinks.resolveAppLink(url);
        if (inTab != null) view.loadUrl(inTab);
        return true;
    }

    /** Welche URLs je Tab gespeichert/wiederhergestellt werden. */
    private static boolean isRestorable(int tabIndex, String url) {
        if (tabIndex == TAB_YOUTUBE) return YouTubeLinks.isYouTubeUrl(url);
        return url != null && url.startsWith("http");
    }

    /** Adresse beim ersten Anzeigen eines Tabs: gespeicherte Seite oder Startseite. */
    private String initialUrl(int tabIndex) {
        String saved = prefs.getString(KEY_URL_PREFIX + tabIndex, null);
        if (tabIndex == TAB_YOUTUBE) return YouTubeLinks.startUrl(saved);
        return saved != null ? saved : TAB_HOME[tabIndex];
    }

    /** Pausiert Video/Audio in einem Tab (nur wenn dort schon etwas geladen wurde). */
    private void pauseMedia(int tabIndex) {
        if (tabIndex < 0 || tabIndex >= webViews.length || !tabLoaded[tabIndex]) return;
        WebView wv = webViews[tabIndex];
        if (wv == null) return;
        try {
            wv.evaluateJavascript(JS_PAUSE_MEDIA, null);
        } catch (Exception ignored) {
        }
    }

    /** Beendet ein Vollbild-Video. @return true, wenn eines angezeigt wurde. */
    private boolean exitFullscreen() {
        if (customView == null) return false;
        View v = customView;
        WebChromeClient.CustomViewCallback cb = customViewCallback;
        customView = null;
        customViewCallback = null;
        if (contentFrame != null) contentFrame.removeView(v);
        if (cb != null) {
            try {
                cb.onCustomViewHidden();
            } catch (Exception ignored) {
            }
        }
        return true;
    }

    /** "Extern öffnen": aktuelle Seite an die YouTube-App bzw. den Browser übergeben. */
    private void openCurrentPageExternally() {
        WebView wv = webViews[currentTab];
        String url = wv != null ? wv.getUrl() : null;
        if (!YouTubeLinks.isWebUrl(url)) url = TAB_HOME[currentTab];
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
            hidePanel(); // pausiert auch das Video im Overlay
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app found to open this link", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ Fehleranzeige

    private LinearLayout buildErrorView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0xFF1C1A17);
        box.setPadding(dp(24), dp(24), dp(24), dp(24));
        box.setClickable(true); // Touches nicht an die WebView darunter durchreichen

        TextView title = new TextView(this);
        title.setText("Page couldn't be loaded");
        title.setTextColor(COLOR_GOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        errorMessage = new TextView(this);
        errorMessage.setTextColor(COLOR_TEXT);
        errorMessage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        errorMessage.setGravity(Gravity.CENTER);
        errorMessage.setPadding(0, dp(8), 0, 0);
        box.addView(errorMessage);

        errorDetail = new TextView(this);
        errorDetail.setTextColor(0x99EDE6D6);
        errorDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        errorDetail.setGravity(Gravity.CENTER);
        errorDetail.setPadding(0, dp(4), 0, dp(16));
        box.addView(errorDetail);

        TextView retry = makeHeaderButton("Try again");
        retry.setTextColor(0xFF1C1A17);
        retry.setTypeface(Typeface.DEFAULT_BOLD);
        retry.setPadding(dp(20), 0, dp(20), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(6));
        bg.setColor(COLOR_GOLD);
        retry.setBackground(bg);
        retry.setOnClickListener(v -> retryCurrentTab());
        box.addView(retry, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        return box;
    }

    private void updateErrorView() {
        if (errorView == null) return;
        TabError err = tabErrors[currentTab];
        if (err == null || miniShown) {
            errorView.setVisibility(View.GONE);
            return;
        }
        errorMessage.setText(err.friendlyMessage());
        errorDetail.setText(err.description);
        errorView.setVisibility(View.VISIBLE);
    }

    private void retryCurrentTab() {
        WebView wv = webViews[currentTab];
        TabError err = tabErrors[currentTab];
        tabErrors[currentTab] = null;
        updateErrorView();
        if (wv == null) return;
        if (err != null && YouTubeLinks.isWebUrl(err.url)) wv.loadUrl(err.url);
        else wv.reload();
    }

    /** Fehler beim Laden der Hauptseite eines Tabs. */
    private static final class TabError {
        final String url;
        final int code;
        final String description;

        TabError(String url, int code, String description) {
            this.url = url;
            this.code = code;
            this.description = description;
        }

        String friendlyMessage() {
            switch (code) {
                case WebViewClient.ERROR_HOST_LOOKUP:
                case WebViewClient.ERROR_CONNECT:
                case WebViewClient.ERROR_TIMEOUT:
                case WebViewClient.ERROR_IO:
                    return "No connection. Check your internet connection and try again.";
                default:
                    return "Something went wrong while loading the page.";
            }
        }
    }

    private void selectTab(int index) {
        int next = clampTab(index);
        if (next != currentTab) {
            // Beim Verlassen eines Tabs Vollbild beenden und Wiedergabe anhalten;
            // Seite und Verlauf des Tabs bleiben erhalten.
            exitFullscreen();
            pauseMedia(currentTab);
        }
        currentTab = next;
        for (int i = 0; i < webViews.length; i++) {
            boolean active = i == currentTab;
            WebView wv = webViews[i];
            if (wv != null) {
                wv.setVisibility(active ? View.VISIBLE : View.GONE);
                // Versteckte Tabs anhalten: alle WebViews teilen sich einen Renderer-Prozess,
                // Werbung/Animationen auf Wiki- oder Markt-Seiten bremsen sonst das Video.
                if (panelShown) {
                    if (active) wv.onResume();
                    else wv.onPause();
                }
            }
            styleTab(tabButtons[i], active);
        }
        if (externalButton != null) {
            externalButton.setVisibility(currentTab == TAB_YOUTUBE ? View.VISIBLE : View.GONE);
        }
        boolean keysTab = currentTab == TAB_KEYS;
        if (keysView != null) keysView.setVisibility(keysTab ? View.VISIBLE : View.GONE);
        for (View b : webNavButtons) b.setVisibility(keysTab ? View.GONE : View.VISIBLE);
        if (keysTab) updateKeysTab();
        else hideKeyboard();
        scrollTabIntoView(currentTab);
        WebView wv = webViews[currentTab];
        if (wv != null && !tabLoaded[currentTab]) {
            tabLoaded[currentTab] = true;
            wv.loadUrl(initialUrl(currentTab));
        }
        updateErrorView();
        if (progressBar != null) {
            int p = wv != null ? wv.getProgress() : 100;
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
                if (isRestorable(i, url)) ed.putString(KEY_URL_PREFIX + i, url);
            }
        }
        ed.apply();
    }

    // ------------------------------------------------------------------ Timer

    /**
     * Timer-Leiste im Hub: Anzeige, Moduswahl (Countdown/Stoppuhr), Presets, ±-Schritte,
     * Start/Pause, Zurücksetzen.
     */
    private LinearLayout buildTimerBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF221F1A);
        bar.setPadding(dp(6), 0, dp(4), 0);

        timerBarTime = new TextView(this);
        timerBarTime.setTextColor(COLOR_GOLD);
        timerBarTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        timerBarTime.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        timerBarTime.setGravity(Gravity.CENTER);
        timerBarTime.setSingleLine(true);
        bar.addView(timerBarTime, new LinearLayout.LayoutParams(dp(68), dp(32)));

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        scroller.addView(chips, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bar.addView(scroller, new LinearLayout.LayoutParams(0, dp(40), 1f));

        timerModeCountdown = addTimerChip(chips, "Countdown", 0x22FFFFFF,
                v -> timerController.setMode(TimerMode.COUNTDOWN));
        timerModeStopwatch = addTimerChip(chips, "Stopwatch", 0x22FFFFFF,
                v -> timerController.setMode(TimerMode.STOPWATCH));
        // kleiner Abstand zwischen Moduswahl und den Countdown-Chips
        ((LinearLayout.LayoutParams) timerModeStopwatch.getLayoutParams()).setMarginEnd(dp(10));

        for (final long preset : TIMER_PRESETS_MS) {
            countdownOnlyChips.add(addTimerChip(chips, TimerFormat.shortLabel(preset), 0x22FFFFFF,
                    v -> timerController.setDuration(preset)));
        }
        for (final long step : TIMER_STEPS_MS) {
            String label = (step < 0 ? "−" : "+") + TimerFormat.shortLabel(Math.abs(step));
            countdownOnlyChips.add(addTimerChip(chips, label, 0x33C9A23F,
                    v -> timerController.adjust(step)));
        }

        timerBarToggle = addNavButton(bar, "▶", v -> timerController.toggle());
        addNavButton(bar, "■", v -> timerController.reset());
        return bar;
    }

    private TextView addTimerChip(LinearLayout row, String label, int color, View.OnClickListener l) {
        TextView chip = makeHeaderButton(label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        chip.setPadding(dp(10), 0, dp(10), 0);
        chip.setOnClickListener(l);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(6));
        bg.setColor(color);
        chip.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
        lp.setMarginEnd(dp(4));
        row.addView(chip, lp);
        return chip;
    }

    private void toggleTimerBar() {
        if (timerBar == null) return;
        boolean show = timerBar.getVisibility() != View.VISIBLE;
        timerBar.setVisibility(show ? View.VISIBLE : View.GONE);
        prefs.edit().putBoolean(KEY_TIMER_BAR, show).apply();
        updateTimerUi();
    }

    /** Mini-Anzeige unter dem Button: eigenes, nicht fokussierbares Overlay-Fenster. */
    private void createTimerBadge() {
        timerBadge = new TimerBadgeView(this);
        timerBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        timerBadge.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        timerBadge.setGravity(Gravity.CENTER);
        timerBadge.setSingleLine(true);
        timerBadge.setIncludeFontPadding(false);
        // Tippen: Pause/Fortsetzen (nach Ablauf: ausblenden). Lang drücken: Neustart.
        timerBadge.setOnClickListener(v -> {
            if (timerController.getState() == TimerState.FINISHED) timerController.reset();
            else timerController.toggle();
        });
        timerBadge.setOnLongClickListener(v -> {
            timerController.restart();
            return true;
        });

        timerBadgeParams = new WindowManager.LayoutParams(
                dp(BADGE_W_DP), dp(BADGE_H_DP), overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        timerBadgeParams.gravity = Gravity.TOP | Gravity.START;
        positionTimerBadge();
    }

    /**
     * Zentriert die Anzeige unter dem Button. Ist unten kein Platz, rutscht sie über den Button;
     * horizontal wird sie im sichtbaren Bereich gehalten.
     */
    private void positionTimerBadge() {
        if (timerBadgeParams == null || bubbleParams == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int bubbleSize = dp(BUBBLE_SIZE_DP);
        int w = dp(BADGE_W_DP);
        int h = dp(BADGE_H_DP);
        int gap = dp(BADGE_GAP_DP);

        int x = bubbleParams.x + (bubbleSize - w) / 2;
        int y = bubbleParams.y + bubbleSize + gap;
        if (y + h > dm.heightPixels) y = bubbleParams.y - h - gap;

        timerBadgeParams.x = Math.max(0, Math.min(x, dm.widthPixels - w));
        timerBadgeParams.y = Math.max(0, Math.min(y, dm.heightPixels - h));
        if (timerBadgeShown) safeUpdate(timerBadge, timerBadgeParams);
    }

    private void showTimerBadge() {
        if (timerBadgeShown || timerBadge == null) return; // nie doppelt hinzufügen
        positionTimerBadge();
        try {
            windowManager.addView(timerBadge, timerBadgeParams);
            timerBadgeShown = true;
        } catch (Exception ignored) {
        }
    }

    private void hideTimerBadge() {
        if (!timerBadgeShown) return;
        timerBadgeShown = false;
        try {
            windowManager.removeView(timerBadge);
        } catch (Exception ignored) {
        }
    }

    /** Synchronisiert Hub-Leiste und Mini-Anzeige mit dem zentralen Timer-State. */
    private void updateTimerUi() {
        if (timerController == null) return;
        TimerState state = timerController.getState();
        boolean stopwatch = timerController.getMode() == TimerMode.STOPWATCH;
        int accent = stopwatch ? COLOR_STOPWATCH : COLOR_GOLD;
        String text = timerController.getDisplayText();
        boolean finished = state == TimerState.FINISHED;
        boolean blinkOn = timerController.isFinishedBlinkOn();

        // Mini-Anzeige nur bei minimiertem Hub und gesetztem Timer
        boolean showBadge = !panelShown && bubble != null && state != TimerState.IDLE;
        if (showBadge) showTimerBadge();
        else hideTimerBadge();

        if (timerBadge != null && timerBadgeShown) {
            timerBadge.setText(text);
            timerBadge.setProgress(timerController.getProgress(), accent);
            int bg;
            int fg;
            int stroke;
            if (finished) {
                bg = blinkOn ? COLOR_BUBBLE_ACTIVE : 0xF01C1A17;
                fg = blinkOn ? Color.WHITE : 0xFFFF5A4A;
                stroke = 0xFFFF5A4A;
            } else if (state == TimerState.PAUSED) {
                bg = 0xE01C1A17;
                fg = 0xB3EDE6D6;
                stroke = 0x99EDE6D6;
            } else {
                bg = 0xE61C1A17;
                fg = COLOR_TEXT;
                stroke = accent; // Gold = Countdown, Blau = Stoppuhr
            }
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(dp(BADGE_H_DP / 2f));
            d.setColor(bg);
            d.setStroke(dp(1), stroke);
            timerBadge.setBackground(d);
            timerBadge.setTextColor(fg);
        }

        if (timerBarTime != null) {
            timerBarTime.setText(text);
            timerBarTime.setTextColor(finished
                    ? (blinkOn ? 0xFFFF5A4A : COLOR_TEXT)
                    : state == TimerState.PAUSED ? 0xB3EDE6D6 : accent);
        }
        styleTab(timerModeCountdown, !stopwatch);
        styleTab(timerModeStopwatch, stopwatch);
        for (View chip : countdownOnlyChips) {
            chip.setVisibility(stopwatch ? View.GONE : View.VISIBLE);
        }
        if (timerBarToggle != null) {
            timerBarToggle.setText(state == TimerState.RUNNING ? "❚❚" : "▶");
        }
        if (timerBarButton != null && timerBar != null) {
            // ⏱ hervorheben, wenn die Leiste offen ist oder ein Timer läuft
            boolean highlight = timerBar.getVisibility() == View.VISIBLE || state != TimerState.IDLE;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(6));
            bg.setColor(highlight ? 0x66C9A23F : 0x22FFFFFF);
            timerBarButton.setBackground(bg);
        }
    }

    // ------------------------------------------------------------------ Tasten-Shortcuts

    private static final String SHIZUKU_PACKAGE = KeySender.SHIZUKU_PACKAGE;

    private void createShortcuts() {
        keySender = new KeySender(this, new KeySender.Listener() {
            @Override
            public void onStatusChanged() {
                updateKeysTab();
            }

            @Override
            public void onSendFailed(String message) {
                Toast.makeText(OverlayService.this, message, Toast.LENGTH_SHORT).show();
                updateKeysTab();
            }
        });
        shortcutBubbles = new ShortcutBubbles(this, windowManager, prefs, keySender,
                new ShortcutBubbles.Host() {
                    @Override
                    public int overlayType() {
                        return OverlayService.this.overlayType();
                    }

                    @Override
                    public int dp(float v) {
                        return OverlayService.this.dp(v);
                    }
                });
        shortcutBubbles.setVisible(!panelShown);
    }

    /** Inhalt des Keys-Tabs: Eingabefeld + Plus, Sende-Status, Liste der Shortcuts. */
    private LinearLayout buildKeysView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1C1A17);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(14));
        scroll.addView(box, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Keyboard shortcuts");
        title.setTextColor(COLOR_GOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Type a key name (e.g. Einfg, Entf, Strg, F5, Strg+C, 1 (ZB)) or a character "
                + "of the German keyboard (ü, ö, ä, ß, #, <, *, @ …) and tap +. "
                + "A bubble appears over the game: tap it to press the key, drag it to move it.");
        hint.setTextColor(0xCCEDE6D6);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setPadding(0, dp(4), 0, dp(10));
        box.addView(hint);

        // --- Eingabe + Plus
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        keyInput = new EditText(this);
        keyInput.setHint("Key, e.g. Einfg");
        keyInput.setHintTextColor(0x80EDE6D6);
        keyInput.setTextColor(COLOR_TEXT);
        keyInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        keyInput.setSingleLine(true);
        // keine Autokorrektur/Vorschläge, damit "entf" nicht zu "erst" wird
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        keyInput.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        keyInput.setPadding(dp(10), 0, dp(10), 0);
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setCornerRadius(dp(6));
        inputBg.setColor(0x22FFFFFF);
        inputBg.setStroke(dp(1), 0x66C9A23F);
        keyInput.setBackground(inputBg);
        keyInput.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_DONE || enter) {
                addShortcutFromInput();
                return true;
            }
            return false;
        });
        keyInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                showKeyPreview(s.toString());
            }
        });
        // Antippen öffnet die Bildschirmtastatur (auch, wenn das Feld schon fokussiert ist)
        keyInput.setOnClickListener(v -> showKeyboard());
        row.addView(keyInput, new LinearLayout.LayoutParams(0, dp(44), 1f));

        TextView plus = makeHeaderButton("+");
        plus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        plus.setTypeface(Typeface.DEFAULT_BOLD);
        plus.setTextColor(0xFF1C1A17);
        GradientDrawable plusBg = new GradientDrawable();
        plusBg.setCornerRadius(dp(6));
        plusBg.setColor(COLOR_GOLD);
        plus.setBackground(plusBg);
        plus.setOnClickListener(v -> addShortcutFromInput());
        LinearLayout.LayoutParams plusLp = new LinearLayout.LayoutParams(dp(48), dp(44));
        plusLp.setMarginStart(dp(8));
        row.addView(plus, plusLp);

        keyFeedback = new TextView(this);
        keyFeedback.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        keyFeedback.setPadding(0, dp(6), 0, dp(6));
        box.addView(keyFeedback);

        // --- Status: womit werden Tasten gesendet?
        LinearLayout statusBox = new LinearLayout(this);
        statusBox.setOrientation(LinearLayout.VERTICAL);
        statusBox.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable statusBg = new GradientDrawable();
        statusBg.setCornerRadius(dp(6));
        statusBg.setColor(COLOR_HEADER);
        statusBox.setBackground(statusBg);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = dp(4);
        box.addView(statusBox, statusLp);

        keyStatus = new TextView(this);
        keyStatus.setTextColor(COLOR_TEXT);
        keyStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statusBox.addView(keyStatus);

        keyStatusAction = makeHeaderButton("");
        keyStatusAction.setTextColor(0xFF1C1A17);
        keyStatusAction.setTypeface(Typeface.DEFAULT_BOLD);
        keyStatusAction.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable actionBg = new GradientDrawable();
        actionBg.setCornerRadius(dp(6));
        actionBg.setColor(COLOR_GOLD);
        keyStatusAction.setBackground(actionBg);
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        actionLp.topMargin = dp(8);
        statusBox.addView(keyStatusAction, actionLp);

        // Reconnect: Shizuku läuft, kommt aber nicht an (Hub lief schon vor Shizuku)
        keyReconnect = makeHeaderButton("Shizuku is running? Reconnect");
        keyReconnect.setTextColor(COLOR_GOLD);
        keyReconnect.setTypeface(Typeface.DEFAULT_BOLD);
        keyReconnect.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable reconnectBg = new GradientDrawable();
        reconnectBg.setCornerRadius(dp(6));
        reconnectBg.setStroke(dp(1), COLOR_GOLD);
        keyReconnect.setBackground(reconnectBg);
        keyReconnect.setOnClickListener(v -> reconnectShizuku());
        keyReconnect.setVisibility(View.GONE);
        LinearLayout.LayoutParams reconnectLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        reconnectLp.topMargin = dp(8);
        statusBox.addView(keyReconnect, reconnectLp);

        // Test: harmlose Taste (Umschalt) senden und Ergebnis anzeigen
        TextView testButton = makeHeaderButton("Test key sending");
        testButton.setTextColor(COLOR_GOLD);
        testButton.setTypeface(Typeface.DEFAULT_BOLD);
        testButton.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable testBg = new GradientDrawable();
        testBg.setCornerRadius(dp(6));
        testBg.setStroke(dp(1), COLOR_GOLD);
        testButton.setBackground(testBg);
        testButton.setOnClickListener(v -> runKeyTest());
        LinearLayout.LayoutParams testLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        testLp.topMargin = dp(8);
        statusBox.addView(testButton, testLp);

        keyTestResult = new TextView(this);
        keyTestResult.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        keyTestResult.setTextColor(0xB3EDE6D6);
        keyTestResult.setText("Sends a harmless Shift press and shows whether it worked.");
        keyTestResult.setPadding(0, dp(6), 0, 0);
        statusBox.addView(keyTestResult);

        // Technischer Steckbrief für die Fehlersuche
        keyDiag = new TextView(this);
        keyDiag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        keyDiag.setTextColor(0x80EDE6D6);
        keyDiag.setTypeface(Typeface.MONOSPACE);
        keyDiag.setPadding(0, dp(8), 0, 0);
        statusBox.addView(keyDiag);

        // --- Hauptschalter: aus = keine Bubbles über dem Spiel, Liste ausgeblendet
        keysEnabledSwitch = new Switch(this);
        keysEnabledSwitch.setText("Shortcuts enabled");
        keysEnabledSwitch.setTextColor(COLOR_TEXT);
        keysEnabledSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        keysEnabledSwitch.setTypeface(Typeface.DEFAULT_BOLD);
        int[][] switchStates = {{android.R.attr.state_checked}, {}};
        keysEnabledSwitch.setThumbTintList(new ColorStateList(switchStates,
                new int[]{COLOR_GOLD, 0xFFB0A898}));
        keysEnabledSwitch.setTrackTintList(new ColorStateList(switchStates,
                new int[]{0x88C9A23F, 0x44EDE6D6}));
        keysEnabledSwitch.setChecked(shortcutBubbles == null || shortcutBubbles.isEnabled());
        keysEnabledSwitch.setOnCheckedChangeListener((b, on) -> {
            if (shortcutBubbles != null) shortcutBubbles.setEnabled(on);
            applyShortcutsEnabled(on);
            setKeyFeedback(on ? "Shortcuts enabled." : "Shortcuts disabled. The bubbles are hidden "
                    + "but kept.", false);
        });
        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        switchLp.topMargin = dp(10);
        box.addView(keysEnabledSwitch, switchLp);

        // --- Liste der Shortcuts
        TextView listTitle = new TextView(this);
        keyListTitle = listTitle;
        listTitle.setText("Your shortcuts");
        listTitle.setTextColor(COLOR_GOLD);
        listTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        listTitle.setTypeface(Typeface.DEFAULT_BOLD);
        listTitle.setPadding(0, dp(14), 0, dp(6));
        box.addView(listTitle);

        keyList = new LinearLayout(this);
        keyList.setOrientation(LinearLayout.VERTICAL);
        box.addView(keyList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        applyShortcutsEnabled(keysEnabledSwitch.isChecked());

        showKeyPreview("");
        return root;
    }

    /** Liste samt Überschrift nur zeigen, wenn die Shortcuts eingeschaltet sind. */
    private void applyShortcutsEnabled(boolean on) {
        int vis = on ? View.VISIBLE : View.GONE;
        if (keyListTitle != null) keyListTitle.setVisibility(vis);
        if (keyList != null) keyList.setVisibility(vis);
    }

    private void addShortcutFromInput() {
        if (keyInput == null || shortcutBubbles == null) return;
        String text = keyInput.getText().toString();
        String error = shortcutBubbles.add(text, bubbleParams.x, bubbleParams.y);
        if (error != null) {
            setKeyFeedback(error, true);
            return;
        }
        String label = KeyNames.parse(text).label;
        keyInput.setText("");
        setKeyFeedback("Added " + label + (shortcutBubbles.isEnabled()
                ? ". Minimize the hub (—) to use the bubble."
                : ". Turn on \"Shortcuts enabled\" to use the bubble."), false);
        updateKeysTab();
    }

    /** Live-Vorschau beim Tippen: erkannte Taste oder Hinweis. */
    private void showKeyPreview(String text) {
        if (keyFeedback == null) return;
        if (text.trim().isEmpty()) {
            setKeyFeedback("Combine keys with +, e.g. Alt+F4", false);
            return;
        }
        KeyNames.Result r = KeyNames.parse(text);
        if (r.isValid()) setKeyFeedback("→ " + r.label, false);
        else setKeyFeedback(r.error, true);
    }

    private void setKeyFeedback(String text, boolean error) {
        if (keyFeedback == null) return;
        keyFeedback.setText(text);
        keyFeedback.setTextColor(error ? 0xFFFF7A6A : 0xB3EDE6D6);
    }

    /** Status-Box und Liste im Keys-Tab aktualisieren. */
    private void updateKeysTab() {
        if (keysView == null || keySender == null) return;
        KeySender.Status status = keySender.getStatus();
        switch (status) {
            case SHIZUKU_READY:
                keyStatus.setText("✓ Keys are sent to the game via Shizuku.");
                keyStatusAction.setVisibility(View.GONE);
                break;
            case SHIZUKU_NEEDS_PERMISSION:
                keyStatus.setText("Shizuku is running, but GWR Hub isn't allowed to use it yet.");
                keyStatusAction.setText("Allow in Shizuku");
                keyStatusAction.setOnClickListener(v -> {
                    hidePanel(); // Shizuku-Dialog läge sonst unter dem Popup
                    keySender.requestShizukuPermission();
                });
                keyStatusAction.setVisibility(View.VISIBLE);
                break;
            case ROOT:
                keyStatus.setText("✓ Keys are sent to the game via root. "
                        + "Allow GWR Hub when your root app asks.");
                keyStatusAction.setVisibility(View.GONE);
                break;
            case SHIZUKU_OUTDATED:
                keyStatus.setText("Your Shizuku version is too old. Update Shizuku to v11 or newer.");
                keyStatusAction.setText("Update Shizuku");
                keyStatusAction.setOnClickListener(v -> openShizukuStore());
                keyStatusAction.setVisibility(View.VISIBLE);
                break;
            case SHIZUKU_NOT_RUNNING:
                keyStatus.setText("Shizuku is installed but not running. Open Shizuku and start it "
                        + "(via Wireless debugging, or root). Without root you have to start it "
                        + "again after every phone restart.");
                keyStatusAction.setText("Open Shizuku");
                keyStatusAction.setOnClickListener(v -> openShizuku());
                keyStatusAction.setVisibility(View.VISIBLE);
                break;
            default:
                keyStatus.setText("Android only lets apps press keys in other apps with extra "
                        + "rights. Install and start Shizuku (no root needed, set up once via "
                        + "wireless debugging) or use a rooted phone.");
                keyStatusAction.setText("Get Shizuku");
                keyStatusAction.setOnClickListener(v -> openShizuku());
                keyStatusAction.setVisibility(View.VISIBLE);
                break;
        }
        boolean found = isShizukuFound(status);
        if (keyReconnect != null) {
            // Auch bei Root anbieten: dort hat su Vorrang, Shizuku kann trotzdem installiert sein
            boolean offer = status == KeySender.Status.SHIZUKU_NOT_RUNNING
                    || (status == KeySender.Status.ROOT && keySender.isShizukuInstalled());
            keyReconnect.setVisibility(offer ? View.VISIBLE : View.GONE);
        }
        if (shizukuReconnecting && found) {
            // Binder ist angekommen (evtl. erst nach der Prüfung)
            shizukuReconnecting = false;
            ui.removeCallbacks(shizukuReconnectCheck);
            showReconnectResult(true, status);
        }
        if (keyDiag != null) keyDiag.setText(keySender.diagnostics());
        refreshKeyList();
    }

    private static boolean isShizukuFound(KeySender.Status status) {
        return status == KeySender.Status.SHIZUKU_READY
                || status == KeySender.Status.SHIZUKU_NEEDS_PERMISSION
                || status == KeySender.Status.SHIZUKU_OUTDATED;
    }

    /**
     * Shizuku schickt seine Verbindung nur, wenn GWR Hub startet oder in den Vordergrund kommt.
     * Eine unsichtbare Activity holt den Prozess kurz nach vorne, ohne das Spiel zu verlassen.
     */
    private void reconnectShizuku() {
        if (keySender == null) return;
        shizukuReconnecting = true;
        if (keyTestResult != null) {
            keyTestResult.setTextColor(0xB3EDE6D6);
            keyTestResult.setText("Reconnecting to Shizuku…");
        }
        try {
            Intent intent = new Intent(this, ShizukuWakeActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(intent);
        } catch (Exception e) {
            android.util.Log.w("GWRHub.Keys", "Couldn't start ShizukuWakeActivity", e);
        }
        ui.removeCallbacks(shizukuReconnectCheck);
        ui.postDelayed(shizukuReconnectCheck, 2500);
    }

    private void finishShizukuReconnect() {
        if (!shizukuReconnecting || keySender == null) return;
        shizukuReconnecting = false;
        KeySender.Status status = keySender.getStatus();
        showReconnectResult(isShizukuFound(status), status);
        updateKeysTab();
    }

    private void showReconnectResult(boolean found, KeySender.Status status) {
        if (keyTestResult == null) return;
        if (found) {
            keyTestResult.setTextColor(0xFF8FD18A);
            keyTestResult.setText(status == KeySender.Status.SHIZUKU_NEEDS_PERMISSION
                    ? "✓ Connected to Shizuku. Now tap \"Allow in Shizuku\"."
                    : "✓ Connected to Shizuku.");
        } else {
            keyTestResult.setTextColor(0xFFFF7A6A);
            keyTestResult.setText("✗ Shizuku still not found. Check that the Shizuku app says it "
                    + "is running, then try again. If it keeps failing, force-stop GWR Hub "
                    + "(Settings → Apps) and open it again.");
        }
    }

    /** Test-Button: harmlose Taste senden und Ergebnis samt Dauer anzeigen. */
    private void runKeyTest() {
        if (keySender == null || keyTestResult == null) return;
        keyTestResult.setTextColor(0xB3EDE6D6);
        keyTestResult.setText("Testing…");
        keySender.sendTest((ok, message, millis) -> {
            if (keyTestResult == null) return;
            if (ok) {
                keyTestResult.setTextColor(0xFF8FD18A);
                keyTestResult.setText("✓ Test OK: key was sent (" + millis + " ms).");
            } else {
                keyTestResult.setTextColor(0xFFFF7A6A);
                keyTestResult.setText("✗ Test failed: " + message);
            }
            updateKeysTab();
        });
    }

    private void openShizukuStore() {
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=" + SHIZUKU_PACKAGE));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
            hidePanel();
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Search for \"Shizuku\" in the Play Store", Toast.LENGTH_LONG).show();
        }
    }

    private void refreshKeyList() {
        if (keyList == null || shortcutBubbles == null) return;
        keyList.removeAllViews();
        java.util.List<ShortcutBubbles.Shortcut> items = shortcutBubbles.list();
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No shortcuts yet.");
            empty.setTextColor(0x80EDE6D6);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            keyList.addView(empty);
            return;
        }
        for (final ShortcutBubbles.Shortcut s : items) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
            rowLp.bottomMargin = dp(4);
            keyList.addView(row, rowLp);

            TextView pill = makeHeaderButton(s.label);
            pill.setTypeface(Typeface.DEFAULT_BOLD);
            pill.setMinWidth(dp(40));
            pill.setPadding(dp(10), 0, dp(10), 0);
            GradientDrawable pillBg = new GradientDrawable();
            pillBg.setCornerRadius(dp(16));
            pillBg.setColor(COLOR_BUBBLE);
            pillBg.setStroke(dp(1), COLOR_GOLD);
            pill.setBackground(pillBg);
            pill.setClickable(false);
            row.addView(pill, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)));

            // Eigener Text der Bubble; leer = Tastenname. Änderungen gelten sofort und werden gespeichert.
            final EditText name = new EditText(this);
            name.setText(s.displayText);
            name.setHint("Bubble text (" + s.label + ")");
            name.setHintTextColor(0x66EDE6D6);
            name.setTextColor(COLOR_TEXT);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            name.setSingleLine(true);
            name.setFilters(new InputFilter[]{
                    new InputFilter.LengthFilter(ShortcutBubbles.MAX_DISPLAY_TEXT)});
            name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            name.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            name.setPadding(dp(8), 0, dp(8), 0);
            GradientDrawable nameBg = new GradientDrawable();
            nameBg.setCornerRadius(dp(6));
            nameBg.setColor(0x22FFFFFF);
            nameBg.setStroke(dp(1), 0x44C9A23F);
            name.setBackground(nameBg);
            name.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence c, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence c, int start, int before, int count) {}

                @Override
                public void afterTextChanged(Editable e) {
                    shortcutBubbles.setDisplayText(s, e.toString());
                }
            });
            name.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    hideKeyboard(name);
                    return true;
                }
                return false;
            });
            name.setOnClickListener(v -> showKeyboard(name));
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, dp(32), 1f);
            nameLp.setMarginStart(dp(8));
            row.addView(name, nameLp);

            // Auge: Bubble über dem Spiel aus-/einblenden, ohne den Shortcut zu löschen
            final EyeDrawable eye = new EyeDrawable(s.hidden);
            final TextView toggle = addNavButton(row, "", null);
            GradientDrawable toggleBg = new GradientDrawable();
            toggleBg.setCornerRadius(dp(6));
            toggleBg.setColor(0x22FFFFFF);
            android.graphics.drawable.LayerDrawable toggleLayers =
                    new android.graphics.drawable.LayerDrawable(new Drawable[]{toggleBg, eye});
            toggleLayers.setLayerInset(1, dp(9), dp(7), dp(9), dp(7));
            toggle.setBackground(toggleLayers);
            applyShortcutHidden(toggle, pill, name, s.hidden);
            toggle.setOnClickListener(v -> {
                boolean hide = !s.hidden;
                shortcutBubbles.setHidden(s, hide);
                eye.setHidden(hide);
                applyShortcutHidden(toggle, pill, name, hide);
                setKeyFeedback((hide ? "Hidden " : "Showing ") + s.shownText()
                        + (hide ? ". Tap the eye to show it again." : " again."), false);
            });

            TextView remove = addNavButton(row, "✕", v -> {
                shortcutBubbles.remove(s);
                setKeyFeedback("Removed " + s.shownText() + ".", false);
                refreshKeyList();
            });
            remove.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        }
    }

    /** Ausgeblendete Shortcuts in der Liste abgedunkelt zeigen. */
    private static void applyShortcutHidden(View toggle, View pill, View name, boolean hidden) {
        toggle.setContentDescription(hidden ? "Show bubble" : "Hide bubble");
        pill.setAlpha(hidden ? 0.4f : 1f);
        name.setAlpha(hidden ? 0.5f : 1f);
    }

    /** Augen-Symbol; ausgeblendet: durchgestrichen und abgedunkelt. */
    private static final class EyeDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path outline = new Path();
        private boolean hidden;

        EyeDrawable(boolean hidden) {
            this.hidden = hidden;
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        void setHidden(boolean hidden) {
            this.hidden = hidden;
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float w = b.width();
            float h = Math.min(b.height(), w * 0.62f);
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float left = cx - w / 2f, right = cx + w / 2f;
            float stroke = Math.max(1.5f, w * 0.09f);
            paint.setColor(hidden ? 0x80EDE6D6 : COLOR_TEXT);
            paint.setStrokeWidth(stroke);

            // Mandelform aus zwei Kurven
            paint.setStyle(Paint.Style.STROKE);
            outline.reset();
            outline.moveTo(left, cy);
            outline.quadTo(cx, cy - h, right, cy);
            outline.quadTo(cx, cy + h, left, cy);
            outline.close();
            canvas.drawPath(outline, paint);

            // Pupille
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, h * 0.28f, paint);

            if (hidden) {
                paint.setStyle(Paint.Style.STROKE);
                float d = w * 0.45f;
                canvas.drawLine(cx - d, cy + d * 0.8f, cx + d, cy - d * 0.8f, paint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter filter) {
            paint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private void openShizuku() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE);
        if (intent == null) {
            intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + SHIZUKU_PACKAGE));
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
            hidePanel();
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Search for \"Shizuku\" in the Play Store", Toast.LENGTH_LONG).show();
        }
    }

    private void showKeyboard() {
        showKeyboard(keyInput);
    }

    /** Fokus auf das Feld und Bildschirmtastatur öffnen (auch, wenn es schon fokussiert ist). */
    private void showKeyboard(EditText field) {
        if (field == null) return;
        field.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) field.post(() -> imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT));
    }

    private void hideKeyboard() {
        hideKeyboard(keyInput);
    }

    private void hideKeyboard(EditText field) {
        if (field == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
        field.clearFocus();
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

    private TextView addNavButton(LinearLayout header, String label, View.OnClickListener l) {
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
        return b;
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
        // Tab-Icon (z. B. Play-Symbol bei YouTube) in Textfarbe einfärben
        tab.setCompoundDrawableTintList(ColorStateList.valueOf(active ? 0xFF1C1A17 : COLOR_TEXT));
    }

    /** Schlichtes Play-Dreieck als Tab-Icon; Farbe kommt über den Tint aus styleTab. */
    private Drawable makePlayIcon() {
        Path triangle = new Path();
        triangle.moveTo(0f, 0f);
        triangle.lineTo(10f, 6f);
        triangle.lineTo(0f, 12f);
        triangle.close();
        ShapeDrawable icon = new ShapeDrawable(new PathShape(triangle, 10f, 12f));
        icon.setBounds(0, 0, dp(9), dp(11));
        return icon;
    }

    /** „Vergrößern“-Symbol (vier Ecken) für den Mini-Player; als Pfad, da ⤢ nicht jede Schrift hat. */
    private Drawable makeExpandIcon() {
        Path p = new Path();
        p.moveTo(1f, 5f);  p.lineTo(1f, 1f);  p.lineTo(5f, 1f);
        p.moveTo(9f, 1f);  p.lineTo(13f, 1f); p.lineTo(13f, 5f);
        p.moveTo(13f, 9f); p.lineTo(13f, 13f); p.lineTo(9f, 13f);
        p.moveTo(5f, 13f); p.lineTo(1f, 13f); p.lineTo(1f, 9f);
        ShapeDrawable icon = new ShapeDrawable(new PathShape(p, 14f, 14f));
        icon.getPaint().setColor(COLOR_TEXT);
        icon.getPaint().setStyle(Paint.Style.STROKE);
        icon.getPaint().setStrokeWidth(1.8f); // in Pfad-Einheiten, wird mitskaliert
        icon.getPaint().setStrokeCap(Paint.Cap.ROUND);
        icon.getPaint().setAntiAlias(true);
        icon.setIntrinsicWidth(dp(16));
        icon.setIntrinsicHeight(dp(16));
        return icon;
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

    /** Kompakte Timer-Anzeige mit dezentem Fortschrittsstrich am unteren Rand. */
    private class TimerBadgeView extends TextView {
        private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float progress = 1f;

        TimerBadgeView(Context ctx) {
            super(ctx);
            progressPaint.setColor(COLOR_GOLD);
            progressPaint.setStrokeCap(Paint.Cap.ROUND);
            progressPaint.setStrokeWidth(dp(2));
        }

        void setProgress(float p, int color) {
            if (Math.abs(p - progress) < 0.001f && progressPaint.getColor() == color) return;
            progress = p;
            progressPaint.setColor(color);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (progress <= 0f) return;
            float inset = getHeight() / 2f;
            float y = getHeight() - dp(3);
            float len = (getWidth() - 2 * inset) * progress;
            canvas.drawLine(inset, y, inset + len, y, progressPaint);
        }
    }

    /** Root des Popups: Zurück-Taste = Vollbild beenden, im Verlauf zurück, sonst minimieren. */
    private class PanelLayout extends FrameLayout {
        PanelLayout(Context ctx) {
            super(ctx);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    if (exitFullscreen()) return true;
                    WebView wv = webViews[currentTab];
                    if (wv != null && wv.canGoBack()) wv.goBack();
                    else minimizePanel();
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
    }
}
