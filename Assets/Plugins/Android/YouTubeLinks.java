package com.gwrhub.overlay;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * URL-Konfiguration und Link-Erkennung für den YouTube-Tab.
 *
 * Bewusst ohne Android-Abhängigkeiten, damit die Logik per JUnit auf der JVM getestet
 * werden kann (siehe Tests/AndroidPlugin). Nur APIs verwenden, die es auch unter
 * Android API 26 gibt (z. B. kein URLEncoder.encode(String, Charset)).
 */
final class YouTubeLinks {

    static final String DEFAULT_QUERY = "Guild Wars Reforged";
    static final String SEARCH_BASE_URL = "https://m.youtube.com/results?search_query=";
    static final String WATCH_BASE_URL = "https://m.youtube.com/watch?v=";

    /** Startseite des Tabs: https://m.youtube.com/results?search_query=Guild+Wars+Reforged */
    static final String DEFAULT_SEARCH_URL = searchUrl(DEFAULT_QUERY);

    /** Domains (inkl. aller Subdomains wie m., www., music., consent.), die zu YouTube gehören. */
    private static final String[] YOUTUBE_DOMAINS = {
            "youtube.com", "youtu.be", "youtube-nocookie.com"
    };

    /** Schemes, mit denen YouTube versucht, die YouTube-App zu öffnen. */
    private static final String[] YOUTUBE_APP_SCHEMES = {"vnd.youtube", "youtube"};

    private static final String INTENT_MARKER = "#Intent;";

    /** Art eines Links aus Sicht des YouTube-Tabs. */
    enum LinkType {
        /** http(s)-Seite auf einer YouTube-Domain: bleibt im Tab. */
        YOUTUBE,
        /** Andere http(s)-Seite. */
        OTHER_WEB,
        /** Kein http(s): App-/Deep-Link (intent:, vnd.youtube:, market: …), mailto:, javascript: … */
        NON_WEB
    }

    private YouTubeLinks() {
    }

    // ------------------------------------------------------------------ URL-Erzeugung

    /** Mobile YouTube-Suche; leere Suche fällt auf {@link #DEFAULT_QUERY} zurück. */
    static String searchUrl(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) q = DEFAULT_QUERY;
        return SEARCH_BASE_URL + encode(q);
    }

    /** Mobile Watch-Seite für eine Video-ID, oder null bei ungültiger ID. */
    static String watchUrl(String videoId) {
        return isValidVideoId(videoId) ? WATCH_BASE_URL + videoId : null;
    }

    /** YouTube-Video-IDs bestehen aus 11 Zeichen [A-Za-z0-9_-]. */
    static boolean isValidVideoId(String id) {
        if (id == null || id.length() != 11) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    /**
     * Adresse, die der Tab beim ersten Laden öffnet: die gespeicherte Seite, sofern sie zu
     * YouTube gehört, sonst die Standardsuche.
     */
    static String startUrl(String savedUrl) {
        return isYouTubeUrl(savedUrl) ? savedUrl : DEFAULT_SEARCH_URL;
    }

    // ------------------------------------------------------------------ Link-Erkennung

    static LinkType classify(String url) {
        if (!isWebUrl(url)) return LinkType.NON_WEB;
        return isYouTubeHost(hostOf(url)) ? LinkType.YOUTUBE : LinkType.OTHER_WEB;
    }

    static boolean isYouTubeUrl(String url) {
        return classify(url) == LinkType.YOUTUBE;
    }

    /** true für http:// und https:// (Groß-/Kleinschreibung egal). */
    static boolean isWebUrl(String url) {
        String scheme = schemeOf(url);
        return "http".equals(scheme) || "https".equals(scheme);
    }

    static boolean isYouTubeHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        for (String domain : YOUTUBE_DOMAINS) {
            if (h.equals(domain) || h.endsWith("." + domain)) return true;
        }
        return false;
    }

    /**
     * Host einer http(s)-Adresse in Kleinbuchstaben, ohne Port, Zugangsdaten und Punkt am Ende;
     * null für andere Schemes. Trennt wie Browser auch an "\" und wertet nur den Teil nach dem
     * letzten "@" aus, damit z. B. "https://youtube.com@example.org/" nicht als YouTube gilt.
     */
    static String hostOf(String url) {
        if (!isWebUrl(url)) return null;
        String rest = url.trim();
        rest = rest.substring(rest.indexOf(':') + 1);

        int start = 0;
        while (start < rest.length() && (rest.charAt(start) == '/' || rest.charAt(start) == '\\')) {
            start++;
        }
        int end = rest.length();
        for (int i = start; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '\\' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        String authority = rest.substring(start, end);
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);

        String host;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            host = close > 0 ? authority.substring(0, close + 1) : authority;
        } else {
            int colon = authority.indexOf(':');
            host = colon >= 0 ? authority.substring(0, colon) : authority;
        }
        host = host.toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host.isEmpty() ? null : host;
    }

    // ------------------------------------------------------------------ App-Links

    /**
     * Übersetzt einen Link, mit dem eine Seite die YouTube-App öffnen will (intent://…,
     * vnd.youtube:…, youtube:…), in eine YouTube-Adresse, die stattdessen im Tab geladen wird.
     *
     * @return die http(s)-Adresse für den Tab, oder null, wenn der Link blockiert werden soll
     *         (kein YouTube-Ziel, z. B. Play Store, mailto:, javascript:). Für normale
     *         http(s)-Adressen ebenfalls null: die lädt die WebView ohnehin selbst.
     */
    static String resolveAppLink(String url) {
        if (url == null || isWebUrl(url)) return null;
        String scheme = schemeOf(url);
        if ("intent".equals(scheme)) return resolveIntentLink(url.trim());
        for (String appScheme : YOUTUBE_APP_SCHEMES) {
            if (appScheme.equals(scheme)) return resolveYouTubeSchemeLink(url.trim());
        }
        return null;
    }

    /** intent://HOST/PFAD#Intent;scheme=…;package=…;S.browser_fallback_url=…;end */
    private static String resolveIntentLink(String url) {
        int marker = url.indexOf(INTENT_MARKER);
        if (marker < 0) return null;

        String data = url.substring("intent:".length(), marker);
        String targetScheme = null;
        String fallback = null;
        for (String part : url.substring(marker + INTENT_MARKER.length()).split(";")) {
            if (part.startsWith("scheme=")) {
                targetScheme = part.substring("scheme=".length()).toLowerCase(Locale.ROOT);
            } else if (part.startsWith("S.browser_fallback_url=")) {
                fallback = decode(part.substring("S.browser_fallback_url=".length()));
            }
        }

        if (targetScheme != null && !data.isEmpty()) {
            String target = targetScheme + ":" + data;
            if (isYouTubeUrl(target)) return target;
            if (!isWebUrl(target) && !"intent".equals(targetScheme)) {
                String resolved = resolveAppLink(target);
                if (resolved != null) return resolved;
            }
        }
        return isYouTubeUrl(fallback) ? fallback.trim() : null;
    }

    /** vnd.youtube:VIDEO_ID, vnd.youtube://VIDEO_ID oder vnd.youtube://www.youtube.com/watch?v=… */
    private static String resolveYouTubeSchemeLink(String url) {
        String rest = url.substring(url.indexOf(':') + 1);
        while (rest.startsWith("/")) rest = rest.substring(1);

        String asWeb = "https://" + rest;
        if (isYouTubeUrl(asWeb)) return asWeb;

        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '?' || c == '#' || c == '&' || c == '/') {
                end = i;
                break;
            }
        }
        return watchUrl(rest.substring(0, end));
    }

    // ------------------------------------------------------------------ Helfer

    /** Scheme in Kleinbuchstaben ("https", "intent", "vnd.youtube" …) oder null. */
    static String schemeOf(String url) {
        if (url == null) return null;
        String s = url.trim();
        int colon = s.indexOf(':');
        if (colon <= 0) return null;
        for (int i = 0; i < colon; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (i > 0 && ((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.'));
            if (!ok) return null;
        }
        return s.substring(0, colon).toLowerCase(Locale.ROOT);
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e); // UTF-8 gibt es immer
        }
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return null;
        }
    }
}
