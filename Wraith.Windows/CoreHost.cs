using Microsoft.Win32;
using System;
using System.Diagnostics;
using System.IO;
using System.Net.Sockets;
using System.Net;
using System.Net.NetworkInformation;
using System.Text;
using System.Threading;

namespace Wraith.Windows
{
    internal sealed class CoreHost
    {
        private readonly Store store;
        private Process process;
        private string configPath;
        private readonly string logPath;
        private bool wasProxyEnabled;
        private string oldProxyServer;
        private string oldProxyOverride;
        private bool hasProxySnapshot;
        private bool proxyModeForSession;
        internal bool Running { get { return process != null && !process.HasExited; } }
        /** The core was started by us and has died on its own (crash, killed, config error at runtime). */
        internal bool ExitedUnexpectedly { get { try { return process != null && process.HasExited; } catch { return false; } } }
        internal string LogPath { get { return logPath; } }
        internal event Action<string> Output;
        internal CoreHost(Store owner) { store = owner; logPath = Path.Combine(store.DataDirectory, "core.log"); }

        internal void Start(Profile profile)
        {
            string exe = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "sing-box.exe");
            if (!File.Exists(exe)) throw new FileNotFoundException("Не найден sing-box.exe рядом с приложением.", exe);
            string secret = Guid.NewGuid().ToString("N").Substring(0, 20);
            configPath = Path.Combine(store.DataDirectory, "active-config.json");
            var config = TunnelConfigBuilder.Build(store.State, profile, logPath, secret);
            File.WriteAllText(configPath, JsonUtil.Serializer.Serialize(config), new UTF8Encoding(false));
            var check = new ProcessStartInfo(exe, "check -c \"" + configPath + "\"") { WorkingDirectory = AppDomain.CurrentDomain.BaseDirectory, UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
            using (var validator = Process.Start(check))
            {
                // Read both pipes concurrently: reading stdout to the end first can deadlock once
                // sing-box fills the stderr pipe with a long validation error.
                var errors = validator.StandardError.ReadToEndAsync(); string output = validator.StandardOutput.ReadToEnd() + errors.Result; validator.WaitForExit(15000);
                if (validator.ExitCode != 0) throw new InvalidOperationException("sing-box отклонил конфигурацию: " + output.Trim());
            }
            proxyModeForSession = store.State.TunnelMode == "System Proxy";
            if (proxyModeForSession) SetSystemProxy(true);
            if (store.State.TunnelMode == "TUN" && !IsAdministrator()) throw new UnauthorizedAccessException("Для TUN перезапустите Wraith через подтверждение администратора.");
            var start = new ProcessStartInfo(exe, "run -c \"" + configPath + "\"") { WorkingDirectory = AppDomain.CurrentDomain.BaseDirectory, UseShellExecute = false, CreateNoWindow = true, WindowStyle = ProcessWindowStyle.Hidden, RedirectStandardOutput = true, RedirectStandardError = true };
            try
            {
                process = Process.Start(start);
                if (process == null) throw new InvalidOperationException("Не удалось запустить ядро sing-box.");
                process.OutputDataReceived += (s, e) => { if (!String.IsNullOrEmpty(e.Data)) Append(e.Data); }; process.ErrorDataReceived += (s, e) => { if (!String.IsNullOrEmpty(e.Data)) Append(e.Data); }; process.BeginOutputReadLine(); process.BeginErrorReadLine();
                Thread.Sleep(700);
                if (process.HasExited) throw new InvalidOperationException("sing-box остановился сразу после запуска. Проверьте журнал.");
                Append("Wraith: подключено к «" + profile.Name + "»");
            }
            catch
            {
                if (proxyModeForSession) SetSystemProxy(false); proxyModeForSession = false;
                // Forget the dead process, otherwise the UI later reports a second "core stopped".
                if (process != null) { try { if (!process.HasExited) process.Kill(); } catch { } process.Dispose(); process = null; }
                throw;
            }
        }

        internal void Stop()
        {
            if (proxyModeForSession) { SetSystemProxy(false); proxyModeForSession = false; }
            if (process != null)
            {
                // sing-box runs without a window, so CloseMainWindow() never did anything and every
                // disconnect waited the full 2.5 s before the kill.
                try { if (!process.HasExited) { process.Kill(); process.WaitForExit(3000); } }
                catch { try { process.Kill(); } catch { } }
                process.Dispose(); process = null;
            }
            Append("Wraith: соединение остановлено");
        }

        internal int Ping(Profile profile, int timeoutMs, string pingType, string pingUrl)
        {
            var outbound = profile.Outbound; string host = JsonUtil.Str(outbound, "server"); int port = JsonUtil.Int(outbound, "server_port", 443);
            if (host.Length == 0) throw new InvalidOperationException("В конфигурации не указан адрес сервера.");
            var sw = Stopwatch.StartNew();
            if (pingType == "icmp") { var reply = new Ping().Send(host, timeoutMs); if (reply == null || reply.Status != IPStatus.Success) throw new InvalidOperationException("ICMP: " + (reply == null ? "нет ответа" : reply.Status.ToString())); return Math.Max(1, (int)sw.ElapsedMilliseconds); }
            if (pingType == "tcp") { using (var tcp = new TcpClient()) { var task = tcp.ConnectAsync(host, port); if (!task.Wait(timeoutMs)) throw new TimeoutException("Тайм-аут подключения к порту."); task.GetAwaiter().GetResult(); } sw.Stop(); return Math.Max(1, (int)sw.ElapsedMilliseconds); }
            if (String.IsNullOrWhiteSpace(pingUrl)) throw new InvalidOperationException("Укажите URL для proxy-пинга.");
            int localPort; var listener = new System.Net.Sockets.TcpListener(IPAddress.Loopback, 0); listener.Start(); localPort = ((IPEndPoint)listener.LocalEndpoint).Port; listener.Stop();
            string exe = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "sing-box.exe"); string path = Path.Combine(store.DataDirectory, "probe-" + Guid.NewGuid().ToString("N") + ".json");
            File.WriteAllText(path, JsonUtil.Print(TunnelConfigBuilder.BuildProbe(store.State, profile, logPath, Guid.NewGuid().ToString("N"), localPort)), new UTF8Encoding(false));
            Process probe = null;
            try
            {
                var start = new ProcessStartInfo(exe, "run -c \"" + path + "\"") { WorkingDirectory = AppDomain.CurrentDomain.BaseDirectory, UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
                probe = Process.Start(start); probe.OutputDataReceived += (s, e) => { if (!String.IsNullOrEmpty(e.Data)) Append("ping: " + e.Data); }; probe.ErrorDataReceived += (s, e) => { if (!String.IsNullOrEmpty(e.Data)) Append("ping: " + e.Data); }; probe.BeginOutputReadLine(); probe.BeginErrorReadLine();
                var deadline = DateTime.UtcNow.AddMilliseconds(timeoutMs); bool ready = false;
                while (DateTime.UtcNow < deadline && !probe.HasExited)
                {
                    try { using (var tcp = new TcpClient()) { var connect = tcp.ConnectAsync("127.0.0.1", localPort); if (connect.Wait(150) && tcp.Connected) { ready = true; break; } } } catch { }
                    Thread.Sleep(50);
                }
                if (!ready) throw new TimeoutException("sing-box не поднял локальный proxy-вход для проверки.");
                var request = (HttpWebRequest)WebRequest.Create(pingUrl); request.Proxy = new WebProxy("http://127.0.0.1:" + localPort); request.Method = pingType == "proxyHead" ? "HEAD" : "GET"; request.Timeout = timeoutMs; request.ReadWriteTimeout = timeoutMs; request.KeepAlive = false; request.UserAgent = "Wraith/Windows";
                using (var response = (HttpWebResponse)request.GetResponse()) { if ((int)response.StatusCode < 200 || (int)response.StatusCode >= 400) throw new InvalidOperationException("Proxy-пинг вернул HTTP " + (int)response.StatusCode + "."); }
            }
            finally
            {
                if (probe != null) { try { if (!probe.HasExited) { probe.Kill(); probe.WaitForExit(2000); } } catch { } probe.Dispose(); }
                try { if (File.Exists(path)) File.Delete(path); } catch { }
            }
            sw.Stop(); return Math.Max(1, (int)sw.ElapsedMilliseconds);
        }

        private void Append(string text)
        {
            // sing-box keeps core.log open for writing; File.AppendAllText asks for exclusive write
            // access and silently failed for every line while connected.
            try
            {
                byte[] line = Encoding.UTF8.GetBytes(DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss ") + text + Environment.NewLine);
                using (var stream = new FileStream(logPath, FileMode.Append, FileAccess.Write, FileShare.ReadWrite | FileShare.Delete)) stream.Write(line, 0, line.Length);
            }
            catch { }
            var handler = Output; if (handler != null) handler(text);
        }
        private void SetSystemProxy(bool enabled)
        {
            const string path = @"Software\Microsoft\Windows\CurrentVersion\Internet Settings";
            using (var key = Registry.CurrentUser.OpenSubKey(path, true))
            {
                if (key == null) throw new InvalidOperationException("Не удалось открыть системные настройки прокси Windows.");
                if (enabled)
                {
                    if (!hasProxySnapshot) { wasProxyEnabled = Convert.ToInt32(key.GetValue("ProxyEnable", 0)) != 0; oldProxyServer = key.GetValue("ProxyServer") == null ? null : Convert.ToString(key.GetValue("ProxyServer")); oldProxyOverride = key.GetValue("ProxyOverride") == null ? null : Convert.ToString(key.GetValue("ProxyOverride")); hasProxySnapshot = true; }
                    key.SetValue("ProxyEnable", 1, RegistryValueKind.DWord); key.SetValue("ProxyServer", "127.0.0.1:" + store.GetInt("proxyPort", 2081), RegistryValueKind.String); key.SetValue("ProxyOverride", "localhost;127.*;<local>", RegistryValueKind.String);
                }
                else if (hasProxySnapshot)
                {
                    key.SetValue("ProxyEnable", wasProxyEnabled ? 1 : 0, RegistryValueKind.DWord); if (oldProxyServer == null) key.DeleteValue("ProxyServer", false); else key.SetValue("ProxyServer", oldProxyServer, RegistryValueKind.String); if (oldProxyOverride == null) key.DeleteValue("ProxyOverride", false); else key.SetValue("ProxyOverride", oldProxyOverride, RegistryValueKind.String); hasProxySnapshot = false;
                }
            }
            InternetSetOption(IntPtr.Zero, 39, IntPtr.Zero, 0); InternetSetOption(IntPtr.Zero, 37, IntPtr.Zero, 0);
        }
        private static bool IsAdministrator() { var id = System.Security.Principal.WindowsIdentity.GetCurrent(); return new System.Security.Principal.WindowsPrincipal(id).IsInRole(System.Security.Principal.WindowsBuiltInRole.Administrator); }
        [System.Runtime.InteropServices.DllImport("wininet.dll", SetLastError = true)] private static extern bool InternetSetOption(IntPtr hInternet, int option, IntPtr buffer, int bufferLength);
    }
}
