using System;
using System.Collections.Generic;
using System.Web.Script.Serialization;

namespace Wraith.Windows
{
    public sealed class Profile
    {
        public Profile() { Id = Guid.NewGuid().ToString("N"); Name = "Сервер"; Group = Source = JsonSource = PingError = TlsPinUnsupported = ""; Latency = -1; Outbound = new Dictionary<string, object>(); Routing = new Dictionary<string, object>(); Dns = new Dictionary<string, object>(); Candidates = new List<Dictionary<string, object>>(); }
        public string Id { get; set; }
        public string Name { get; set; }
        public string Group { get; set; }
        public string Source { get; set; }
        public string JsonSource { get; set; }
        public string PingError { get; set; }
        public string TlsPinUnsupported { get; set; }
        public int Latency { get; set; }
        public bool Favorite { get; set; }
        public bool Full { get; set; }
        public Dictionary<string, object> Outbound { get; set; }
        public Dictionary<string, object> Routing { get; set; }
        public Dictionary<string, object> Dns { get; set; }
        public List<Dictionary<string, object>> Candidates { get; set; }
    }
    public sealed class Subscription
    {
        public Subscription() { Id = Guid.NewGuid().ToString("N"); Name = "Подписка"; Url = Error = ""; IntervalHours = 24; Updated = DateTime.MinValue; }
        public string Id { get; set; }
        public string Name { get; set; }
        public string Url { get; set; }
        public int IntervalHours { get; set; }
        public DateTime Updated { get; set; }
        public string Error { get; set; }
    }
    public sealed class AppState
    {
        public AppState() { Schema = 1; Selected = ""; TunnelMode = "TUN"; Profiles = new List<Profile>(); Subscriptions = new List<Subscription>(); Settings = Defaults(); }
        public int Schema { get; set; }
        public string Selected { get; set; }
        public string TunnelMode { get; set; }
        public List<Profile> Profiles { get; set; }
        public List<Subscription> Subscriptions { get; set; }
        public Dictionary<string, object> Settings { get; set; }
        public static Dictionary<string, object> Defaults()
        {
            return new Dictionary<string, object> {
                {"userAgent",""},{"dnsFromJson",true},{"dns","Cloudflare DNS"},{"ipPreference","auto"},
                {"subscriptionDeviceId",""},
                {"mtu",1500},{"fragment",false},{"fragmentType","xray"},{"fragmentPackets","tlshello"},{"fragmentLength","50-100"},
                {"fragmentInterval","10-20"},{"fragmentMaxSplit","100-200"},{"fragmentDelay",10},{"recordFragment",false},
                {"noise",false},{"noiseType","array"},{"noisePacket","1A,2B,3C"},{"noiseDelay","10-16"},{"noiseRand","1-8192"},{"noiseRange","0-255"},
                {"mux",false},{"muxProtocol","h2mux"},{"muxLimit","connections"},{"muxConnections",4},{"muxStreams",8},{"muxPadding",true},{"sniff",true},
                {"bypassLan",true},{"strictRoute",true},{"allowLan",false},{"proxyPort",2081},
                {"proxyPassword",""},{"mode","rules"},{"quic","allow"},{"pingType","proxyGet"},
                {"pingUrl","https://www.gstatic.com/generate_204"},{"pingTimeout",5000},{"sort","none"},
                {"theme","dark"},{"updateOnOpen",true},{"pingOnOpen",false}
            };
        }
    }
    internal static class JsonUtil
    {
        internal static readonly JavaScriptSerializer Serializer = new JavaScriptSerializer { MaxJsonLength = 16 * 1024 * 1024, RecursionLimit = 128 };
        internal static Dictionary<string, object> Obj(object value) { return value as Dictionary<string, object> ?? new Dictionary<string, object>(); }
        internal static List<object> Arr(object value) { var list = value as List<object>; if (list != null) return list; var array = value as object[]; return array == null ? new List<object>() : new List<object>(array); }
        internal static string Str(Dictionary<string, object> d, string key, string fallback = "") { object v; return d != null && d.TryGetValue(key, out v) && v != null ? Convert.ToString(v) : fallback; }
        internal static bool Bool(Dictionary<string, object> d, string key, bool fallback = false) { object v; return d != null && d.TryGetValue(key, out v) ? Convert.ToBoolean(v) : fallback; }
        internal static int Int(Dictionary<string, object> d, string key, int fallback = 0) { object v; return d != null && d.TryGetValue(key, out v) ? Convert.ToInt32(v) : fallback; }
        internal static Dictionary<string, object> Parse(string json) { return Obj(Serializer.DeserializeObject(json)); }
        internal static string Print(object value) { return Serializer.Serialize(value); }
        internal static Dictionary<string, object> Copy(Dictionary<string, object> value) { return Parse(Print(value)); }
    }
}
