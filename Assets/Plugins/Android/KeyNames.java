package com.gwrhub.overlay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Übersetzt eingetippte Tastennamen in Android-Keycodes, z. B. "einfg" → Einfügen,
 * "entf" → Entfernen, "strg+c" → Strg + C. Deutsche und englische Namen werden erkannt,
 * Groß-/Kleinschreibung, Leerzeichen und Umlaute (ä/ae) spielen keine Rolle.
 * Mehrere Tasten werden mit "+" verbunden.
 *
 * Einzelne Zeichen folgen der deutschen PC-Tastatur (QWERTZ), so wie Android sie mit dem
 * Layout "Deutsch" meldet: "ü" ist die Ü-Taste, "#" die Rautetaste, "<" die Taste neben
 * Shift. Zeichen auf der zweiten/dritten Ebene werden zur passenden Kombination, z. B.
 * "*" → Shift + "+", "?" → Shift + ß, "@" → AltGr + Q.
 *
 * Reine Java-Logik ohne Android-Abhängigkeit (testbar mit Tests/run-keys-tests.ps1);
 * die Keycode-Werte entsprechen den Konstanten aus android.view.KeyEvent.
 */
public final class KeyNames {

    /** Höchstzahl an Tasten in einer Kombination (inkl. Shift/AltGr aus Sonderzeichen). */
    public static final int MAX_KEYS = 4;

    /** Ergebnis von {@link #parse}: entweder eine gültige Kombination oder eine Fehlermeldung. */
    public static final class Result {
        /** Keycodes: erst die Modifikatoren, dann die übrigen Tasten (null bei Fehler). */
        public final int[] keyCodes;
        /** Anzeigetext für die Bubble, z. B. "Strg+C" (null bei Fehler). */
        public final String label;
        /** Fehlermeldung für den Nutzer (null bei Erfolg). */
        public final String error;

        private Result(int[] keyCodes, String label, String error) {
            this.keyCodes = keyCodes;
            this.label = label;
            this.error = error;
        }

        public boolean isValid() {
            return error == null;
        }
    }

    /** Eine Taste, ggf. mit Modifikator, der für das Zeichen nötig ist (Shift, AltGr). */
    private static final class Key {
        final int code;
        /** Zusätzlich zu haltende Taste (0 = keine). */
        final int modifier;
        final String label;

        Key(int code, int modifier, String label) {
            this.code = code;
            this.modifier = modifier;
            this.label = label;
        }
    }

    // Keycodes aus android.view.KeyEvent
    static final int KEYCODE_0 = 7;
    static final int KEYCODE_1 = 8;
    static final int KEYCODE_DPAD_UP = 19;
    static final int KEYCODE_DPAD_DOWN = 20;
    static final int KEYCODE_DPAD_LEFT = 21;
    static final int KEYCODE_DPAD_RIGHT = 22;
    static final int KEYCODE_A = 29;
    static final int KEYCODE_E = 33;
    static final int KEYCODE_M = 41;
    static final int KEYCODE_Q = 45;
    static final int KEYCODE_Y = 53;
    static final int KEYCODE_Z = 54;
    static final int KEYCODE_COMMA = 55;
    static final int KEYCODE_PERIOD = 56;
    static final int KEYCODE_ALT_LEFT = 57;
    static final int KEYCODE_ALT_RIGHT = 58; // AltGr
    static final int KEYCODE_SHIFT_LEFT = 59;
    static final int KEYCODE_SHIFT_RIGHT = 60;
    static final int KEYCODE_TAB = 61;
    static final int KEYCODE_SPACE = 62;
    static final int KEYCODE_ENTER = 66;
    static final int KEYCODE_DEL = 67; // Rücktaste
    static final int KEYCODE_GRAVE = 68; // deutsch: ^
    static final int KEYCODE_MINUS = 69; // deutsch: -
    static final int KEYCODE_EQUALS = 70; // deutsch: ´
    static final int KEYCODE_LEFT_BRACKET = 71; // deutsch: Ü
    static final int KEYCODE_RIGHT_BRACKET = 72; // deutsch: +
    static final int KEYCODE_BACKSLASH = 73; // deutsch: #
    static final int KEYCODE_SEMICOLON = 74; // deutsch: Ö
    static final int KEYCODE_APOSTROPHE = 75; // deutsch: Ä
    static final int KEYCODE_SLASH = 76; // deutsch: ß
    static final int KEYCODE_PLUS = 81; // deutsch: < (Taste neben linkem Shift)
    static final int KEYCODE_MENU = 82;
    static final int KEYCODE_PAGE_UP = 92;
    static final int KEYCODE_PAGE_DOWN = 93;
    static final int KEYCODE_ESCAPE = 111;
    static final int KEYCODE_FORWARD_DEL = 112; // Entf
    static final int KEYCODE_CTRL_LEFT = 113;
    static final int KEYCODE_CTRL_RIGHT = 114;
    static final int KEYCODE_CAPS_LOCK = 115;
    static final int KEYCODE_SCROLL_LOCK = 116;
    static final int KEYCODE_META_LEFT = 117;
    static final int KEYCODE_META_RIGHT = 118;
    static final int KEYCODE_SYSRQ = 120;
    static final int KEYCODE_BREAK = 121;
    static final int KEYCODE_MOVE_HOME = 122;
    static final int KEYCODE_MOVE_END = 123;
    static final int KEYCODE_INSERT = 124;
    static final int KEYCODE_F1 = 131;
    static final int KEYCODE_NUM_LOCK = 143;
    static final int KEYCODE_NUMPAD_0 = 144;
    static final int KEYCODE_NUMPAD_DIVIDE = 154;
    static final int KEYCODE_NUMPAD_MULTIPLY = 155;
    static final int KEYCODE_NUMPAD_SUBTRACT = 156;
    static final int KEYCODE_NUMPAD_ADD = 157;
    static final int KEYCODE_NUMPAD_DOT = 158;
    static final int KEYCODE_NUMPAD_ENTER = 160;

    private static final int SHIFT = KEYCODE_SHIFT_LEFT;
    private static final int ALTGR = KEYCODE_ALT_RIGHT;

    /** Normalisierter Name → Taste. Das Label folgt der Sprache des Namens (einfg → "Einfg", ins → "Ins"). */
    private static final Map<String, Key> NAMES = new HashMap<>();

    /** Einzelnes Zeichen der deutschen Tastatur → Taste (ggf. mit Shift/AltGr). */
    private static final Map<Character, Key> CHARS = new HashMap<>();

    static {
        // ---------------------------------------------------- Zeichen der deutschen Tastatur
        for (char c = 'a'; c <= 'z'; c++) {
            chr(Character.toUpperCase(c), KEYCODE_A + (c - 'a'), 0, String.valueOf(Character.toUpperCase(c)));
            chr(c, KEYCODE_A + (c - 'a'), 0, String.valueOf(Character.toUpperCase(c)));
        }
        for (char c = '0'; c <= '9'; c++) chr(c, KEYCODE_0 + (c - '0'), 0, String.valueOf(c));
        // Umlaute und ß: Groß- und Kleinbuchstabe meinen dieselbe Taste (wie bei a/A)
        chr('ü', KEYCODE_LEFT_BRACKET, 0, "Ü");
        chr('Ü', KEYCODE_LEFT_BRACKET, 0, "Ü");
        chr('ö', KEYCODE_SEMICOLON, 0, "Ö");
        chr('Ö', KEYCODE_SEMICOLON, 0, "Ö");
        chr('ä', KEYCODE_APOSTROPHE, 0, "Ä");
        chr('Ä', KEYCODE_APOSTROPHE, 0, "Ä");
        chr('ß', KEYCODE_SLASH, 0, "ß");
        // Weitere eigene Tasten
        chr('^', KEYCODE_GRAVE, 0, "^");
        chr('´', KEYCODE_EQUALS, 0, "´");
        chr('+', KEYCODE_RIGHT_BRACKET, 0, "+");
        chr('#', KEYCODE_BACKSLASH, 0, "#");
        chr('<', KEYCODE_PLUS, 0, "<");
        chr(',', KEYCODE_COMMA, 0, ",");
        chr('.', KEYCODE_PERIOD, 0, ".");
        chr('-', KEYCODE_MINUS, 0, "-");
        // Shift-Ebene
        String shiftDigits = "=!\"§$%&/()"; // Shift + 0 … 9
        for (int i = 0; i <= 9; i++) chr(shiftDigits.charAt(i), KEYCODE_0 + i, SHIFT, null);
        chr('°', KEYCODE_GRAVE, SHIFT, null);
        chr('?', KEYCODE_SLASH, SHIFT, null);
        chr('`', KEYCODE_EQUALS, SHIFT, null);
        chr('*', KEYCODE_RIGHT_BRACKET, SHIFT, null);
        chr('\'', KEYCODE_BACKSLASH, SHIFT, null);
        chr('>', KEYCODE_PLUS, SHIFT, null);
        chr(';', KEYCODE_COMMA, SHIFT, null);
        chr(':', KEYCODE_PERIOD, SHIFT, null);
        chr('_', KEYCODE_MINUS, SHIFT, null);
        // AltGr-Ebene
        chr('²', KEYCODE_1 + 1, ALTGR, null);
        chr('³', KEYCODE_1 + 2, ALTGR, null);
        chr('{', KEYCODE_1 + 6, ALTGR, null);
        chr('[', KEYCODE_1 + 7, ALTGR, null);
        chr(']', KEYCODE_1 + 8, ALTGR, null);
        chr('}', KEYCODE_0, ALTGR, null);
        chr('\\', KEYCODE_SLASH, ALTGR, null);
        chr('~', KEYCODE_RIGHT_BRACKET, ALTGR, null);
        chr('|', KEYCODE_PLUS, ALTGR, null);
        chr('@', KEYCODE_Q, ALTGR, null);
        chr('€', KEYCODE_E, ALTGR, null);
        chr('µ', KEYCODE_M, ALTGR, null);

        // ---------------------------------------------------- Modifikatoren
        add(KEYCODE_CTRL_LEFT, "Strg", "strg", "steuerung", "strglinks", "lstrg");
        add(KEYCODE_CTRL_LEFT, "Ctrl", "ctrl", "control", "ctl", "lctrl", "ctrlleft", "leftctrl");
        add(KEYCODE_CTRL_RIGHT, "Strg R", "strgrechts", "rstrg");
        add(KEYCODE_CTRL_RIGHT, "Ctrl R", "rctrl", "ctrlright", "rightctrl");
        add(KEYCODE_ALT_LEFT, "Alt", "alt", "lalt", "altleft", "altlinks");
        add(KEYCODE_ALT_RIGHT, "AltGr", "altgr", "ralt", "altright", "altrechts");
        add(KEYCODE_SHIFT_LEFT, "Shift", "shift", "lshift", "shiftleft", "leftshift");
        add(KEYCODE_SHIFT_LEFT, "Umsch", "umschalt", "umsch", "umschalttaste", "hochstelltaste");
        add(KEYCODE_SHIFT_RIGHT, "Shift R", "rshift", "shiftright", "rightshift", "shiftrechts");
        add(KEYCODE_META_LEFT, "Win", "win", "windows", "windowstaste", "super", "meta", "cmd");
        add(KEYCODE_CAPS_LOCK, "Caps", "capslock", "caps");
        add(KEYCODE_CAPS_LOCK, "Feststell", "feststell", "feststelltaste", "fest");

        // ---------------------------------------------------- Navigation / Bearbeiten
        add(KEYCODE_INSERT, "Einfg", "einfg", "einfuegen", "einf");
        add(KEYCODE_INSERT, "Ins", "insert", "ins");
        add(KEYCODE_FORWARD_DEL, "Entf", "entf", "entfernen");
        add(KEYCODE_FORWARD_DEL, "Del", "delete", "del", "forwarddelete");
        add(KEYCODE_MOVE_HOME, "Pos1", "pos1");
        add(KEYCODE_MOVE_HOME, "Home", "home");
        add(KEYCODE_MOVE_END, "Ende", "ende");
        add(KEYCODE_MOVE_END, "End", "end");
        add(KEYCODE_PAGE_UP, "Bild↑", "bildauf", "bildhoch", "bildrauf", "bildoben");
        add(KEYCODE_PAGE_UP, "PgUp", "pageup", "pgup", "pup");
        add(KEYCODE_PAGE_DOWN, "Bild↓", "bildab", "bildrunter", "bildunten");
        add(KEYCODE_PAGE_DOWN, "PgDn", "pagedown", "pgdn", "pgdown", "pdown");
        add(KEYCODE_DPAD_UP, "↑", "hoch", "oben", "pfeilhoch", "pfeiloben", "pfeilauf",
                "up", "arrowup", "uparrow");
        add(KEYCODE_DPAD_DOWN, "↓", "runter", "unten", "pfeilrunter", "pfeilunten", "pfeilab",
                "down", "arrowdown", "downarrow");
        add(KEYCODE_DPAD_LEFT, "←", "links", "pfeillinks", "left", "arrowleft", "leftarrow");
        add(KEYCODE_DPAD_RIGHT, "→", "rechts", "pfeilrechts", "right", "arrowright", "rightarrow");
        add(KEYCODE_ESCAPE, "Esc", "esc", "escape");
        add(KEYCODE_ENTER, "Enter", "enter", "eingabe", "eingabetaste", "return", "ret");
        add(KEYCODE_TAB, "Tab", "tab", "tabulator", "tabtaste");
        add(KEYCODE_SPACE, "Leer", "leer", "leertaste", "leerzeichen");
        add(KEYCODE_SPACE, "Space", "space", "spacebar", "spc");
        add(KEYCODE_DEL, "Rück", "rueck", "ruecktaste", "rueckschritt");
        add(KEYCODE_DEL, "Bksp", "backspace", "bksp", "bs");
        add(KEYCODE_SYSRQ, "Druck", "druck", "drucken", "drucktaste");
        add(KEYCODE_SYSRQ, "PrtSc", "print", "printscreen", "prtsc", "prtscr", "prntscr", "sysrq");
        add(KEYCODE_BREAK, "Pause", "pause", "break", "unterbrechen");
        add(KEYCODE_SCROLL_LOCK, "Rollen", "rollen", "rollentaste");
        add(KEYCODE_SCROLL_LOCK, "ScrLk", "scrolllock", "scroll", "scrlk");
        add(KEYCODE_NUM_LOCK, "Num", "num", "numlock", "numlk");
        add(KEYCODE_MENU, "Menü", "menue", "kontext", "kontextmenue", "anwendung");
        add(KEYCODE_MENU, "Menu", "menu", "apps", "contextmenu");

        // ---------------------------------------------------- Zeichen als Wort
        word('ü', "ue", "uumlaut");
        word('ö', "oe", "oumlaut");
        word('ä', "ae", "aumlaut");
        word('ß', "ss", "sz", "eszett", "scharfess", "scharfes");
        word('^', "zirkumflex", "caret", "dach", "dachtaste");
        word('°', "grad", "degree");
        word('´', "akut", "acute", "akzent");
        word('`', "gravis", "grave", "backtick");
        word('+', "plus");
        word('*', "stern", "star", "asterisk", "mal");
        word('~', "tilde");
        word('#', "raute", "rautetaste", "hash", "pound");
        word('\'', "apostroph", "apostrophe", "quote");
        word('<', "kleiner", "kleinerals", "less", "lessthan");
        word('>', "groesser", "groesserals", "greater", "greaterthan");
        word('|', "pipe", "senkrechtstrich");
        word('-', "minus", "bindestrich");
        word('_', "unterstrich", "underscore");
        word(',', "komma", "comma");
        word(';', "semikolon", "semicolon", "strichpunkt");
        word('.', "punkt", "period", "dot");
        word(':', "doppelpunkt", "colon");
        word('!', "ausrufezeichen", "exclamation");
        word('"', "anfuehrungszeichen", "doublequote");
        word('§', "paragraph", "paragraf");
        word('$', "dollar");
        word('%', "prozent", "percent");
        word('&', "ampersand", "kaufmannsund");
        word('/', "slash", "schraegstrich");
        word('\\', "backslash");
        word('(', "klammerauf", "rundeklammerauf");
        word(')', "klammerzu", "rundeklammerzu");
        word('=', "gleich", "equals", "equal");
        word('?', "fragezeichen", "question");
        word('[', "eckigeklammerauf", "leftbracket");
        word(']', "eckigeklammerzu", "rightbracket");
        word('{', "geschweifteklammerauf", "leftbrace");
        word('}', "geschweifteklammerzu", "rightbrace");
        word('@', "at", "klammeraffe");
        word('€', "euro");
        word('²', "hoch2");
        word('³', "hoch3");
        word('µ', "mikro", "micro");

        // ---------------------------------------------------- F-Tasten
        for (int i = 1; i <= 12; i++) add(KEYCODE_F1 + i - 1, "F" + i, "f" + i);

        // ---------------------------------------------------- Ziffernblock
        for (int i = 0; i <= 9; i++) {
            add(KEYCODE_NUMPAD_0 + i, "Num" + i, "num" + i, "numpad" + i, "kp" + i, "nb" + i);
            // Schreibweise von GWR: "1 (ZB)" (ZB = Ziffernblock)
            add(KEYCODE_NUMPAD_0 + i, i + " (ZB)", i + "zb", "zb" + i, "ziffernblock" + i,
                    "zehnerblock" + i);
        }
        add(KEYCODE_NUMPAD_ENTER, "Enter (ZB)", "enterzb", "eingabezb");
        add(KEYCODE_NUMPAD_DOT, ", (ZB)", "kommazb", ",zb");
        add(KEYCODE_NUMPAD_MULTIPLY, "* (ZB)", "*zb");
        add(KEYCODE_NUMPAD_DIVIDE, "/ (ZB)", "/zb");
        add(KEYCODE_NUMPAD_ADD, "+ (ZB)", "pluszb");
        add(KEYCODE_NUMPAD_SUBTRACT, "- (ZB)", "minuszb");
        add(KEYCODE_NUMPAD_ADD, "Num+", "numplus", "numpadplus", "numadd", "numpadadd", "kpplus");
        add(KEYCODE_NUMPAD_SUBTRACT, "Num-", "numminus", "numpadminus", "numsubtract", "kpminus");
        add(KEYCODE_NUMPAD_MULTIPLY, "Num*", "nummal", "numstern", "nummultiply", "numpadmultiply",
                "numstar", "kpmultiply");
        add(KEYCODE_NUMPAD_DIVIDE, "Num/", "numgeteilt", "numdivide", "numpaddivide", "numslash",
                "kpdivide");
        add(KEYCODE_NUMPAD_ENTER, "NumEnter", "numenter", "numpadenter", "kpenter");
        add(KEYCODE_NUMPAD_DOT, "Num,", "numkomma", "numpunkt", "numdot", "numpaddot", "numdel",
                "numentf", "kpdot");
    }

    private KeyNames() {}

    private static void add(int code, String label, String... names) {
        Key key = new Key(code, 0, label);
        for (String n : names) NAMES.put(n, key);
    }

    /** Zeichen → Taste; ohne {@code label} wird das Zeichen selbst angezeigt. */
    private static void chr(char c, int code, int modifier, String label) {
        CHARS.put(c, new Key(code, modifier, label != null ? label : String.valueOf(c)));
    }

    /** Namen für ein Zeichen, z. B. "raute" für "#". */
    private static void word(char c, String... names) {
        Key key = CHARS.get(c);
        if (key == null) throw new IllegalStateException("no key for " + c);
        for (String n : names) NAMES.put(n, key);
    }

    /**
     * Wertet die Eingabe aus, z. B. "Einfg", "entf", "Strg + C", "alt+F4", "ü", "strg+#".
     */
    public static Result parse(String text) {
        String input = text == null ? "" : text.trim();
        if (input.isEmpty()) return error("Type a key name, e.g. Einfg, Entf or Strg+C");

        // Namen, die selbst ein "+" enthalten: "+ (ZB)" und "Num+" (am Ende oder vor "+")
        input = input.replaceAll("(?i)\\+\\s*\\(\\s*zb\\s*\\)", "pluszb")
                .replaceAll("(?i)\\bnum\\s*\\+(?=\\s*(\\+|$))", "numplus");
        // "+" allein bzw. "strg++" meinen die Plus-Taste selbst
        if (input.equals("+")) input = "plus";
        else if (input.endsWith("++")) input = input.substring(0, input.length() - 1) + "plus";

        String[] parts = input.split("\\+", -1);
        if (parts.length > MAX_KEYS) return error("At most " + MAX_KEYS + " keys per shortcut");

        // Modifikatoren werden zuerst gedrückt, dann die übrigen Tasten in Eingabereihenfolge
        List<Integer> modifiers = new ArrayList<>();
        List<Integer> others = new ArrayList<>();
        List<Integer> typed = new ArrayList<>();
        StringBuilder label = new StringBuilder();
        for (String part : parts) {
            String token = part.trim();
            if (token.isEmpty()) return error("Missing key around \"+\"");
            Key key = lookup(token);
            if (key == null) return error("Unknown key: \"" + token + "\"");
            if (typed.contains(key.code)) return error("\"" + token + "\" is used twice");
            typed.add(key.code);
            if (key.modifier != 0) addOnce(modifiers, key.modifier);
            addOnce(isModifier(key.code) ? modifiers : others, key.code);
            if (label.length() > 0) label.append('+');
            label.append(key.label);
        }

        int total = modifiers.size() + others.size();
        if (total > MAX_KEYS) return error("At most " + MAX_KEYS + " keys per shortcut");
        int[] codes = new int[total];
        int i = 0;
        for (int c : modifiers) codes[i++] = c;
        for (int c : others) codes[i++] = c;
        return new Result(codes, label.toString(), null);
    }

    private static void addOnce(List<Integer> list, int code) {
        if (!list.contains(code)) list.add(code);
    }

    static boolean isModifier(int code) {
        switch (code) {
            case KEYCODE_CTRL_LEFT:
            case KEYCODE_CTRL_RIGHT:
            case KEYCODE_ALT_LEFT:
            case KEYCODE_ALT_RIGHT:
            case KEYCODE_SHIFT_LEFT:
            case KEYCODE_SHIFT_RIGHT:
            case KEYCODE_META_LEFT:
            case KEYCODE_META_RIGHT:
                return true;
            default:
                return false;
        }
    }

    private static Result error(String message) {
        return new Result(null, null, message);
    }

    private static Key lookup(String token) {
        if (token.length() == 1) {
            Key single = CHARS.get(token.charAt(0));
            if (single != null) return single;
        }
        return NAMES.get(normalize(token));
    }

    /**
     * Linux-Scancode der Taste auf einer deutschen PC-Tastatur (das, was eine echte Tastatur
     * zusätzlich zum Keycode meldet), 0 wenn unbekannt. Umkehrung von Androids Generic.kl
     * plus den Umbelegungen des deutschen Layouts (ß, -, <, Y, Z).
     */
    public static int scanCode(int keyCode) {
        Integer sc = SCAN_CODES.get(keyCode);
        return sc != null ? sc : 0;
    }

    private static final Map<Integer, Integer> SCAN_CODES = new HashMap<>();

    static {
        // Buchstaben a … z (Y/Z wie auf QWERTZ vertauscht)
        int[] letters = {30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50,
                49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 44, 21};
        for (int i = 0; i < 26; i++) SCAN_CODES.put(KEYCODE_A + i, letters[i]);
        for (int i = 1; i <= 9; i++) SCAN_CODES.put(KEYCODE_0 + i, i + 1);
        SCAN_CODES.put(KEYCODE_0, 11);
        SCAN_CODES.put(KEYCODE_SLASH, 12);         // ß
        SCAN_CODES.put(KEYCODE_EQUALS, 13);        // ´
        SCAN_CODES.put(KEYCODE_LEFT_BRACKET, 26);  // Ü
        SCAN_CODES.put(KEYCODE_RIGHT_BRACKET, 27); // +
        SCAN_CODES.put(KEYCODE_SEMICOLON, 39);     // Ö
        SCAN_CODES.put(KEYCODE_APOSTROPHE, 40);    // Ä
        SCAN_CODES.put(KEYCODE_GRAVE, 41);         // ^
        SCAN_CODES.put(KEYCODE_BACKSLASH, 43);     // #
        SCAN_CODES.put(KEYCODE_COMMA, 51);
        SCAN_CODES.put(KEYCODE_PERIOD, 52);
        SCAN_CODES.put(KEYCODE_MINUS, 53);         // -
        SCAN_CODES.put(KEYCODE_PLUS, 86);          // <
        SCAN_CODES.put(KEYCODE_ESCAPE, 1);
        SCAN_CODES.put(KEYCODE_DEL, 14);
        SCAN_CODES.put(KEYCODE_TAB, 15);
        SCAN_CODES.put(KEYCODE_ENTER, 28);
        SCAN_CODES.put(KEYCODE_SPACE, 57);
        SCAN_CODES.put(KEYCODE_CAPS_LOCK, 58);
        SCAN_CODES.put(KEYCODE_CTRL_LEFT, 29);
        SCAN_CODES.put(KEYCODE_CTRL_RIGHT, 97);
        SCAN_CODES.put(KEYCODE_SHIFT_LEFT, 42);
        SCAN_CODES.put(KEYCODE_SHIFT_RIGHT, 54);
        SCAN_CODES.put(KEYCODE_ALT_LEFT, 56);
        SCAN_CODES.put(KEYCODE_ALT_RIGHT, 100);
        SCAN_CODES.put(KEYCODE_META_LEFT, 125);
        SCAN_CODES.put(KEYCODE_META_RIGHT, 126);
        for (int i = 0; i < 10; i++) SCAN_CODES.put(KEYCODE_F1 + i, 59 + i);
        SCAN_CODES.put(KEYCODE_F1 + 10, 87);
        SCAN_CODES.put(KEYCODE_F1 + 11, 88);
        SCAN_CODES.put(KEYCODE_SYSRQ, 99);
        SCAN_CODES.put(KEYCODE_SCROLL_LOCK, 70);
        SCAN_CODES.put(KEYCODE_BREAK, 119);
        SCAN_CODES.put(KEYCODE_INSERT, 110);
        SCAN_CODES.put(KEYCODE_FORWARD_DEL, 111);
        SCAN_CODES.put(KEYCODE_MOVE_HOME, 102);
        SCAN_CODES.put(KEYCODE_MOVE_END, 107);
        SCAN_CODES.put(KEYCODE_PAGE_UP, 104);
        SCAN_CODES.put(KEYCODE_PAGE_DOWN, 109);
        SCAN_CODES.put(KEYCODE_DPAD_UP, 103);
        SCAN_CODES.put(KEYCODE_DPAD_LEFT, 105);
        SCAN_CODES.put(KEYCODE_DPAD_RIGHT, 106);
        SCAN_CODES.put(KEYCODE_DPAD_DOWN, 108);
        SCAN_CODES.put(KEYCODE_MENU, 139);
        SCAN_CODES.put(KEYCODE_NUM_LOCK, 69);
        int[] numpad = {82, 79, 80, 81, 75, 76, 77, 71, 72, 73}; // Num 0 … 9
        for (int i = 0; i < 10; i++) SCAN_CODES.put(KEYCODE_NUMPAD_0 + i, numpad[i]);
        SCAN_CODES.put(KEYCODE_NUMPAD_DIVIDE, 98);
        SCAN_CODES.put(KEYCODE_NUMPAD_MULTIPLY, 55);
        SCAN_CODES.put(KEYCODE_NUMPAD_SUBTRACT, 74);
        SCAN_CODES.put(KEYCODE_NUMPAD_ADD, 78);
        SCAN_CODES.put(KEYCODE_NUMPAD_DOT, 83);
        SCAN_CODES.put(KEYCODE_NUMPAD_ENTER, 96);
    }

    /** Kleinbuchstaben, Umlaute ausschreiben, Leerzeichen/Punkte/Bindestriche/Unterstriche/Klammern weg. */
    static String normalize(String token) {
        String s = token.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '.' || c == '-' || c == '_' || c == '\t' || c == '(' || c == ')') {
                continue;
            }
            out.append(c);
        }
        String n = out.toString();
        // Präfix "keycode" (z. B. KEYCODE_INSERT) zulassen
        if (n.startsWith("keycode") && n.length() > 7) n = n.substring(7);
        return n;
    }
}
