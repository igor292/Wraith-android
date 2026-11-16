using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Text;

namespace Wraith.Windows
{
    internal static class ImportParser
    {
        internal static List<Profile> Parse(string raw)
        {
            string input = (raw ?? "").Trim().TrimStart('\uFEFF');
            if (input.Length == 0) throw new InvalidOperationException("Вставьте ссылку, JSON или HTTPS-ссылку подписки.");
            if (input.StartsWith("{")) return ParseObject(JsonUtil.Parse(input), input);
            if (input.StartsWith("["))
            {
                var result = new List<Profile>();
                foreach (var item in JsonUtil.Arr(JsonUtil.Serializer.DeserializeObject(input))) result.AddRange(ParseObject(JsonUtil.Obj(item), JsonUtil.Print(item)));
                if (result.Count == 0) throw new InvalidOperationException("В JSON не найден поддерживаемый сервер.");
                return result;
            }
            var profiles = new List<Profile>();
            foreach (string line in input.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries))
            {
                string value = line.Trim(); if (!value.Contains("://") || value.StartsWith("#")) continue;
                profiles.Add(ParseLink(value));
            }
            if (profiles.Count == 0)
            {
                string decoded;
                try { decoded = Encoding.UTF8.GetString(Convert.FromBase64String(PadBase64(input))); }
                catch { throw new InvalidOperationException("Не удалось распознать ссылку, JSON или Base64-подписку."); }
                if (decoded.TrimStart().StartsWith("{") || decoded.TrimStart().StartsWith("[")) return Parse(decoded);
                return Parse(decoded);
            }
            return profiles;
        }

        private static string PadBase64(string value)
        {
            value = new string(value.Where(c => !char.IsWhiteSpace(c)).ToArray()).Replace('-', '+').Replace('_', '/');
            value = value.TrimEnd('='); while (value.Length % 4 != 0) value += "="; return value;
        }

        private static List<Profile> ParseObject(Dictionary<string, object> root, string raw)
        {
            if (root.ContainsKey("outbounds"))
            {
                List<object> outbounds = JsonUtil.Arr(root["outbounds"]);
                bool xray = outbounds.Any(o => JsonUtil.Obj(o).ContainsKey("protocol") || JsonUtil.Obj(o).ContainsKey("streamSettings"));
                if (!xray)
                {
                    return new List<Profile> { new Profile { Name = JsonUtil.Str(root, "name", JsonUtil.Str(root, "remarks", "JSON-конфигурация")), Full = true, Source = raw, JsonSource = raw, Outbound = root } };
                }
                var proxies = outbounds.Select(JsonUtil.Obj).Where(o => !new[] { "freedom", "blackhole", "dns", "loopback" }.Contains(JsonUtil.Str(o, "protocol"))).ToList();
                if (proxies.Count == 0) throw new InvalidOperationException("В Xray JSON нет прокси-исходящего.");
                var converted = proxies.Select(ConvertXrayOutbound).ToList();
                string name = JsonUtil.Str(root, "remarks", "name"); if (name.Length == 0) name = "Xray";
                var profile = new Profile { Name = name, Source = raw, JsonSource = raw, Outbound = converted[0], Candidates = converted };
                var firstTls = JsonUtil.Obj(converted[0].ContainsKey("tls") ? converted[0]["tls"] : null);
                profile.TlsPinUnsupported = JsonUtil.Str(firstTls, "xray_pin_unsupported"); firstTls.Remove("xray_pin_unsupported");
                if (root.ContainsKey("routing")) profile.Routing = JsonUtil.Obj(root["routing"]);
                if (root.ContainsKey("dns")) profile.Dns = JsonUtil.Obj(root["dns"]);
                return new List<Profile> { profile };
            }
            if (root.ContainsKey("type")) return new List<Profile> { new Profile { Name = JsonUtil.Str(root, "tag", JsonUtil.Str(root, "name", "Сервер")), Outbound = root, Source = raw, JsonSource = raw } };
            throw new InvalidOperationException("Ожидается sing-box JSON, Xray JSON или ссылка.");
        }

        private static Dictionary<string, object> ConvertXrayOutbound(Dictionary<string, object> x)
        {
            string protocol = JsonUtil.Str(x, "protocol").ToLowerInvariant();
            var s = JsonUtil.Obj(x.ContainsKey("settings") ? x["settings"] : null);
            Dictionary<string, object> peer = null, user = null;
            if (protocol == "vless" || protocol == "vmess")
            {
                var vn = JsonUtil.Arr(s.ContainsKey("vnext") ? s["vnext"] : null); if (vn.Count > 0) peer = JsonUtil.Obj(vn[0]);
                var users = peer == null ? new List<object>() : JsonUtil.Arr(peer.ContainsKey("users") ? peer["users"] : null); if (users.Count > 0) user = JsonUtil.Obj(users[0]);
            }
            else if (protocol == "trojan" || protocol == "shadowsocks")
            {
                var servers = JsonUtil.Arr(s.ContainsKey("servers") ? s["servers"] : null); if (servers.Count > 0) peer = JsonUtil.Obj(servers[0]);
            }
            if (peer == null) throw new InvalidOperationException("Не удалось прочитать адрес исходящего Xray " + protocol + ".");
            var o = new Dictionary<string, object> { { "type", protocol }, { "server", JsonUtil.Str(peer, "address") }, { "server_port", JsonUtil.Int(peer, "port") } };
            if (user != null)
            {
                if (protocol == "vless") { o["uuid"] = JsonUtil.Str(user, "id"); string flow = JsonUtil.Str(user, "flow"); if (flow.Length > 0) o["flow"] = flow; }
                else if (protocol == "vmess") { o["uuid"] = JsonUtil.Str(user, "id"); o["security"] = JsonUtil.Str(user, "security", "auto"); o["alter_id"] = JsonUtil.Int(user, "alterId"); }
            }
            else if (protocol == "trojan") o["password"] = JsonUtil.Str(peer, "password");
            else if (protocol == "shadowsocks") { o["method"] = JsonUtil.Str(peer, "method"); o["password"] = JsonUtil.Str(peer, "password"); }
            var stream = JsonUtil.Obj(x.ContainsKey("streamSettings") ? x["streamSettings"] : null);
            string security = JsonUtil.Str(stream, "security"); var tlsSettings = JsonUtil.Obj(stream.ContainsKey("tlsSettings") ? stream["tlsSettings"] : null);
            if (security == "tls")
            {
                string sni = JsonUtil.Str(tlsSettings, "serverName", JsonUtil.Str(peer, "address"));
                var tls = new Dictionary<string, object> { { "enabled", true }, { "server_name", sni } };
                string fp = JsonUtil.Str(tlsSettings, "fingerprint"); if (fp.Length > 0) tls["utls"] = new Dictionary<string, object> { { "enabled", true }, { "fingerprint", Fingerprint(fp) } };
                var alpn = JsonUtil.Arr(tlsSettings.ContainsKey("alpn") ? tlsSettings["alpn"] : null); if (alpn.Count > 0) tls["alpn"] = alpn;
                if (tlsSettings.ContainsKey("pinnedPeerCertSha256")) tls["xray_pin_unsupported"] = tlsSettings["pinnedPeerCertSha256"];
                o["tls"] = tls;
            }
            else if (security == "reality")
            {
                var reality = JsonUtil.Obj(stream.ContainsKey("realitySettings") ? stream["realitySettings"] : null);
                o["tls"] = new Dictionary<string, object> {
                    {"enabled",true},{"server_name",JsonUtil.Str(reality,"serverName",JsonUtil.Str(peer,"address"))},
                    {"utls",new Dictionary<string,object>{{"enabled",true},{"fingerprint",Fingerprint(JsonUtil.Str(reality,"fingerprint","chrome"))}}},
                    {"reality",new Dictionary<string,object>{{"enabled",true},{"public_key",JsonUtil.Str(reality,"publicKey")},{"short_id",JsonUtil.Str(reality,"shortId")}}}
                };
            }
            string network = JsonUtil.Str(stream, "network", "tcp");
            if (network == "grpc") { var grpc = JsonUtil.Obj(stream.ContainsKey("grpcSettings") ? stream["grpcSettings"] : null); o["transport"] = new Dictionary<string, object> { { "type", "grpc" }, { "service_name", JsonUtil.Str(grpc, "serviceName") } }; }
            else if (network == "ws") { var ws = JsonUtil.Obj(stream.ContainsKey("wsSettings") ? stream["wsSettings"] : null); o["transport"] = new Dictionary<string, object> { { "type", "ws" }, { "path", JsonUtil.Str(ws, "path", "/") } }; }
            else if (network != "tcp" && network != "raw") throw new InvalidOperationException("Транспорт Xray " + network + " пока не поддерживается.");
            return o;
        }

        private static string Fingerprint(string value)
        {
            string v = value.ToLowerInvariant(); return new[] { "chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized" }.Contains(v) ? v : "chrome";
        }

        private static Profile ParseLink(string line)
        {
            int split = line.IndexOf("://"); string scheme = line.Substring(0, split).ToLowerInvariant();
            string fragment = ""; int hash = line.IndexOf('#'); if (hash >= 0) { fragment = Uri.UnescapeDataString(line.Substring(hash + 1)); line = line.Substring(0, hash); }
            string auth = line.Substring(split + 3); int query = auth.IndexOf('?'); string q = query >= 0 ? auth.Substring(query + 1) : ""; if (query >= 0) auth = auth.Substring(0, query);
            string id, hostPort; int at = auth.LastIndexOf('@'); if (at >= 0) { id = Uri.UnescapeDataString(auth.Substring(0, at)); hostPort = auth.Substring(at + 1); } else { id = ""; hostPort = auth; }
            string host; int port = 443; if (hostPort.StartsWith("[")) { int close = hostPort.IndexOf(']'); host = hostPort.Substring(1, close - 1); if (hostPort.Length > close + 2) int.TryParse(hostPort.Substring(close + 2), out port); }
            else { int colon = hostPort.LastIndexOf(':'); host = colon < 0 ? hostPort : hostPort.Substring(0, colon); if (colon >= 0) int.TryParse(hostPort.Substring(colon + 1), out port); }
            var queryMap = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            foreach (string item in q.Split('&')) { var pair = item.Split(new[] { '=' }, 2); if (pair.Length == 2) queryMap[Uri.UnescapeDataString(pair[0])] = Uri.UnescapeDataString(pair[1]); }
            string transport = queryMap.ContainsKey("type") ? queryMap["type"] : "tcp", security = queryMap.ContainsKey("security") ? queryMap["security"] : "none";
            var o = new Dictionary<string, object> { { "type", scheme == "hy2" ? "hysteria2" : scheme }, { "server", host }, { "server_port", port } };
            if (scheme == "vless") { o["uuid"] = id; if (queryMap.ContainsKey("flow")) o["flow"] = queryMap["flow"]; }
            else if (scheme == "trojan") o["password"] = id;
            else if (scheme == "vmess") { string vm = Encoding.UTF8.GetString(Convert.FromBase64String(PadBase64(id))); var obj = JsonUtil.Parse(vm); o["type"] = "vmess"; o["uuid"] = JsonUtil.Str(obj, "id"); o["security"] = JsonUtil.Str(obj, "scy", "auto"); o["server_port"] = Int32.Parse(JsonUtil.Str(obj, "port", "443")); host = JsonUtil.Str(obj, "add", host); o["server"] = host; if (JsonUtil.Str(obj, "net") == "ws") o["transport"] = new Dictionary<string, object> { { "type", "ws" }, { "path", JsonUtil.Str(JsonUtil.Obj(obj.ContainsKey("ws-opts") ? obj["ws-opts"] : null), "path", "/") } }; }
            else if (scheme != "hysteria2" && scheme != "shadowsocks") throw new InvalidOperationException("Ссылка " + scheme + " пока не поддерживается.");
            if (security == "tls" || security == "reality")
            {
                string sni = queryMap.ContainsKey("sni") ? queryMap["sni"] : host;
                var tls = new Dictionary<string, object> { { "enabled", true }, { "server_name", sni } };
                if (queryMap.ContainsKey("fp")) tls["utls"] = new Dictionary<string, object> { { "enabled", true }, { "fingerprint", Fingerprint(queryMap["fp"]) } };
                if (security == "reality") tls["reality"] = new Dictionary<string, object> { { "enabled", true }, { "public_key", queryMap.ContainsKey("pbk") ? queryMap["pbk"] : "" }, { "short_id", queryMap.ContainsKey("sid") ? queryMap["sid"] : "" } };
                o["tls"] = tls;
            }
            if (scheme == "vless" && (transport == "grpc" || transport == "ws")) o["transport"] = new Dictionary<string, object> { { "type", transport }, { transport == "grpc" ? "service_name" : "path", queryMap.ContainsKey("serviceName") ? queryMap["serviceName"] : queryMap.ContainsKey("path") ? queryMap["path"] : "" } };
            return new Profile { Name = fragment.Length > 0 ? fragment : host, Source = line, JsonSource = line, Outbound = o };
        }

        internal static List<Profile> Fetch(Subscription sub, string userAgent)
        {
            var request = (HttpWebRequest)WebRequest.Create(sub.Url); request.Method = "GET"; request.UserAgent = string.IsNullOrWhiteSpace(userAgent) ? "Happ/4.4.1/Android/190166673024350285" : userAgent; request.Timeout = 20000; request.ReadWriteTimeout = 20000;
            using (var response = (HttpWebResponse)request.GetResponse()) using (var reader = new System.IO.StreamReader(response.GetResponseStream(), Encoding.UTF8)) return Parse(reader.ReadToEnd());
        }
    }
}
