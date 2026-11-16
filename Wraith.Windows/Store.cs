using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;

namespace Wraith.Windows
{
    internal sealed class Store
    {
        private static readonly string Folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Wraith");
        private static readonly string FileName = Path.Combine(Folder, "wraith.dat");
        internal AppState State { get; private set; }
        internal string DataDirectory { get { return Folder; } }
        internal Store() { Directory.CreateDirectory(Folder); State = Load(); }
        private AppState Load()
        {
            try
            {
                byte[] protectedBytes = File.ReadAllBytes(FileName);
                string json = Encoding.UTF8.GetString(ProtectedData.Unprotect(protectedBytes, Encoding.UTF8.GetBytes("Wraith Windows profile v1"), DataProtectionScope.CurrentUser));
                AppState state = JsonUtil.Serializer.Deserialize<AppState>(json);
                if (state.Settings == null) state.Settings = AppState.Defaults();
                foreach (var pair in AppState.Defaults()) if (!state.Settings.ContainsKey(pair.Key)) state.Settings[pair.Key] = pair.Value;
                if (state.Profiles == null) state.Profiles = new System.Collections.Generic.List<Profile>();
                if (state.Subscriptions == null) state.Subscriptions = new System.Collections.Generic.List<Subscription>();
                return state;
            }
            catch (FileNotFoundException) { return new AppState(); }
            catch (DirectoryNotFoundException) { return new AppState(); }
            catch
            {
                // An unreadable file (another Windows account, damaged data) used to be replaced by
                // an empty state on the next Save(), silently destroying every profile. Keep a copy.
                try { if (File.Exists(FileName)) File.Copy(FileName, FileName + ".broken-" + DateTime.Now.ToString("yyyyMMdd-HHmmss"), false); } catch { }
                return new AppState();
            }
        }
        internal void Save()
        {
            string json = JsonUtil.Serializer.Serialize(State);
            byte[] encrypted = ProtectedData.Protect(Encoding.UTF8.GetBytes(json), Encoding.UTF8.GetBytes("Wraith Windows profile v1"), DataProtectionScope.CurrentUser);
            string temp = FileName + ".new";
            File.WriteAllBytes(temp, encrypted);
            if (File.Exists(FileName)) File.Replace(temp, FileName, null); else File.Move(temp, FileName);
        }
        internal object Get(string key) { object value; return State.Settings.TryGetValue(key, out value) ? value : null; }
        internal string GetString(string key, string fallback = "") { object value = Get(key); return value == null ? fallback : Convert.ToString(value); }
        internal bool GetBool(string key, bool fallback = false) { object value = Get(key); return value == null ? fallback : Convert.ToBoolean(value); }
        internal int GetInt(string key, int fallback = 0) { object value = Get(key); return value == null ? fallback : Convert.ToInt32(value); }
        internal void Set(string key, object value) { State.Settings[key] = value; Save(); }
        internal Profile Selected { get { return State.Profiles.Find(p => p.Id == State.Selected); } }
    }
}
