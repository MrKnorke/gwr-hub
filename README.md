# GWR Hub – Wiki Overlay for Guild Wars Reforged (Android)

*[Deutsche Version weiter unten](#gwr-hub--wiki-overlay-für-guild-wars-reforged-android)*

A Unity app that activates a floating overlay on launch:

- **Small "GW" button in the top left** that sits on top of every app, including Guild Wars Reforged. You can drag it to move it; its position is saved.
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
- **Minimizing** ("—", the Back key, or tapping the button again) only hides the popup. The open page and its history are kept. The current URL of each tab is also saved and reloaded even after the overlay restarts.
- Quit via the notification ("Overlay beenden" / stop overlay) or in the Hub app.

## Structure

| File | Purpose |
|---|---|
| `Assets/Plugins/Android/OverlayService.java` | Foreground service: button, popup, WebViews, saving state |
| `Assets/Plugins/Android/OverlayBridge.java` | Static calls for Unity (permission, start/stop) |
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

- **Kleiner „GW“-Button oben links**, der über jeder App liegt, also auch über Guild Wars Reforged. Du kannst ihn per Ziehen verschieben, die Position wird gespeichert.
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
- **Minimieren** („—“, Zurück-Taste oder erneut auf den Button tippen) blendet das Popup nur aus. Die geöffnete Seite und der Verlauf bleiben erhalten. Die aktuelle URL pro Tab wird zusätzlich gespeichert und auch nach einem Neustart des Overlays wieder geladen.
- Beenden über die Benachrichtigung („Overlay beenden“) oder in der Hub-App.

## Aufbau

| Datei | Zweck |
|---|---|
| `Assets/Plugins/Android/OverlayService.java` | Foreground-Service: Button, Popup, WebViews, Zustand speichern |
| `Assets/Plugins/Android/OverlayBridge.java` | Statische Aufrufe für Unity (Berechtigung, Start/Stop) |
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
