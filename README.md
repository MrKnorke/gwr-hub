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
  | Karte | gwrevival.com/map |
  | Teams | gwskillbar.com/teams |
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
4. Menü **GWR Hub → 1. Projekt einrichten**. Das erstellt die Szene und setzt Paketname, IL2CPP/ARM64, Min-SDK 26 und *Application Entry Point = Activity*.
5. Menü **GWR Hub → 2. APK bauen**. Die APK landet in `Builds/GWRHub.apk`.
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
