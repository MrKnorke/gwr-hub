using UnityEngine;

namespace GWRHub
{
    /// <summary>
    /// Dünner C#-Wrapper um das Java-Plugin com.gwrhub.overlay.OverlayBridge.
    /// Im Editor wird das Verhalten nur simuliert.
    /// </summary>
    public static class OverlayPlugin
    {
#if UNITY_ANDROID && !UNITY_EDITOR
        private const string BridgeClass = "com.gwrhub.overlay.OverlayBridge";

        private static AndroidJavaObject Activity
        {
            get
            {
                using (var player = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
                    return player.GetStatic<AndroidJavaObject>("currentActivity");
            }
        }

        private static T Call<T>(string method, params object[] args)
        {
            using (var bridge = new AndroidJavaClass(BridgeClass))
                return bridge.CallStatic<T>(method, args);
        }

        private static void Call(string method, params object[] args)
        {
            using (var bridge = new AndroidJavaClass(BridgeClass))
                bridge.CallStatic(method, args);
        }

        public static bool HasOverlayPermission => Call<bool>("canDrawOverlays", Activity);
        public static bool IsRunning => Call<bool>("isOverlayRunning");
        public static int SdkInt => Call<int>("sdkInt");

        public static void RequestOverlayPermission() => Call("requestOverlayPermission", Activity);
        public static bool Start() => Call<bool>("startOverlay", Activity);
        public static void Stop() => Call("stopOverlay", Activity);
        public static void MoveAppToBackground() => Call("moveToBack", Activity);
#else
        private static bool _simRunning;

        public static bool HasOverlayPermission => true;
        public static bool IsRunning => _simRunning;
        public static int SdkInt => 0;

        public static void RequestOverlayPermission() => Debug.Log("[GWR Hub] (Editor) Overlay-Berechtigung angefragt");
        public static bool Start() { _simRunning = true; Debug.Log("[GWR Hub] (Editor) Overlay gestartet"); return true; }
        public static void Stop() { _simRunning = false; Debug.Log("[GWR Hub] (Editor) Overlay gestoppt"); }
        public static void MoveAppToBackground() => Debug.Log("[GWR Hub] (Editor) App in den Hintergrund");
#endif
    }
}
