# GWR Hub – Wiki Overlay for Guild Wars Reforged (Android)

*[Deutsche Version weiter unten](#gwr-hub--wiki-overlay-für-guild-wars-reforged-android)*

A Unity app that activates a floating overlay on launch:

- **Small "GW" button in the top left** that sits on top of every app, including Guild Wars Reforged. You can drag it to move it; its position is saved separately for portrait and landscape, so it can sit in a different spot in each orientation.
- **Tapping** it opens a popup with these tabs (the tab bar can be swiped sideways), plus Back / Forward / Reload / Home:

  | Tab | Site |
  |---|---|
  | PvX Wiki | gwpvx.fandom.com |
  | GW Wiki | wiki.guildwars.com |
  | Kamadan | kamadan.gwtoolbox.com (trade chat) |
  | GW Market | gwmarket.net |
  | Map | gwrevival.com/map |
  | Teams | gwskillbar.com/teams |
  | Today | foogw.github.io (Guild Wars Today: dailies) |
  | YouTube | m.youtube.com, starts with a search for "Guild Wars Reforged" |
  | Keys | Your keyboard shortcuts (see below) |
- **YouTube tab**: YouTube links stay in the tab instead of jumping to the YouTube app. ⌂ goes back to the "Guild Wars Reforged" search, and ↗ (only on this tab) opens the current page in the YouTube app or browser. **Picture-in-picture:** if a video is playing in the YouTube tab when you minimize ("—", Back or the GW button), it keeps playing with sound in a small floating bubble over the game, and the game gets keyboard and touch control back. The bubble uses YouTube's lightweight embedded player and continues from the same position; the full YouTube page is paused meanwhile, and expanding back into the hub continues the video there. If a video can't be embedded, the bubble falls back to showing the YouTube page's own player. Drag the bubble to move it (its position is saved separately for portrait and landscape). Tap it to show the controls: expand back into the hub (corner icon or the GW button), ▶/❚❚, and ✕ to close it, which pauses the video. Videos still pause when you switch tabs, minimize from another tab or stop the overlay. The player's fullscreen button fills the popup's content area, and Back exits it. Hidden tabs are paused while the hub is open, so they don't slow down the video.
- **Keys tab – keyboard shortcuts**: type a key name into the text field (the keyboard opens when you tap it), e.g. `einfg`/`insert`, `entf`/`del`, `strg`/`ctrl`, `pos1`, `bild auf`, `esc`, `f5`, `a`, or a combination like `strg+c` / `alt+f4`, and tap **+**. Each shortcut becomes a small draggable bubble over the game, like the GW button. Tapping a bubble presses the key(s) in the game, dragging moves it (positions are saved separately for portrait and landscape). In the list, the text field next to each shortcut sets the text shown on its bubble (e.g. `Held 1` instead of `1 (ZB)`, up to 24 characters; empty shows the key name). The bubble grows or shrinks to fit the text. Remove shortcuts with ✕. The bubbles hide while the popup is open, because the popup would receive the keys instead. **Android only lets apps press keys in other apps with extra rights**, so this needs either [Shizuku](https://shizuku.rikka.app/) (no root; start it once via wireless debugging after each reboot, then tap *Allow in Shizuku* in the Keys tab) or a rooted phone. Single characters follow the German PC keyboard (QWERTZ): `ü`/`Ü`, `ö`, `ä`, `ß`, `^`, `´`, `+`, `#`, `<`, `-` are their own keys, characters on the Shift or AltGr level become the matching combination (`*` = Shift + `+`, `?` = Shift + `ß`, `@` = AltGr + `Q`, `{` = AltGr + `7` …). Numpad keys (`num1` or GWR's notation `1 (ZB)`) are sent with Num Lock on. Keys are sent like a German keyboard reports them to Android, so in a text field with the US layout they may show a different character (e.g. `#` types `\`). If the Keys tab shows *Key injection: input command*, the direct method couldn't start; then numpad keys don't work and key combinations need Android 12+.
- If a page can't be loaded (e.g. no connection), the tab shows a message with **Try again**.
- **Minimizing** ("—", the Back key, or tapping the button again) only hides the popup. The open page and its history are kept. The current URL of each tab is also saved and reloaded even after the overlay restarts.
- **Timer** (⏱ in the popup header): a manual countdown for buffs, events etc. Pick a preset (30s … 30m) or adjust with −1m / +1m / −10s / +10s, then ▶ to start, ❚❚ to pause, ■ to reset. ±-steps also work on a running timer. While a timer is set, the minimized button shows a compact `MM:SS` badge right below it (it follows the button when dragged, and jumps above it near the bottom edge). Tap the badge to pause/resume, long-press to restart. While running it plays a short 1-second beep every 30 seconds. At `00:00` it blinks red, beeps once and vibrates briefly; tap it to dismiss. Switch between **Countdown** and **Stopwatch** with the chips at the start of the timer bar (the choice is remembered). The stopwatch counts up from `00:00`, shows a blue accent instead of gold, its progress line works as a seconds hand, and it stops at `99:59`. Presets and ±-steps only apply to the countdown. Runs purely inside the app, no game memory access.
- Quit via the notification ("Overlay beenden" / stop overlay) or in the Hub app.

## Structure

| File | Purpose |
|---|---|
| `Assets/Plugins/Android/OverlayService.java` | Foreground service: button, popup, WebViews, saving state |
| `Assets/Plugins/Android/OverlayBridge.java` | Static calls for Unity (permission, start/stop) |
| `Assets/Plugins/Android/OverlayTimer.java`, `TimerState.java`, `TimerMode.java`, `TimerFormat.java` | Pure timer logic (countdown + stopwatch) and `MM:SS` formatting (no Android dependencies) |
| `Assets/Plugins/Android/OverlayTimerController.java` | Shared timer instance: ticking, saved duration, finish signal |
| `Tests/run-timer-tests.ps1` | Runs the timer unit tests (`Tests/timer/TimerTests.java`, needs only a JDK) |
| `Assets/Plugins/Android/YouTubeLinks.java` | YouTube tab: default search URL, YouTube vs. other links, app-link rewriting (no Android dependencies) |
| `Tests/run-youtube-tests.ps1` | Runs the YouTube link unit tests (`Tests/youtube/YouTubeLinksTests.java`, needs only a JDK) |
| `Assets/Plugins/Android/KeyNames.java` | Keys tab: German/English key names → Android key codes (no Android dependencies) |
| `Assets/Plugins/Android/KeySender.java` | Sends keys to the game via Shizuku or root, through `KeyInjector` (fallback: `input keyevent`) |
| `Assets/Plugins/Android/KeyInjector.java` | Runs via `app_process` in the Shizuku/root shell and injects keys with Num Lock on |
| `Assets/Plugins/Android/ShortcutBubbles.java` | Draggable shortcut bubbles, saved list and positions |
| `Assets/Editor/GWRHubGradleDeps.cs` | Adds the Shizuku library (Maven Central) to the Gradle build |
| `Tests/run-keys-tests.ps1` | Runs the key name unit tests (`Tests/keys/KeyNamesTests.java`, needs only a JDK) |
| `Assets/Plugins/Android/AndroidManifest.xml` | Permissions (`SYSTEM_ALERT_WINDOW`, foreground service) + service |
| `Assets/Scripts/HubApp.cs` | Start screen (IMGUI): request permission, start overlay automatically |
| `Assets/Scripts/OverlayPlugin.cs` | C# wrapper around the Java plugin |
| `Assets/Editor/GWRHubSetup.cs` | **GWR Hub** menu: set up the project and build the APK |

Tab titles and URLs are defined in `OverlayService.java` in `TAB_TITLES` / `TAB_HOME`. Append new sites at the end, because the index is used to map the saved state.

## Building

1. Install **Unity 6** (targeted at 6000.5.x) with **Android Build Support**
   (Unity Hub → Installs → 6000.5.8f1 → Add modules → *Android Build Support* incl. *OpenJDK* and *Android SDK & NDK Tools*).
   Unity 6 ships with Android SDK 34+, which is required for the `specialUse` foreground service type.
2. Unity Hub → **Add → Add project from disk** → select this folder and open it.
3. **File → Build Profiles** → Android → **Switch Platform**.
4. Menu **GWR Hub → 1. Set up project**. This creates the scene and sets the package name, IL2CPP/ARM64, min SDK 26 and *Application Entry Point = Activity*.
5. Menu **GWR Hub → 2. Build APK**. The APK ends up in `Builds/GWRHub.apk`.
   Alternatively use Build Profiles → *Build And Run* to deploy directly to the phone.

## Installation and use on the phone

1. Install the APK (allow installation from unknown sources).
2. Open GWR Hub → **Berechtigung erteilen** (grant permission) → enable GWR Hub under "Display over other apps" → go back.
   The overlay then starts automatically. Allow notifications (Android 13+) so that "Overlay beenden" is visible in the notification bar.
3. **Zum Spiel wechseln** (switch to game) → launch Guild Wars Reforged → tap **GW** in the top left.

## Notes

- If the APK is sideloaded, Android 13+ may block the overlay permission as a "restricted setting".
  In that case, unlock it under *Settings → Apps → GWR Hub → ⋮ → Allow restricted settings*.
- Some manufacturers (Xiaomi/MIUI, Huawei, Samsung Game Mode) have additional toggles for "Show pop-up windows" or overlays in the Game Launcher.
- While the popup is open, it has keyboard focus (for the wiki search). Touches outside the popup still go to the game.

---

# GWR Hub – Wiki-Overlay für Guild Wars Reforged (Android)

Unity-App, die beim Start ein schwebendes Overlay aktiviert:

- **Kleiner „GW“-Button oben links**, der über jeder App liegt, also auch über Guild Wars Reforged. Du kannst ihn per Ziehen verschieben, die Position wird getrennt für Hoch- und Querformat gespeichert, er kann also in jeder Ausrichtung woanders sitzen.
- **Antippen** öffnet ein Popup mit diesen Tabs (die Tab-Leiste lässt sich seitlich wischen), dazu Zurück / Vor / Neu laden / Startseite:

  | Tab | Seite |
  |---|---|
  | PvX Wiki | gwpvx.fandom.com |
  | GW Wiki | wiki.guildwars.com |
  | Kamadan | kamadan.gwtoolbox.com (Handelschat) |
  | GW Market | gwmarket.net |
  | Map | gwrevival.com/map |
  | Teams | gwskillbar.com/teams |
  | Today | foogw.github.io (Guild Wars Today: Dailies) |
  | YouTube | m.youtube.com, startet mit der Suche nach „Guild Wars Reforged“ |
  | Keys | Deine Tasten-Shortcuts (siehe unten) |
- **YouTube-Tab**: YouTube-Links bleiben im Tab, statt in die YouTube-App zu springen. ⌂ führt zurück zur Suche „Guild Wars Reforged“, ↗ (nur in diesem Tab) öffnet die aktuelle Seite in der YouTube-App bzw. im Browser. **Bild-in-Bild:** Läuft im YouTube-Tab gerade ein Video, wenn du minimierst („—“, Zurück oder GW-Button), spielt es mit Ton in einer kleinen schwebenden Bubble über dem Spiel weiter, und das Spiel bekommt Tastatur und Touch-Steuerung zurück. Die Bubble nutzt YouTubes schlanken Embed-Player und spielt an derselben Stelle weiter; die eigentliche YouTube-Seite ist solange angehalten, und zurück im Hub läuft das Video dort weiter. Darf ein Video nicht eingebettet werden, zeigt die Bubble stattdessen den Player der YouTube-Seite. Die Bubble lässt sich verschieben (Position wird getrennt für Hoch- und Querformat gespeichert). Antippen zeigt die Knöpfe: zurück in den Hub (Ecken-Symbol oder GW-Button), ▶/❚❚, und ✕ schließt sie und pausiert das Video. Beim Tab-Wechsel, beim Minimieren aus einem anderen Tab und beim Beenden des Overlays pausieren Videos weiterhin. Die Vollbild-Taste des Players füllt den Inhaltsbereich des Popups, Zurück beendet das Vollbild. Versteckte Tabs werden angehalten, solange der Hub offen ist, damit sie das Video nicht bremsen.
- **Keys-Tab – Tasten-Shortcuts**: Tastennamen ins Textfeld tippen (beim Antippen öffnet sich die Tastatur), z. B. `einfg`/`insert`, `entf`/`del`, `strg`/`ctrl`, `pos1`, `bild auf`, `esc`, `f5`, `a` oder eine Kombination wie `strg+c` / `alt+f4`, dann **+** drücken. Jeder Shortcut wird zu einer kleinen, verschiebbaren Bubble über dem Spiel, wie der GW-Button. Antippen drückt die Taste(n) im Spiel, Ziehen verschiebt die Bubble (Position wird getrennt für Hoch- und Querformat gespeichert). In der Liste legt das Textfeld neben jedem Shortcut den Text der Bubble fest (z. B. `Held 1` statt `1 (ZB)`, bis zu 24 Zeichen; leer zeigt den Tastennamen). Die Bubble wird so breit wie der Text. Entfernen per ✕. Solange das Popup offen ist, sind die Bubbles ausgeblendet, weil sonst das Popup die Tasten bekäme. **Android erlaubt Apps nur mit Zusatzrechten, Tasten in anderen Apps zu drücken**: Nötig ist entweder [Shizuku](https://shizuku.rikka.app/) (ohne Root; nach jedem Neustart einmal per kabellosem Debugging starten, dann im Keys-Tab *Allow in Shizuku* tippen) oder ein gerootetes Handy. Einzelne Zeichen folgen der deutschen PC-Tastatur (QWERTZ): `ü`/`Ü`, `ö`, `ä`, `ß`, `^`, `´`, `+`, `#`, `<`, `-` sind eigene Tasten, Zeichen auf der Shift- oder AltGr-Ebene werden zur passenden Kombination (`*` = Shift + `+`, `?` = Shift + `ß`, `@` = AltGr + `Q`, `{` = AltGr + `7` …). Ziffernblock-Tasten (`num1` oder in GWR-Schreibweise `1 (ZB)`) werden mit eingeschaltetem Num Lock geschickt. Die Tasten kommen so an, wie eine deutsche Tastatur sie Android meldet; in einem Textfeld mit US-Layout kann deshalb ein anderes Zeichen erscheinen (z. B. `\` statt `#`). Zeigt der Keys-Tab *Key injection: input command*, konnte der direkte Weg nicht starten; dann gehen Ziffernblock-Tasten nicht, und Tastenkombinationen brauchen Android 12+.
- Lässt sich eine Seite nicht laden (z. B. ohne Verbindung), zeigt der Tab eine Meldung mit **Try again**.
- **Minimieren** („—“, Zurück-Taste oder erneut auf den Button tippen) blendet das Popup nur aus. Die geöffnete Seite und der Verlauf bleiben erhalten. Die aktuelle URL pro Tab wird zusätzlich gespeichert und auch nach einem Neustart des Overlays wieder geladen.
- **Timer** (⏱ in der Kopfzeile des Popups): manueller Countdown für Buffs, Events usw. Preset wählen (30s … 30m) oder mit −1m / +1m / −10s / +10s anpassen, dann ▶ starten, ❚❚ pausieren, ■ zurücksetzen. Die ±-Schritte wirken auch auf einen laufenden Timer. Solange ein Timer gesetzt ist, zeigt der minimierte Button direkt darunter eine kompakte `MM:SS`-Anzeige (wandert beim Verschieben mit und springt am unteren Rand über den Button). Antippen = Pause/Fortsetzen, lang drücken = Neustart. Während der Timer läuft, ertönt alle 30 Sekunden ein kurzer Signalton (1 s). Bei `00:00` blinkt sie rot, piept einmal und vibriert kurz; Antippen blendet sie aus. Über die Chips **Countdown** / **Stopwatch** am Anfang der Timer-Leiste wechselst du zur Stoppuhr (die Wahl wird gespeichert). Sie zählt ab `00:00` hoch, hat einen blauen statt goldenen Akzent, der Fortschrittsstrich läuft als Sekundenzeiger, und sie stoppt bei `99:59`. Presets und ±-Schritte gelten nur für den Countdown. Läuft rein in der App, kein Zugriff auf den Spielspeicher.
- Beenden über die Benachrichtigung („Overlay beenden“) oder in der Hub-App.

## Aufbau

| Datei | Zweck |
|---|---|
| `Assets/Plugins/Android/OverlayService.java` | Foreground-Service: Button, Popup, WebViews, Zustand speichern |
| `Assets/Plugins/Android/OverlayBridge.java` | Statische Aufrufe für Unity (Berechtigung, Start/Stop) |
| `Assets/Plugins/Android/OverlayTimer.java`, `TimerState.java`, `TimerMode.java`, `TimerFormat.java` | Reine Timer-Logik (Countdown + Stoppuhr) und `MM:SS`-Formatierung (ohne Android-Abhängigkeiten) |
| `Assets/Plugins/Android/OverlayTimerController.java` | Zentrale Timer-Instanz: Ticken, gespeicherte Dauer, Ablauf-Signal |
| `Tests/run-timer-tests.ps1` | Führt die Timer-Unit-Tests aus (`Tests/timer/TimerTests.java`, braucht nur ein JDK) |
| `Assets/Plugins/Android/YouTubeLinks.java` | YouTube-Tab: Standard-Such-URL, YouTube- vs. andere Links, App-Links umleiten (ohne Android-Abhängigkeiten) |
| `Tests/run-youtube-tests.ps1` | Führt die YouTube-Link-Unit-Tests aus (`Tests/youtube/YouTubeLinksTests.java`, braucht nur ein JDK) |
| `Assets/Plugins/Android/KeyNames.java` | Keys-Tab: deutsche/englische Tastennamen → Android-Keycodes (ohne Android-Abhängigkeiten) |
| `Assets/Plugins/Android/KeySender.java` | Schickt Tasten über Shizuku oder Root ans Spiel, per `KeyInjector` (Fallback: `input keyevent`) |
| `Assets/Plugins/Android/KeyInjector.java` | Läuft per `app_process` in der Shizuku-/Root-Shell und speist Tasten mit Num Lock an ein |
| `Assets/Plugins/Android/ShortcutBubbles.java` | Verschiebbare Shortcut-Bubbles, gespeicherte Liste und Positionen |
| `Assets/Editor/GWRHubGradleDeps.cs` | Bindet die Shizuku-Bibliothek (Maven Central) in den Gradle-Build ein |
| `Tests/run-keys-tests.ps1` | Führt die Tastennamen-Unit-Tests aus (`Tests/keys/KeyNamesTests.java`, braucht nur ein JDK) |
| `Assets/Plugins/Android/AndroidManifest.xml` | Berechtigungen (`SYSTEM_ALERT_WINDOW`, Foreground-Service) + Service |
| `Assets/Scripts/HubApp.cs` | Startbildschirm (IMGUI): Berechtigung holen, Overlay automatisch starten |
| `Assets/Scripts/OverlayPlugin.cs` | C#-Wrapper um das Java-Plugin |
| `Assets/Editor/GWRHubSetup.cs` | Menü **GWR Hub**: Projekt einrichten und APK bauen |

Titel und URLs der Tabs stehen in `OverlayService.java` in `TAB_TITLES` / `TAB_HOME`. Neue Seiten hinten anhängen, weil der Index den gespeicherten Zustand zuordnet.

## Bauen

1. **Unity 6** (ausgelegt auf 6000.5.x) mit **Android Build Support** installieren
   (Unity Hub → Installs → 6000.5.8f1 → Add modules → *Android Build Support* inkl. *OpenJDK* und *Android SDK & NDK Tools*).
   Unity 6 bringt Android SDK 34+ mit, das für den Foreground-Service-Typ `specialUse` nötig ist.
2. Unity Hub → **Add → Add project from disk** → diesen Ordner wählen und öffnen.
3. **File → Build Profiles** → Android → **Switch Platform**.
4. Menü **GWR Hub → 1. Set up project**. Das erstellt die Szene und setzt Paketname, IL2CPP/ARM64, Min-SDK 26 und *Application Entry Point = Activity*.
5. Menü **GWR Hub → 2. Build APK**. Die APK landet in `Builds/GWRHub.apk`.
   Alternativ über Build Profiles → *Build And Run* direkt aufs Handy.

## Installation und Nutzung auf dem Handy

1. APK installieren (Installation aus unbekannten Quellen erlauben).
2. GWR Hub öffnen → **Berechtigung erteilen** → bei „Über anderen Apps einblenden“ GWR Hub aktivieren → zurück.
   Das Overlay startet danach automatisch. Benachrichtigungen erlauben (Android 13+), damit „Overlay beenden“ in der Leiste sichtbar ist.
3. **Zum Spiel wechseln** → Guild Wars Reforged starten → oben links auf **GW** tippen.

## Hinweise

- Wird die APK per Sideload installiert, blockiert Android 13+ die Overlay-Berechtigung eventuell als „eingeschränkte Einstellung“.
  Dann unter *Einstellungen → Apps → GWR Hub → ⋮ → Eingeschränkte Einstellungen zulassen* freischalten.
- Manche Hersteller (Xiaomi/MIUI, Huawei, Samsung-Spielmodus) haben zusätzliche Schalter für „Pop-up-Fenster anzeigen“ bzw. Overlays im Game Launcher.
- Solange das Popup offen ist, hat es den Tastaturfokus (für die Wiki-Suche). Berührungen außerhalb des Popups gehen weiterhin ans Spiel.
