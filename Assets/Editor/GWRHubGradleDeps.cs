#if UNITY_ANDROID
using System.IO;
using System.Text;
using System.Text.RegularExpressions;
using UnityEditor.Android;
using UnityEngine;

namespace GWRHub.EditorTools
{
    /// <summary>
    /// Fügt dem generierten Gradle-Projekt (unityLibrary) die Bibliotheken des Overlays hinzu:
    ///  - Shizuku: Tasten-Shortcuts ohne Root ans Spiel senden (KeySender.java)
    /// Absichtlich ein Build-Hook statt eines eigenen mainTemplate.gradle, damit es mit
    /// jeder Unity-Version funktioniert. Gradle lädt die Bibliotheken von Maven Central / Google.
    /// </summary>
    public class GWRHubGradleDeps : IPostGenerateGradleAndroidProject
    {
        private const string ShizukuVersion = "13.1.5";

        // Gruppe:Artefakt -> vollständige Koordinate
        private static readonly string[][] Dependencies =
        {
            new[] { "dev.rikka.shizuku:api", "dev.rikka.shizuku:api:" + ShizukuVersion },
            new[] { "dev.rikka.shizuku:provider", "dev.rikka.shizuku:provider:" + ShizukuVersion },
        };

        public int callbackOrder => 0;

        public void OnPostGenerateGradleAndroidProject(string path)
        {
            string gradle = Path.Combine(path, "build.gradle");
            if (!File.Exists(gradle))
            {
                Debug.LogWarning("[GWR Hub] build.gradle not found in " + path + " – overlay libraries missing");
                return;
            }

            string text = File.ReadAllText(gradle);
            var deps = new StringBuilder();
            foreach (var dep in Dependencies)
            {
                if (!text.Contains(dep[0])) deps.Append("    implementation '").Append(dep[1]).Append("'\n");
            }
            if (deps.Length == 0) return;

            // Erste Top-Level-"dependencies {"-Zeile (unityLibrary hat keinen buildscript-Block)
            var match = Regex.Match(text, @"^dependencies\s*\{[^\n]*\n", RegexOptions.Multiline);
            if (!match.Success)
            {
                Debug.LogError("[GWR Hub] No dependencies block in " + gradle + " – overlay libraries missing");
                return;
            }

            text = text.Insert(match.Index + match.Length, deps.ToString());
            File.WriteAllText(gradle, text);
        }
    }
}
#endif
