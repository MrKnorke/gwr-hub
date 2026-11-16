using System.IO;
using UnityEditor;
using UnityEditor.Build;
using UnityEditor.Build.Reporting;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.Rendering;
using UnityEngine.Rendering.Universal;

namespace GWRHub.EditorTools
{
    /// <summary>
    /// Menü "GWR Hub": richtet Szene + Android Player Settings ein und baut die APK.
    /// </summary>
    [InitializeOnLoad]
    public static class GWRHubSetup
    {
        // Läuft bei jedem Editor-Start / Skript-Reload: stellt sicher, dass die
        // Einstellungen stimmen, auch wenn nur über "Build Profiles" gebaut wird.
        static GWRHubSetup()
        {
            EditorApplication.delayCall += () =>
            {
                if (EditorApplication.isPlayingOrWillChangePlaymode) return;
                SetupProjectSilently();
            };
        }

        private const string ScenePath = "Assets/Scenes/Hub.unity";
        private const string PackageName = "com.gwrhub.app";
        private const string ApkPath = "Builds/GWRHub.apk";

        [MenuItem("GWR Hub/1. Set up project")]
        public static void SetupProject()
        {
            CreateSceneIfMissing();
            ConfigurePlayerSettings();
            AssetDatabase.SaveAssets();
            Debug.Log("[GWR Hub] Project set up. Now run 'GWR Hub/2. Build APK'.");
            EditorUtility.DisplayDialog("GWR Hub",
                "Project set up.\n\nScene: " + ScenePath + "\nPackage: " + PackageName +
                "\n\nNext: GWR Hub > 2. Build APK", "OK");
        }

        [MenuItem("GWR Hub/2. Build APK")]
        public static void BuildApk()
        {
            SetupProjectSilently();

            Directory.CreateDirectory(Path.GetDirectoryName(ApkPath));
            EditorUserBuildSettings.buildAppBundle = false;

            var options = new BuildPlayerOptions
            {
                scenes = new[] { ScenePath },
                locationPathName = ApkPath,
                target = BuildTarget.Android,
                targetGroup = BuildTargetGroup.Android,
                options = BuildOptions.None
            };

            BuildReport report = BuildPipeline.BuildPlayer(options);
            if (report.summary.result == BuildResult.Succeeded)
            {
                Debug.Log("[GWR Hub] APK built: " + Path.GetFullPath(ApkPath));
                EditorUtility.RevealInFinder(ApkPath);
            }
            else
            {
                Debug.LogError("[GWR Hub] Build failed: " + report.summary.result);
            }
        }

        internal static void SetupProjectSilently()
        {
            EnsureUniversalRenderPipeline();
            CreateSceneIfMissing();
            ConfigurePlayerSettings();
            AssetDatabase.SaveAssets();
        }

        private const string UrpAssetPath = "Assets/Settings/URP/GWRHub_URP.asset";
        private const string UrpRendererPath = "Assets/Settings/URP/GWRHub_URP_Renderer.asset";

        // Die integrierte Render-Pipeline ist in Unity 6 veraltet -> URP verwenden.
        private static void EnsureUniversalRenderPipeline()
        {
            var asset = AssetDatabase.LoadAssetAtPath<UniversalRenderPipelineAsset>(UrpAssetPath);
            if (asset == null)
            {
                Directory.CreateDirectory(Path.GetDirectoryName(UrpAssetPath));

                var renderer = AssetDatabase.LoadAssetAtPath<UniversalRendererData>(UrpRendererPath);
                if (renderer == null)
                {
                    renderer = ScriptableObject.CreateInstance<UniversalRendererData>();
                    AssetDatabase.CreateAsset(renderer, UrpRendererPath);
                }

                asset = UniversalRenderPipelineAsset.Create(renderer);
                AssetDatabase.CreateAsset(asset, UrpAssetPath);
                AssetDatabase.SaveAssets();
            }

            if (GraphicsSettings.defaultRenderPipeline != asset)
                GraphicsSettings.defaultRenderPipeline = asset;

            // Qualitätsstufen ohne eigene Pipeline erben die Standard-Pipeline;
            // eventuelle Built-in-Overrides gibt es hier nicht (alle sind leer).
        }

        private static void CreateSceneIfMissing()
        {
            if (!File.Exists(ScenePath))
            {
                Directory.CreateDirectory(Path.GetDirectoryName(ScenePath));
                var scene = EditorSceneManager.NewScene(NewSceneSetup.DefaultGameObjects, NewSceneMode.Single);

                var cam = Camera.main;
                if (cam != null)
                {
                    cam.clearFlags = CameraClearFlags.SolidColor;
                    cam.backgroundColor = new Color32(0x1C, 0x1A, 0x17, 0xFF);
                }

                new GameObject("HubApp").AddComponent<HubApp>();
                EditorSceneManager.SaveScene(scene, ScenePath);
                AssetDatabase.Refresh();
            }

            var scenes = EditorBuildSettings.scenes;
            if (scenes.Length != 1 || scenes[0].path != ScenePath || !scenes[0].enabled)
                EditorBuildSettings.scenes = new[] { new EditorBuildSettingsScene(ScenePath, true) };
        }

        private static void ConfigurePlayerSettings()
        {
            PlayerSettings.companyName = "GWR Hub";
            PlayerSettings.productName = "GWR Hub";
            PlayerSettings.SetApplicationIdentifier(NamedBuildTarget.Android, PackageName);
            PlayerSettings.bundleVersion = "1.0.0";
            PlayerSettings.Android.bundleVersionCode = 1;

            PlayerSettings.SetScriptingBackend(NamedBuildTarget.Android, ScriptingImplementation.IL2CPP);
            PlayerSettings.Android.targetArchitectures = AndroidArchitecture.ARM64;
            PlayerSettings.Android.minSdkVersion = AndroidSdkVersions.AndroidApiLevel26;
            PlayerSettings.Android.targetSdkVersion = AndroidSdkVersions.AndroidApiLevelAuto;

#if UNITY_2023_1_OR_NEWER
            // Unser AndroidManifest.xml verwendet UnityPlayerActivity (nicht GameActivity).
            // Ist GameActivity aktiv (Unity-6-Standard), setzt Unity die Activity auf
            // android:enabled="false" -> kein App-Symbol auf dem Handy.
            PlayerSettings.Android.applicationEntry = AndroidApplicationEntry.Activity;
#endif

            PlayerSettings.defaultInterfaceOrientation = UIOrientation.AutoRotation;
            PlayerSettings.allowedAutorotateToPortrait = true;
            PlayerSettings.allowedAutorotateToPortraitUpsideDown = false;
            PlayerSettings.allowedAutorotateToLandscapeLeft = true;
            PlayerSettings.allowedAutorotateToLandscapeRight = true;
            PlayerSettings.runInBackground = false;
        }
    }

    /// <summary>
    /// Vor jedem Android-Build (auch über Build Profiles / Build And Run) prüfen,
    /// dass die Launcher-Activity aktiv ist.
    /// </summary>
    public class GWRHubPreBuild : IPreprocessBuildWithReport
    {
        public int callbackOrder => -1000;

        public void OnPreprocessBuild(BuildReport report)
        {
            if (report.summary.platform != BuildTarget.Android) return;
            GWRHubSetup.SetupProjectSilently();
#if UNITY_2023_1_OR_NEWER
            if (PlayerSettings.Android.applicationEntry != AndroidApplicationEntry.Activity)
                throw new BuildFailedException(
                    "[GWR Hub] Application Entry Point must be 'Activity', otherwise the app icon is missing.");
#endif
        }
    }
}
