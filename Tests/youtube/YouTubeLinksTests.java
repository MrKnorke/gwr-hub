package com.gwrhub.overlay;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit-Tests für {@link YouTubeLinks}: Standard-Such-URL und interne/externe Link-Erkennung.
 * Wie die Timer-Tests bewusst ohne JUnit/Gradle, weil Unity die Android-Plugins selbst baut:
 * Start über Tests/run-youtube-tests.ps1 (braucht nur ein JDK).
 * Liegt außerhalb von Assets/, damit Unity die Tests nicht in die APK kompiliert.
 */
public final class YouTubeLinksTests {

    private static final String VIDEO_ID = "dQw4w9WgXcQ";

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
        // ------------------------------------------------------------ Such-URLs
        test("default search URL matches the spec exactly", () ->
                eq("https://m.youtube.com/results?search_query=Guild+Wars+Reforged",
                        YouTubeLinks.DEFAULT_SEARCH_URL));

        test("default query is Guild Wars Reforged", () ->
                eq("Guild Wars Reforged", YouTubeLinks.DEFAULT_QUERY));

        test("searchUrl encodes spaces as + and escapes special characters", () -> {
            eq("https://m.youtube.com/results?search_query=GW+%26+Mesmer%2FBuilds",
                    YouTubeLinks.searchUrl("GW & Mesmer/Builds"));
            eq("https://m.youtube.com/results?search_query=M%C3%B6nch+%23guide%3F",
                    YouTubeLinks.searchUrl("Mönch #guide?"));
        });

        test("searchUrl trims the query", () ->
                eq(YouTubeLinks.DEFAULT_SEARCH_URL, YouTubeLinks.searchUrl("  Guild Wars Reforged  ")));

        test("empty or null query falls back to the default search", () -> {
            eq(YouTubeLinks.DEFAULT_SEARCH_URL, YouTubeLinks.searchUrl(null));
            eq(YouTubeLinks.DEFAULT_SEARCH_URL, YouTubeLinks.searchUrl(""));
            eq(YouTubeLinks.DEFAULT_SEARCH_URL, YouTubeLinks.searchUrl("   "));
        });

        test("watchUrl builds the mobile watch page for valid IDs only", () -> {
            eq("https://m.youtube.com/watch?v=" + VIDEO_ID, YouTubeLinks.watchUrl(VIDEO_ID));
            eq(null, YouTubeLinks.watchUrl("too-short"));
            eq(null, YouTubeLinks.watchUrl("bad!chars!!"));
            eq(null, YouTubeLinks.watchUrl(null));
        });

        // ------------------------------------------------------------ Startseite / Wiederherstellen
        test("startUrl restores a saved YouTube page", () -> {
            String saved = "https://m.youtube.com/watch?v=" + VIDEO_ID;
            eq(saved, YouTubeLinks.startUrl(saved));
        });

        test("startUrl uses the default search when nothing or a non-YouTube page was saved", () -> {
            eq(YouTubeLinks.DEFAULT_SEARCH_URL, YouTubeLinks.startUrl(null));
            eq(YouTubeLinks.DEFAULT_SEARCH_URL, YouTubeLinks.startUrl(""));
            eq(YouTubeLinks.DEFAULT_SEARCH_URL, YouTubeLinks.startUrl("https://gwpvx.fandom.com/wiki/Main_Page"));
            eq(YouTubeLinks.DEFAULT_SEARCH_URL, YouTubeLinks.startUrl("about:blank"));
        });

        // ------------------------------------------------------------ Interne Links (YouTube)
        test("YouTube URLs are internal", () -> {
            String[] internal = {
                    "https://m.youtube.com/results?search_query=Guild+Wars+Reforged",
                    "https://m.youtube.com/watch?v=" + VIDEO_ID,
                    "https://www.youtube.com/@GuildWars",
                    "https://youtube.com/shorts/" + VIDEO_ID,
                    "http://youtu.be/" + VIDEO_ID,
                    "https://YOUTU.BE/" + VIDEO_ID,
                    "HTTPS://M.YOUTUBE.COM/",
                    "https://m.youtube.com:443/watch?v=" + VIDEO_ID,
                    "https://www.youtube.com./",
                    "https://consent.youtube.com/m?continue=https%3A%2F%2Fm.youtube.com%2F",
                    "https://www.youtube-nocookie.com/embed/" + VIDEO_ID,
                    "  https://m.youtube.com/  ",
            };
            for (String url : internal) {
                eq(YouTubeLinks.LinkType.YOUTUBE, YouTubeLinks.classify(url), url);
                check(YouTubeLinks.isYouTubeUrl(url), url);
            }
        });

        // ------------------------------------------------------------ Externe Links
        test("other web pages are external", () -> {
            String[] external = {
                    "https://gwpvx.fandom.com/wiki/Main_Page",
                    "https://notyoutube.com/watch?v=" + VIDEO_ID,
                    "https://youtube.com.example.org/",
                    "https://example.org/?next=https://www.youtube.com/",
                    "https://example.org/youtube.com",
                    "https://youtube.com@example.org/",
                    "https://example.org\\@youtube.com/",
                    "https://accounts.google.com/ServiceLogin",
            };
            for (String url : external) {
                eq(YouTubeLinks.LinkType.OTHER_WEB, YouTubeLinks.classify(url), url);
                check(!YouTubeLinks.isYouTubeUrl(url), url);
            }
        });

        test("non-web links are neither internal nor loadable", () -> {
            String[] nonWeb = {
                    "intent://www.youtube.com/watch?v=" + VIDEO_ID + "#Intent;scheme=https;end",
                    "vnd.youtube:" + VIDEO_ID,
                    "market://details?id=com.google.android.youtube",
                    "mailto:someone@example.org",
                    "javascript:alert(1)",
                    "ftp://youtube.com/",
                    "about:blank",
                    "m.youtube.com/watch?v=" + VIDEO_ID,
                    "",
                    null,
            };
            for (String url : nonWeb) {
                eq(YouTubeLinks.LinkType.NON_WEB, YouTubeLinks.classify(url), String.valueOf(url));
                check(!YouTubeLinks.isWebUrl(url), String.valueOf(url));
            }
        });

        test("hostOf strips port, credentials, case and trailing dot", () -> {
            eq("m.youtube.com", YouTubeLinks.hostOf("https://user:pw@M.YouTube.com.:8443/x?y#z"));
            eq("youtu.be", YouTubeLinks.hostOf("http://youtu.be"));
            eq("[::1]", YouTubeLinks.hostOf("http://[::1]:8080/"));
            eq("path-only", YouTubeLinks.hostOf("https:///path-only")); // wie Browser: Slashes überspringen
            eq(null, YouTubeLinks.hostOf("https://"));
            eq(null, YouTubeLinks.hostOf("vnd.youtube:" + VIDEO_ID));
        });

        test("isYouTubeHost requires a full domain match", () -> {
            check(YouTubeLinks.isYouTubeHost("youtube.com"), "youtube.com");
            check(YouTubeLinks.isYouTubeHost("music.youtube.com"), "music.youtube.com");
            check(!YouTubeLinks.isYouTubeHost("fakeyoutube.com"), "fakeyoutube.com");
            check(!YouTubeLinks.isYouTubeHost("youtube.co"), "youtube.co");
            check(!YouTubeLinks.isYouTubeHost(null), "null");
        });

        // ------------------------------------------------------------ App-Links -> im Tab bleiben
        test("vnd.youtube links open the video in the tab instead of the YouTube app", () -> {
            String watch = "https://m.youtube.com/watch?v=" + VIDEO_ID;
            eq(watch, YouTubeLinks.resolveAppLink("vnd.youtube:" + VIDEO_ID));
            eq(watch, YouTubeLinks.resolveAppLink("vnd.youtube://" + VIDEO_ID));
            eq(watch, YouTubeLinks.resolveAppLink("vnd.youtube:" + VIDEO_ID + "?t=42"));
            eq(watch, YouTubeLinks.resolveAppLink("youtube:" + VIDEO_ID));
            eq("https://www.youtube.com/watch?v=" + VIDEO_ID,
                    YouTubeLinks.resolveAppLink("vnd.youtube://www.youtube.com/watch?v=" + VIDEO_ID));
        });

        test("intent links to YouTube are rewritten to an in-tab URL", () -> {
            eq("https://www.youtube.com/watch?v=" + VIDEO_ID, YouTubeLinks.resolveAppLink(
                    "intent://www.youtube.com/watch?v=" + VIDEO_ID
                            + "#Intent;scheme=https;package=com.google.android.youtube;end"));
            eq("https://www.youtube.com/watch?v=" + VIDEO_ID, YouTubeLinks.resolveAppLink(
                    "intent://www.youtube.com/watch?v=" + VIDEO_ID
                            + "#Intent;scheme=vnd.youtube;package=com.google.android.youtube;end"));
            eq("https://m.youtube.com/watch?v=" + VIDEO_ID, YouTubeLinks.resolveAppLink(
                    "intent://" + VIDEO_ID + "#Intent;scheme=vnd.youtube;end"));
        });

        test("intent links fall back to a YouTube browser_fallback_url", () ->
                eq("https://m.youtube.com/watch?v=" + VIDEO_ID, YouTubeLinks.resolveAppLink(
                        "intent://open#Intent;scheme=youtube-app;package=com.google.android.youtube;"
                                + "S.browser_fallback_url=https%3A%2F%2Fm.youtube.com%2Fwatch%3Fv%3D"
                                + VIDEO_ID + ";end")));

        test("app links without a YouTube target are blocked", () -> {
            String[] blocked = {
                    "intent://example.org/#Intent;scheme=https;end",
                    "intent://details?id=com.google.android.youtube#Intent;scheme=market;"
                            + "S.browser_fallback_url=https%3A%2F%2Fplay.google.com%2Fstore;end",
                    "intent://www.youtube.com/watch?v=" + VIDEO_ID, // ohne #Intent;-Teil
                    "market://details?id=com.google.android.youtube",
                    "vnd.youtube:not-a-valid-id",
                    "mailto:someone@example.org",
                    "javascript:alert(1)",
                    "",
                    null,
            };
            for (String url : blocked) eq(null, YouTubeLinks.resolveAppLink(url), String.valueOf(url));
        });

        test("normal web URLs need no rewrite", () -> {
            eq(null, YouTubeLinks.resolveAppLink("https://m.youtube.com/watch?v=" + VIDEO_ID));
            eq(null, YouTubeLinks.resolveAppLink("https://gwpvx.fandom.com/"));
        });

        // ------------------------------------------------------------ Ausführen
        int failed = 0;
        for (int i = 0; i < cases.size(); i++) {
            try {
                cases.get(i).run();
                System.out.println("PASS  " + names.get(i));
            } catch (Throwable t) {
                failed++;
                System.out.println("FAIL  " + names.get(i) + "\n      " + t.getMessage());
            }
        }
        System.out.println();
        System.out.println((cases.size() - failed) + "/" + cases.size() + " tests passed");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------ Assertions

    private static void eq(Object expected, Object actual) {
        eq(expected, actual, null);
    }

    private static void eq(Object expected, Object actual, String context) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">"
                    + (context != null ? "  [" + context + "]" : ""));
        }
    }

    private static void check(boolean condition, String context) {
        if (!condition) throw new AssertionError("check failed  [" + context + "]");
    }
}
