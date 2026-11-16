using UnityEngine;
#if UNITY_ANDROID
using UnityEngine.Android;
#endif

namespace GWRHub
{
    /// <summary>
    /// Startbildschirm des GWR Hubs. Holt die nötigen Berechtigungen, startet das Overlay
    /// automatisch und bietet Buttons zum Starten/Stoppen bzw. zum Wechseln ins Spiel.
    /// Die UI wird per IMGUI gezeichnet, damit keine Prefabs/Canvas-Assets nötig sind.
    /// </summary>
    public class HubApp : MonoBehaviour
    {
        private const string NotificationPermission = "android.permission.POST_NOTIFICATIONS";

        private bool _hasPermission;
        private bool _running;
        private bool _autoStartPending = true;
        private float _nextRefresh;

        private GUIStyle _title, _text, _button, _box;
        private Texture2D _bgTex, _btnTex, _btnHoverTex, _boxTex;

        private void Start()
        {
            Application.targetFrameRate = 30;
            Screen.sleepTimeout = SleepTimeout.SystemSetting;

            RequestNotificationPermissionIfNeeded();
            Refresh();
            TryAutoStart();
        }

        private void OnApplicationFocus(bool hasFocus)
        {
            // Nach Rückkehr aus den System-Einstellungen Status neu prüfen und ggf. starten
            if (!hasFocus) return;
            Refresh();
            TryAutoStart();
        }

        private void Update()
        {
            if (Time.unscaledTime >= _nextRefresh)
            {
                _nextRefresh = Time.unscaledTime + 1f;
                Refresh();
            }

            if (Input.GetKeyDown(KeyCode.Escape)) // Android-Zurück-Taste
                OverlayPlugin.MoveAppToBackground();
        }

        private void Refresh()
        {
            _hasPermission = OverlayPlugin.HasOverlayPermission;
            _running = OverlayPlugin.IsRunning;
        }

        private void TryAutoStart()
        {
            if (!_autoStartPending || !_hasPermission || _running) return;
            _autoStartPending = false;
            _running = OverlayPlugin.Start();
        }

        private static void RequestNotificationPermissionIfNeeded()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            // Android 13+: ohne diese Berechtigung läuft das Overlay trotzdem,
            // nur die Benachrichtigung (mit "Overlay beenden") ist dann unsichtbar.
            if (OverlayPlugin.SdkInt >= 33 && !Permission.HasUserAuthorizedPermission(NotificationPermission))
                Permission.RequestUserPermission(NotificationPermission);
#endif
        }

        // ------------------------------------------------------------------ UI

        private void OnGUI()
        {
            EnsureStyles();

            float scale = Mathf.Max(1f, Mathf.Min(Screen.width, Screen.height) / 420f);
            GUI.matrix = Matrix4x4.Scale(new Vector3(scale, scale, 1f));
            float w = Screen.width / scale;
            float h = Screen.height / scale;

            GUI.DrawTexture(new Rect(0, 0, w, h), _bgTex);

            float panelW = Mathf.Min(380f, w - 32f);
            GUILayout.BeginArea(new Rect((w - panelW) / 2f, 24f, panelW, h - 48f));

            GUILayout.Label("GWR Hub", _title);
            GUILayout.Label("Overlay für Guild Wars Reforged", _text);
            GUILayout.Space(16);

            GUILayout.BeginVertical(_box);
            GUILayout.Label("Über anderen Apps einblenden:  " + (_hasPermission ? "erlaubt" : "fehlt"), _text);
            GUILayout.Label("Overlay:  " + (_running ? "aktiv" : "inaktiv"), _text);
            GUILayout.EndVertical();
            GUILayout.Space(16);

            if (!_hasPermission)
            {
                GUILayout.Label("Bitte erlaube GWR Hub im nächsten Bildschirm, über anderen Apps angezeigt zu werden.", _text);
                if (GUILayout.Button("Berechtigung erteilen", _button))
                {
                    _autoStartPending = true;
                    OverlayPlugin.RequestOverlayPermission();
                }
            }
            else if (!_running)
            {
                if (GUILayout.Button("Overlay starten", _button))
                    _running = OverlayPlugin.Start();
            }
            else
            {
                if (GUILayout.Button("Zum Spiel wechseln", _button))
                    OverlayPlugin.MoveAppToBackground();
                GUILayout.Space(8);
                if (GUILayout.Button("Overlay beenden", _button))
                {
                    OverlayPlugin.Stop();
                    _running = false;
                }
            }

            GUILayout.Space(20);
            GUILayout.Label(
                "So geht's: Overlay starten, Guild Wars Reforged öffnen und oben links auf den GW-Button tippen. " +
                "Dort findest du PvX Wiki, GW Wiki, Kamadan-Handel, GW Market, die Karte und Teams. Mit „—“ oder der Zurück-Taste minimierst du das Fenster – " +
                "die aktuelle Seite bleibt erhalten. Den Button kannst du per Ziehen verschieben.",
                _text);

            GUILayout.EndArea();
        }

        private void EnsureStyles()
        {
            if (_title != null) return;

            Color gold = new Color32(0xC9, 0xA2, 0x3F, 0xFF);
            Color text = new Color32(0xED, 0xE6, 0xD6, 0xFF);

            _bgTex = MakeTex(new Color32(0x1C, 0x1A, 0x17, 0xFF));
            _btnTex = MakeTex(new Color32(0x7A, 0x10, 0x10, 0xFF));
            _btnHoverTex = MakeTex(new Color32(0xA3, 0x18, 0x18, 0xFF));
            _boxTex = MakeTex(new Color32(0x2A, 0x26, 0x20, 0xFF));

            _title = new GUIStyle(GUI.skin.label)
            {
                fontSize = 30, fontStyle = FontStyle.Bold, alignment = TextAnchor.MiddleCenter,
                normal = { textColor = gold }
            };
            _text = new GUIStyle(GUI.skin.label)
            {
                fontSize = 15, wordWrap = true, alignment = TextAnchor.UpperLeft,
                normal = { textColor = text }
            };
            _button = new GUIStyle(GUI.skin.button)
            {
                fontSize = 18, fontStyle = FontStyle.Bold, fixedHeight = 52,
                normal = { background = _btnTex, textColor = gold },
                hover = { background = _btnHoverTex, textColor = gold },
                active = { background = _btnHoverTex, textColor = Color.white }
            };
            _box = new GUIStyle(GUI.skin.box)
            {
                padding = new RectOffset(12, 12, 10, 10),
                normal = { background = _boxTex }
            };
        }

        private static Texture2D MakeTex(Color c)
        {
            var t = new Texture2D(1, 1);
            t.SetPixel(0, 0, c);
            t.Apply();
            return t;
        }
    }
}
