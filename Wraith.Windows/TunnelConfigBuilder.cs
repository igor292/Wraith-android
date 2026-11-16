using System;
using System.Collections.Generic;
using System.Linq;

namespace Wraith.Windows
{
    internal static class TunnelConfigBuilder
    {
        internal static Dictionary<string, object> Build(AppState state, Profile profile, string logPath, string controllerSecret)
        {
            if (profile == null) throw new InvalidOperationException("Выберите сервер.");
            if (!String.IsNullOrWhiteSpace(profile.TlsPinUnsupported)) throw new InvalidOperationException("В узле задан pinnedPeerCertSha256. Текущая сборка sing-box не умеет применить этот Xray pin, поэтому соединение остановлено вместо незаметного отключения проверки сертификата.");
            var config = profile.Full ? JsonUtil.Copy(profile.Outbound) : null;
            if (config != null)
            {
                NormalizeLegacyDns(config);
                config["inbounds"] = Inbounds(state);
                var route = JsonUtil.Obj(config.ContainsKey("route") ? config["route"] : null);
                route["auto_detect_interface"] = true;
                if (!route.ContainsKey("final")) route["final"] = "proxy";
                config["route"] = route;
            }
            else
            {
                var outbounds = new List<object>(); var candidates = profile.Candidates.Count > 0 ? profile.Candidates : new List<Dictionary<string, object>> { profile.Outbound };
                var candidateTags = new List<string>();
                for (int i = 0; i < candidates.Count; i++) { string tag = candidates.Count == 1 ? "proxy" : "proxy-node-" + i; candidateTags.Add(tag); outbounds.Add(ConfigureOutbound(candidates[i], tag, state)); }
                if (candidates.Count > 1) outbounds.Add(new Dictionary<string, object> { { "type", "selector" }, { "tag", "proxy" }, { "outbounds", candidateTags }, { "default", candidateTags[0] }, { "interrupt_exist_connections", true } });
                outbounds.Add(new Dictionary<string, object> { { "type", "direct" }, { "tag", "direct" } }); outbounds.Add(new Dictionary<string, object> { { "type", "block" }, { "tag", "block" } });
                var rules = new List<object>();
                var ruleSets = new List<object>();
                if (GetBool(state, "sniff", true)) rules.Add(new Dictionary<string, object> { { "action", "sniff" } });
                rules.Add(new Dictionary<string, object> { { "protocol", new[] { "dns" } }, { "action", "hijack-dns" } });
                if (GetBool(state, "bypassLan", true)) rules.Add(new Dictionary<string, object> { { "ip_is_private", true }, { "outbound", "direct" } });
                AddXrayRules(profile.Routing, rules, ruleSets);
                if (GetString(state, "quic", "allow") == "reject") rules.Add(new Dictionary<string, object> { { "network", new[] { "udp" } }, { "port", new[] { 443 } }, { "action", "reject" } });
                var routeObj = new Dictionary<string, object> { { "rules", rules }, { "rule_set", ruleSets }, { "final", GetString(state, "mode", "rules") == "direct" ? "direct" : "proxy" }, { "auto_detect_interface", true }, { "default_domain_resolver", "bootstrap" } };
                var configObj = new Dictionary<string, object> {
                    { "log", new Dictionary<string,object>{{"level","info"},{"timestamp",true},{"output",logPath}} },
                    { "dns", BuildDns(profile, state) }, { "inbounds", Inbounds(state) }, { "outbounds", outbounds }, { "route", routeObj },
                    { "experimental", new Dictionary<string,object>{{"clash_api",new Dictionary<string,object>{{"external_controller","127.0.0.1:9097"},{"secret",controllerSecret}}}} }
                };
                config = configObj;
            }
            return config;
        }

        // sing-box 1.14 removed the old { address: ... } DNS server schema and
        // the top-level dns.fakeip object. Keep the subscription JSON untouched
        // for display/editing and migrate only the runtime copy here.
        private static void NormalizeLegacyDns(Dictionary<string, object> config)
        {
            if (!config.ContainsKey("dns")) return;
            var dns = JsonUtil.Obj(config["dns"]);
            var servers = JsonUtil.Arr(dns.ContainsKey("servers") ? dns["servers"] : null);
            var fakeip = JsonUtil.Obj(dns.ContainsKey("fakeip") ? dns["fakeip"] : null);
            bool hasLegacyFakeip = fakeip.Count > 0 && JsonUtil.Bool(fakeip, "enabled", false);
            string fakeipTag = "fakeip";

            if (hasLegacyFakeip)
            {
                foreach (object entry in servers)
                {
                    var old = JsonUtil.Obj(entry);
                    if (String.Equals(JsonUtil.Str(old, "address"), "fakeip", StringComparison.OrdinalIgnoreCase))
                    {
                        fakeipTag = JsonUtil.Str(old, "tag", "fakeip");
                        break;
                    }
                }
            }

            var normalized = new List<object>();
            bool fakeipAdded = false;
            foreach (object entry in servers)
            {
                var old = JsonUtil.Obj(entry);
                if (old.Count == 0)
                {
                    string address = Convert.ToString(entry);
                    if (!String.IsNullOrWhiteSpace(address)) normalized.Add(CreateLegacyDnsServer(address, null));
                    continue;
                }

                string type = JsonUtil.Str(old, "type");
                string addressValue = JsonUtil.Str(old, "address");
                if (type.Length == 0 && addressValue.Length > 0)
                {
                    if (hasLegacyFakeip && String.Equals(addressValue, "fakeip", StringComparison.OrdinalIgnoreCase))
                    {
                        normalized.Add(CreateFakeipServer(fakeipTag, fakeip));
                        fakeipAdded = true;
                        continue;
                    }
                    normalized.Add(CreateLegacyDnsServer(addressValue, old));
                    continue;
                }

                // Also upgrade the removed string-server shorthand while leaving
                // already typed 1.14 server objects byte-for-byte equivalent.
                if (type.Length == 0 && old.ContainsKey("server"))
                {
                    var modern = new Dictionary<string, object>(old);
                    modern["type"] = "udp";
                    normalized.Add(modern);
                    continue;
                }
                normalized.Add(entry);
            }

            if (hasLegacyFakeip && !fakeipAdded) normalized.Add(CreateFakeipServer(fakeipTag, fakeip));
            if (servers.Count > 0 || normalized.Count > 0) dns["servers"] = normalized;
            if (dns.ContainsKey("fakeip")) dns.Remove("fakeip");
            config["dns"] = dns;
        }

        private static Dictionary<string, object> CreateFakeipServer(string tag, Dictionary<string, object> legacy)
        {
            var server = new Dictionary<string, object> { { "type", "fakeip" }, { "tag", tag } };
            string ipv4 = JsonUtil.Str(legacy, "inet4_range");
            string ipv6 = JsonUtil.Str(legacy, "inet6_range");
            if (ipv4.Length > 0) server["inet4_range"] = ipv4;
            if (ipv6.Length > 0) server["inet6_range"] = ipv6;
            return server;
        }

        private static Dictionary<string, object> CreateLegacyDnsServer(string address, Dictionary<string, object> old)
        {
            old = old ?? new Dictionary<string, object>();
            var result = new Dictionary<string, object>(old);
            result.Remove("address");
            string tag = JsonUtil.Str(old, "tag");
            int port = JsonUtil.Int(old, "port", 0);
            Uri uri;
            string scheme = "udp";
            string host = address;
            string path = null;
            if (Uri.TryCreate(address, UriKind.Absolute, out uri) && uri.Scheme != "file" && uri.Scheme.Length > 1)
            {
                scheme = uri.Scheme.ToLowerInvariant();
                if (scheme == "https") { host = uri.Host; path = String.IsNullOrEmpty(uri.AbsolutePath) || uri.AbsolutePath == "/" ? "/dns-query" : uri.AbsolutePath; if (uri.Query.Length > 0) path += uri.Query; }
                else if (scheme == "tls" || scheme == "tcp" || scheme == "udp" || scheme == "quic" || scheme == "h3") host = uri.Host;
                else if (scheme == "dhcp") { host = uri.Host; }
                else { scheme = "udp"; host = address; }
                if (port == 0 && !uri.IsDefaultPort) port = uri.Port;
            }
            else if (address.Equals("local", StringComparison.OrdinalIgnoreCase)) scheme = "local";
            else
            {
                Uri hostAndPort;
                if (Uri.TryCreate("udp://" + address, UriKind.Absolute, out hostAndPort) && Uri.CheckHostName(hostAndPort.Host) != UriHostNameType.Unknown)
                {
                    host = hostAndPort.Host;
                    if (port == 0 && !hostAndPort.IsDefaultPort) port = hostAndPort.Port;
                }
            }

            result["type"] = scheme;
            if (tag.Length > 0) result["tag"] = tag;
            if (scheme != "local")
            {
                if (scheme == "dhcp")
                {
                    if (host.Length > 0 && host != "auto") result["interface"] = host;
                }
                else result["server"] = host;
                if (port > 0) result["server_port"] = port;
                if (path != null) result["path"] = path;
            }
            if (result.ContainsKey("address_resolver"))
            {
                if (!result.ContainsKey("domain_resolver")) result["domain_resolver"] = result["address_resolver"];
                result.Remove("address_resolver");
            }
            if (result.ContainsKey("address_strategy"))
            {
                if (!result.ContainsKey("strategy")) result["strategy"] = result["address_strategy"];
                result.Remove("address_strategy");
            }
            result.Remove("port");
            return result;
        }

        internal static Dictionary<string, object> BuildProbe(AppState sourceState, Profile profile, string logPath, string secret, int localPort)
        {
            var state = JsonUtil.Serializer.Deserialize<AppState>(JsonUtil.Print(sourceState));
            state.TunnelMode = "System Proxy";
            state.Settings["proxyPort"] = localPort; state.Settings["allowLan"] = false; state.Settings["socksAuth"] = false;
            var config = Build(state, profile, logPath, secret);
            config["inbounds"] = new List<object> { new Dictionary<string, object> { { "type", "mixed" }, { "tag", "probe-in" }, { "listen", "127.0.0.1" }, { "listen_port", localPort } } };
            config.Remove("experimental");
            return config;
        }

        private static Dictionary<string, object> ConfigureOutbound(Dictionary<string, object> source, string tag, AppState state)
        {
            var outbound = JsonUtil.Copy(source); outbound["tag"] = tag;
            var tls = JsonUtil.Obj(outbound.ContainsKey("tls") ? outbound["tls"] : null);
            bool transportCanFragment = tls.Count > 0 && !new[] { "hysteria", "hysteria2", "tuic", "wireguard" }.Contains(JsonUtil.Str(outbound, "type")) && String.IsNullOrEmpty(JsonUtil.Str(outbound, "flow"));
            if (GetBool(state, "fragment", false) && transportCanFragment)
            {
                string kind = GetString(state, "fragmentType", "xray");
                if (kind == "xray")
                {
                    var fragment = new Dictionary<string, object> { { "packets", GetString(state, "fragmentPackets", "tlshello") }, { "length", GetString(state, "fragmentLength", "50-100") }, { "interval", GetString(state, "fragmentInterval", "10-20") } };
                    string maxSplit = GetString(state, "fragmentMaxSplit", "100-200"); if (!String.IsNullOrWhiteSpace(maxSplit)) fragment["max_split"] = maxSplit;
                    outbound["wraith_fragment"] = fragment;
                }
                else { tls["fragment"] = true; tls["fragment_fallback_delay"] = FirstRangeValue(GetString(state, "fragmentDelay", "10")) + "ms"; }
            }
            if (GetBool(state, "recordFragment", false) && transportCanFragment) tls["record_fragment"] = true;
            if (tls.Count > 0) outbound["tls"] = tls;
            if (GetBool(state, "noise", false))
            {
                string noiseType = GetString(state, "noiseType", "array"); var noise = new Dictionary<string, object> { { "type", noiseType }, { "delay", GetString(state, "noiseDelay", "10-16") } };
                if (noiseType == "rand") { noise["rand"] = GetString(state, "noiseRand", "1-8192"); noise["rand_range"] = GetString(state, "noiseRange", "0-255"); }
                else noise["packet"] = GetString(state, "noisePacket", "1A,2B,3C");
                outbound["wraith_noise"] = new object[] { noise };
            }
            if (GetBool(state, "mux", false) && String.IsNullOrEmpty(JsonUtil.Str(outbound, "flow")) && new[] { "vmess", "vless", "trojan", "shadowsocks" }.Contains(JsonUtil.Str(outbound, "type")))
            {
                bool streams = GetString(state, "muxLimit", "connections") == "streams"; string limit = streams ? "max_streams" : "max_connections";
                outbound["multiplex"] = new Dictionary<string, object> { { "enabled", true }, { "protocol", GetString(state, "muxProtocol", "h2mux") }, { limit, GetInt(state, streams ? "muxStreams" : "muxConnections", streams ? 8 : 4) }, { "padding", GetBool(state, "muxPadding", true) } };
            }
            return outbound;
        }

        private static List<object> Inbounds(AppState state)
        {
            int proxyPort = GetInt(state, "proxyPort", 2081);
            var result = new List<object>();
            if (state.TunnelMode == "TUN") result.Add(new Dictionary<string, object> {
                {"type","tun"},{"tag","tun-in"},{"address",new[]{"172.19.0.1/30","fdfe:dcba:9876::1/126"}},
                {"mtu",GetInt(state,"mtu",1500)},{"auto_route",true},{"strict_route",true},{"stack","mixed"},{"dns_mode","hijack"}
            });
            if (GetBool(state, "allowLan", false) && !GetBool(state, "socksAuth", false)) throw new InvalidOperationException("Для доступа из LAN включите SOCKS5-авторизацию, чтобы не открыть прокси всей сети.");
            if (GetBool(state, "socksAuth", false) && GetString(state, "proxyPassword", "").Length < 8) throw new InvalidOperationException("Задайте пароль локального прокси не короче 8 символов.");
            var mixed = new Dictionary<string, object> { { "type", "mixed" }, { "tag", "mixed-in" }, { "listen", GetBool(state, "allowLan", false) ? "0.0.0.0" : "127.0.0.1" }, { "listen_port", proxyPort } };
            if (GetBool(state, "socksAuth", false)) mixed["users"] = new[] { new Dictionary<string, object> { { "username", GetString(state, "proxyUser", "wraith") }, { "password", GetString(state, "proxyPassword", "") } } };
            result.Add(mixed);
            return result;
        }

        private static string FirstRangeValue(string value) { if (String.IsNullOrWhiteSpace(value)) return "10"; int separator = value.IndexOf('-'); return separator < 0 ? value.Trim() : value.Substring(0, separator).Trim(); }

        private static Dictionary<string, object> BuildDns(Profile p, AppState state)
        {
            if (p.Full && p.Outbound.ContainsKey("dns")) return JsonUtil.Obj(p.Outbound["dns"]);
            if (p.Dns.Count > 0 && GetBool(state, "dnsFromJson", true))
            {
                var raw = JsonUtil.Arr(p.Dns.ContainsKey("servers") ? p.Dns["servers"] : null);
                var servers = new List<object>();
                foreach (object entry in raw)
                {
                    string address = Convert.ToString(entry); var d = JsonUtil.Obj(entry);
                    if (d.Count > 0) address = JsonUtil.Str(d, "address", JsonUtil.Str(d, "server"));
                    string tag = JsonUtil.Str(d, "tag", "remote-dns-" + servers.Count); Uri uri;
                    if (Uri.TryCreate(address, UriKind.Absolute, out uri) && (uri.Scheme == "https" || uri.Scheme == "tls"))
                    {
                        var dnsServer = new Dictionary<string, object> { { "type", uri.Scheme }, { "tag", tag }, { "server", uri.Host }, { "server_port", JsonUtil.Int(d, "port", uri.IsDefaultPort ? (uri.Scheme == "https" ? 443 : 853) : uri.Port) }, { "detour", "proxy" } };
                        if (uri.Scheme == "https") dnsServer["path"] = String.IsNullOrEmpty(uri.AbsolutePath) ? "/dns-query" : uri.AbsolutePath;
                        servers.Add(dnsServer);
                    }
                    else if (!String.IsNullOrWhiteSpace(address))
                    {
                        string dnsHost = address; int dnsPort = JsonUtil.Int(d, "port", 53); Uri parsed;
                        if (Uri.TryCreate("udp://" + address, UriKind.Absolute, out parsed)) { dnsHost = parsed.Host; if (!parsed.IsDefaultPort) dnsPort = parsed.Port; }
                        if (Uri.CheckHostName(dnsHost) != UriHostNameType.Unknown) servers.Add(new Dictionary<string, object> { { "type", "udp" }, { "tag", tag }, { "server", dnsHost }, { "server_port", dnsPort }, { "detour", "proxy" } });
                    }
                }
                if (servers.Count > 0)
                {
                    servers.Insert(0, new Dictionary<string, object> { { "type", "udp" }, { "tag", "bootstrap" }, { "server", "1.1.1.1" } });
                    var tags = servers.Select(s => JsonUtil.Str(JsonUtil.Obj(s), "tag")).ToList();
                    string final = JsonUtil.Str(p.Dns, "final"); if (!tags.Contains(final)) final = tags[0];
                    if (String.IsNullOrEmpty(JsonUtil.Str(p.Dns, "final"))) final = tags.Count > 1 ? tags[1] : tags[0];
                    string strategy = JsonUtil.Str(p.Dns, "queryStrategy", JsonUtil.Str(p.Dns, "strategy", "prefer_ipv4")).ToLowerInvariant();
                    if (strategy == "useip") strategy = "prefer_ipv4"; else if (strategy == "useipv4") strategy = "ipv4_only"; else if (strategy == "useipv6") strategy = "ipv6_only";
                    if (strategy != "ipv4_only" && strategy != "ipv6_only" && strategy != "prefer_ipv4" && strategy != "prefer_ipv6") strategy = "prefer_ipv4";
                    return new Dictionary<string, object> { { "servers", servers }, { "final", final }, { "strategy", strategy }, { "reverse_mapping", true } };
                }
            }
            string dns = GetString(state, "dns", "Cloudflare DNS"); string server = dns == "Google DNS" ? "8.8.8.8" : dns == "Quad9" ? "9.9.9.9" : "1.1.1.1";
            return new Dictionary<string, object> { { "servers", new object[] { new Dictionary<string, object> { { "type", "udp" }, { "tag", "bootstrap" }, { "server", "1.1.1.1" } }, new Dictionary<string, object> { { "type", "https" }, { "tag", "remote-dns" }, { "server", server }, { "server_port", 443 }, { "path", "/dns-query" }, { "detour", "proxy" } } } }, { "final", "remote-dns" }, { "strategy", GetString(state, "ipPreference", "auto") == "ipv4" ? "ipv4_only" : "prefer_ipv4" } };
        }

        private static void AddXrayRules(Dictionary<string, object> routing, List<object> output, List<object> ruleSets)
        {
            var xrules = JsonUtil.Arr(routing.ContainsKey("rules") ? routing["rules"] : null);
            var knownRuleSets = new HashSet<string>(StringComparer.Ordinal);
            foreach (object value in xrules)
            {
                var x = JsonUtil.Obj(value); string tag = JsonUtil.Str(x, "outboundTag", "proxy").ToLowerInvariant();
                string outbound = tag.Contains("direct") || tag == "freedom" ? "direct" : tag.Contains("block") || tag == "blackhole" ? "block" : "proxy";
                var r = new Dictionary<string, object>(); var domains = new List<string>(); var suffixes = new List<string>(); var ips = new List<string>(); var tags = new List<string>();
                foreach (object item in JsonUtil.Arr(x.ContainsKey("domain") ? x["domain"] : null))
                {
                    string domain = Convert.ToString(item); if (domain.StartsWith("domain:")) suffixes.Add(domain.Substring(7)); else if (domain.StartsWith("full:")) domains.Add(domain.Substring(5)); else if (domain.StartsWith("geosite:")) { string geo = GeoName("geosite", domain.Substring(8)); AddRemoteRuleSet("geosite", geo, tags, knownRuleSets, ruleSets); } else domains.Add(domain);
                }
                if (domains.Count > 0) r["domain"] = domains;
                if (suffixes.Count > 0) r["domain_suffix"] = suffixes;
                foreach (object item in JsonUtil.Arr(x.ContainsKey("ip") ? x["ip"] : null)) { string ip = Convert.ToString(item); if (ip.StartsWith("geoip:")) { string geo = GeoName("geoip", ip.Substring(6)); AddRemoteRuleSet("geoip", geo, tags, knownRuleSets, ruleSets); } else ips.Add(ip); } if (ips.Count > 0) r["ip_cidr"] = ips;
                if (tags.Count > 0) r["rule_set"] = tags;
                if (x.ContainsKey("network")) r["network"] = JsonUtil.Str(x, "network").Split(',');
                if (x.ContainsKey("port")) r["port"] = Convert.ToString(x["port"]);
                r["outbound"] = outbound; if (r.Count > 1) output.Add(r);
            }
        }
        private static string GeoName(string kind, string raw)
        {
            string geo = (raw ?? "").Trim().ToLowerInvariant(); if (geo.StartsWith("!") || !System.Text.RegularExpressions.Regex.IsMatch(geo, "^[a-z0-9._-]+$")) return "";
            if (kind == "geosite") return geo == "ads" || geo == "ads-all" || geo == "category-ads" ? "category-ads-all" : geo;
            return System.Text.RegularExpressions.Regex.IsMatch(geo, "^[a-z]{2}$") ? geo : "";
        }
        private static void AddRemoteRuleSet(string kind, string geo, List<string> tags, HashSet<string> known, List<object> output)
        {
            if (String.IsNullOrEmpty(geo)) return; string tag = kind + "-" + geo.Replace('.', '-').Replace('@', '-'); if (!tags.Contains(tag)) tags.Add(tag);
            if (!known.Add(tag)) return;
            string root = kind == "geosite" ? "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-" : "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-";
            output.Add(new Dictionary<string, object> { { "type", "remote" }, { "tag", tag }, { "format", "binary" }, { "url", root + geo + ".srs" }, { "download_detour", "proxy" }, { "update_interval", "24h" } });
        }
        private static object Get(AppState state, string key) { object value; return state.Settings.TryGetValue(key, out value) ? value : null; }
        private static bool GetBool(AppState s, string k, bool d) { object v = Get(s, k); return v == null ? d : Convert.ToBoolean(v); }
        private static int GetInt(AppState s, string k, int d) { object v = Get(s, k); return v == null ? d : Convert.ToInt32(v); }
        private static string GetString(AppState s, string k, string d) { object v = Get(s, k); return v == null ? d : Convert.ToString(v); }
    }
}
