package com.gwrhub.overlay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Unit-Tests für {@link KeyNames}: deutsche/englische Tastennamen und Kombinationen.
 * Wie die anderen Tests ohne JUnit/Gradle: Start über Tests/run-keys-tests.ps1 (braucht nur ein JDK).
 * Liegt außerhalb von Assets/, damit Unity die Tests nicht in die APK kompiliert.
 */
public final class KeyNamesTests {

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
        // ------------------------------------------------------------ Einzeltasten
        test("einfg / einfügen / insert mean Insert", () -> {
            keys("einfg", 124);
            keys("Einfügen", 124);
            keys("einfuegen", 124);
            keys("INSERT", 124);
            keys("ins", 124);
        });
        test("entf / entfernen / delete mean forward delete", () -> {
            keys("entf", 112);
            keys("Entfernen", 112);
            keys("del", 112);
        });
        test("strg / ctrl mean left Ctrl", () -> {
            keys("strg", 113);
            keys("Ctrl", 113);
        });
        test("labels follow the typed language", () -> {
            label("einfg", "Einfg");
            label("insert", "Ins");
            label("entf", "Entf");
            label("strg", "Strg");
            label("ctrl", "Ctrl");
        });
        test("navigation keys incl. German names", () -> {
            keys("pos1", 122);
            keys("ende", 123);
            keys("Bild auf", 92);
            keys("bild-ab", 93);
            keys("pfeil hoch", 19);
            keys("links", 21);
            keys("esc", 111);
            keys("leertaste", 62);
            keys("Rücktaste", 67);
            keys("tab", 61);
            keys("enter", 66);
        });
        test("F keys", () -> {
            keys("f1", 131);
            keys("F12", 142);
            label("f5", "F5");
            invalid("f13");
        });
        test("letters, digits and symbols", () -> {
            keys("a", 29);
            keys("Z", 54);
            label("c", "C");
            keys("0", 7);
            keys("9", 16);
            keys("-", 69);
            keys(".", 56);
        });
        test("numpad", () -> {
            keys("num1", 145);
            keys("Numpad 0", 144);
            keys("numplus", 157);
            keys("numenter", 160);
            keys("numlock", 143);
        });
        test("numpad in GWR notation \"1 (ZB)\"", () -> {
            keys("1 (ZB)", 145);
            keys("0 (zb)", 144);
            keys("9(ZB)", 153);
            keys("ZB 1", 145);
            keys("Ziffernblock 5", 149);
            keys("Enter (ZB)", 160);
            keys("Strg + 1 (ZB)", 113, 145);
            label("1 (zb)", "1 (ZB)");
            keys("1", 8); // ohne (ZB) bleibt es die normale 1
        });
        test("# is the PC #-key (KEYCODE_BACKSLASH), not the phone keypad #", () -> {
            keys("#", 73);
            keys("raute", 73);
            keys("Hash", 73);
            label("#", "#");
            label("raute", "#");
            keys("strg+#", 113, 73);
        });
        test("KEYCODE_ prefix is accepted", () -> keys("KEYCODE_INSERT", 124));

        // ------------------------------------------------------------ Kombinationen
        test("combos with +", () -> {
            keys("strg+c", 113, 31);
            keys("Strg + Shift + Esc", 113, 59, 111);
            keys("alt+f4", 57, 134);
            label("strg+c", "Strg+C");
            label("alt + F4", "Alt+F4");
        });
        test("plus key itself (German + key = KEYCODE_RIGHT_BRACKET)", () -> {
            keys("+", 72);
            keys("strg++", 113, 72);
            keys("strg+plus", 113, 72);
            label("strg++", "Strg++");
        });

        // ------------------------------------------------------------ Deutsche Tastatur
        test("umlauts, upper and lower case, are the same key", () -> {
            keys("ü", 71);
            keys("Ü", 71);
            keys("ö", 74);
            keys("Ö", 74);
            keys("ä", 75);
            keys("Ä", 75);
            keys("ue", 71);
            label("ü", "Ü");
            label("ä", "Ä");
            keys("strg+ö", 113, 74);
        });
        test("ß and the other own keys of the German layout", () -> {
            keys("ß", 76);
            keys("eszett", 76);
            keys("^", 68);
            keys("´", 70);
            keys("<", 81);
            keys("-", 69);
            keys(",", 55);
            keys(".", 56);
            label("ß", "ß");
            label("<", "<");
        });
        test("shift-level characters become Shift + key", () -> {
            keys("*", 59, 72);
            keys("?", 59, 76);
            keys("!", 59, 8);
            keys("/", 59, 14);
            keys("=", 59, 7);
            keys("°", 59, 68);
            keys("'", 59, 73);
            keys(">", 59, 81);
            keys("_", 59, 69);
            keys(":", 59, 56);
            keys(";", 59, 55);
            keys("§", 59, 10);
            label("*", "*");
            label("stern", "*");
        });
        test("AltGr-level characters become AltGr + key", () -> {
            keys("@", 58, 45);
            keys("€", 58, 33);
            keys("{", 58, 14);
            keys("[", 58, 15);
            keys("]", 58, 16);
            keys("}", 58, 7);
            keys("\\", 58, 76);
            keys("~", 58, 72);
            keys("|", 58, 81);
            label("@", "@");
        });
        test("implied modifiers merge with typed ones, modifiers first", () -> {
            keys("strg+*", 113, 59, 72);
            keys("shift+*", 59, 72);
            keys("*+shift", 59, 72);
            keys("a+strg", 113, 29);
            label("a+strg", "A+Strg");
            invalid("strg+alt+shift+@"); // 5 Tasten mit AltGr
        });
        test("numpad names containing +", () -> {
            keys("+ (ZB)", 157);
            keys("num+", 157);
            keys("strg+num+", 113, 157);
            keys("strg + + (ZB)", 113, 157);
        });
        test("scan codes of a German keyboard", () -> {
            check(KeyNames.scanCode(54) == 21, "Z");
            check(KeyNames.scanCode(53) == 44, "Y");
            check(KeyNames.scanCode(76) == 12, "ß");
            check(KeyNames.scanCode(73) == 43, "#");
            check(KeyNames.scanCode(81) == 86, "<");
            check(KeyNames.scanCode(69) == 53, "-");
            check(KeyNames.scanCode(145) == 79, "Num1");
            check(KeyNames.scanCode(62) == 57, "Space");
            check(KeyNames.scanCode(29) == 30, "A");
            check(KeyNames.scanCode(7) == 11, "0");
        });

        // ------------------------------------------------------------ Fehler
        test("empty input is rejected", () -> {
            invalid("");
            invalid("   ");
            invalid(null);
        });
        test("unknown key names the token", () -> {
            KeyNames.Result r = KeyNames.parse("strg+foo");
            check(!r.isValid(), "should be invalid");
            check(r.error.contains("foo"), "error mentions token: " + r.error);
        });
        test("dangling + is rejected", () -> {
            invalid("strg+");
            invalid("+c");
        });
        test("duplicate keys are rejected", () -> invalid("strg+ctrl"));
        test("too many keys are rejected", () -> invalid("strg+alt+shift+win+a"));

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

    private static void keys(String input, int... expected) {
        KeyNames.Result r = KeyNames.parse(input);
        if (!r.isValid()) throw new AssertionError("\"" + input + "\" rejected: " + r.error);
        if (!Arrays.equals(expected, r.keyCodes)) {
            throw new AssertionError("\"" + input + "\": expected " + Arrays.toString(expected)
                    + " but was " + Arrays.toString(r.keyCodes));
        }
    }

    private static void label(String input, String expected) {
        KeyNames.Result r = KeyNames.parse(input);
        if (!r.isValid()) throw new AssertionError("\"" + input + "\" rejected: " + r.error);
        if (!expected.equals(r.label)) {
            throw new AssertionError("\"" + input + "\": expected label <" + expected
                    + "> but was <" + r.label + ">");
        }
    }

    private static void invalid(String input) {
        KeyNames.Result r = KeyNames.parse(input);
        if (r.isValid()) {
            throw new AssertionError("\"" + input + "\" should be rejected but gave " + r.label);
        }
    }

    private static void check(boolean condition, String context) {
        if (!condition) throw new AssertionError("check failed  [" + context + "]");
    }
}
