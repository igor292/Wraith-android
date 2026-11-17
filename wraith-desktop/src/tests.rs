//! Config builders checked against the same cases as the Android app, and against the real cores.

use crate::importer;
use crate::json::Get;
use crate::model::{AppState, Profile};
use serde_json::{json, Value};

const BALANCER: &str = r#"{"remarks":"Auto","dns":{"servers":["1.1.1.1"]},
 "inbounds":[{"tag":"socks-in","protocol":"socks","listen":"127.0.0.1","port":10808,"settings":{"udp":true}},
   {"tag":"http-in","protocol":"http","listen":"127.0.0.1","port":10809},
   {"tag":"dns-in","protocol":"dokodemo-door","listen":"127.0.0.1","port":5353,"settings":{"address":"1.1.1.1","port":53,"network":"udp"}}],
 "outbounds":[
   {"tag":"proxy-nl","protocol":"vless","settings":{"vnext":[{"address":"192.0.2.1","port":443,"users":[{"id":"00948d0e-1437-0005-abfe-bb90bf6bc89a","encryption":"none"}]}]}},
   {"tag":"proxy-de","protocol":"vless","settings":{"vnext":[{"address":"198.51.100.2","port":8443,"users":[{"id":"00948d0e-1437-0005-abfe-bb90bf6bc89a","encryption":"none"}]}]}},
   {"tag":"proxy-fi","protocol":"trojan","settings":{"servers":[{"address":"203.0.113.3","port":443,"password":"secret"}]},"streamSettings":{"security":"tls"}},
   {"protocol":"freedom"},{"tag":"frag","protocol":"freedom","settings":{"fragment":{"packets":"tlshello","length":"10-20","interval":"10-20"}}},
   {"tag":"block","protocol":"blackhole"},{"tag":"dns-out","protocol":"dns"}],
 "observatory":{"subjectSelector":["proxy-"],"probeUrl":"https://www.gstatic.com/generate_204","probeInterval":"1m"},
 "api":{"tag":"api","listen":"0.0.0.0:10085","services":["HandlerService"]},
 "log":{"loglevel":"warning","access":"C:/Windows/evil.log","error":"C:/x.log"},
 "routing":{"domainStrategy":"IPIfNonMatch","balancers":[{"tag":"auto","selector":["proxy-"],"strategy":{"type":"leastPing"},"fallbackTag":"proxy-nl"}],
   "rules":[{"type":"field","inboundTag":["dns-in"],"outboundTag":"dns-out"},{"type":"field","domain":["domain:example.ru"],"outboundTag":"direct"},
     {"type":"field","inboundTag":["socks-in","http-in"],"balancerTag":"auto"}]}}"#;

fn state(settings: Value) -> AppState {
    let mut s = AppState::default();
    for (k, v) in settings.as_object().unwrap() { s.settings.insert(k.clone(), v.clone()); }
    s
}
fn profile(src: &str) -> Profile { importer::parse(src).unwrap().profiles.remove(0) }

#[test]
fn balancer_document_is_one_row_with_three_servers() {
    let p = profile(BALANCER);
    assert_eq!(p.servers, 3); assert!(p.balancer); assert_eq!(p.name, "Auto"); assert_eq!(p.engine, "xray");
}

#[test]
fn provider_balancer_kept_and_inlets_remapped() {
    let st = state(json!({})); let p = profile(BALANCER);
    let built = crate::xray::build(&st, &p, &["192.168.1.1".into()], 1234).unwrap();
    let c = &built.config;
    let rules = c.o("routing").unwrap().objects("rules");
    let catch = rules.iter().find(|r| r.s("balancerTag") == "auto").expect("balancer rule");
    let tags: Vec<String> = catch.a("inboundTag").unwrap().iter().map(|v| v.as_str().unwrap().to_string()).collect();
    assert!(tags.contains(&"http".to_string()) && tags.contains(&"socks".to_string()));
    assert_eq!(built.proxy_tags, vec!["proxy-nl", "proxy-de", "proxy-fi"]);
    // Security: no provider API listener, no log file paths.
    assert!(!c.has("api"));
    assert!(!c.o("log").unwrap().has("access") && !c.o("log").unwrap().has("error"));
    // System DNS by default, queried directly.
    assert_eq!(c.o("dns").unwrap().a("servers").unwrap()[0], json!("192.168.1.1"));
    assert!(rules.iter().any(|r| r.s("port") == "53" && r.a("ip").map(|a| a.contains(&json!("192.168.1.1"))).unwrap_or(false)));
}

#[test]
fn auto_select_adds_balancer_for_plain_multi_server_document() {
    let src = BALANCER.replace(r#""balancerTag":"auto""#, r#""outboundTag":"proxy-nl""#);
    let p = profile(&src); assert!(!p.balancer);
    let pinned = crate::xray::build(&state(json!({})), &p, &[], 1).unwrap().config;
    assert!(!crate::xray::has_balancer(&pinned, crate::xray::AUTO_BALANCER));
    let auto = crate::xray::build(&state(json!({"autoSelect": true})), &p, &[], 1).unwrap().config;
    assert!(crate::xray::has_balancer(&auto, crate::xray::AUTO_BALANCER));
    assert!(auto.o("routing").unwrap().objects("rules").iter().any(|r| r.s("balancerTag") == crate::xray::AUTO_BALANCER));
}

#[test]
fn links_become_xray_outbounds() {
    let vless = "vless://00948d0e-1437-0005-abfe-bb90bf6bc89a@example.com:443?type=xhttp&security=reality&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=0123&sni=www.example.org&fp=chrome&path=%2Fx&mode=auto#%F0%9F%87%A9%F0%9F%87%AA%20Germany";
    let p = profile(vless);
    assert_eq!(p.name, "🇩🇪 Germany"); assert_eq!(p.transport, "xhttp"); assert_eq!(p.security, "reality"); assert_eq!(p.engine, "xray");
    let src = crate::xray::source(&p).unwrap();
    let o = &src.objects("outbounds")[0];
    assert_eq!(o.o("streamSettings").unwrap().o("realitySettings").unwrap().s("serverName"), "www.example.org");
    // TUIC and obfuscated Hysteria2 go to sing-box.
    assert!(profile("tuic://uuid:pass@example.com:443?sni=example.com#t").is_singbox());
    assert!(profile("hy2://pass@example.com:443?obfs=salamander&obfs-password=x#h").is_singbox());
    assert!(!profile("hy2://pass@example.com:443?sni=example.com#h").is_singbox());
    let ss = profile("ss://YWVzLTI1Ni1nY206c2VjcmV0@example.com:8388#ss");
    assert_eq!(ss.protocol, "shadowsocks");
}

#[test]
fn custom_and_provider_dns_only_when_switched_on() {
    let p = profile(BALANCER);
    let custom = crate::xray::build(&state(json!({"customDns": true, "tunnelDns": "tls://1.1.1.1"})), &p, &["10.0.0.1".into()], 1).unwrap().config;
    assert_eq!(custom.o("dns").unwrap().a("servers").unwrap()[0], json!("tls://1.1.1.1"));
    let provider = crate::xray::build(&state(json!({"dnsFromJson": true})), &p, &["10.0.0.1".into()], 1).unwrap().config;
    assert_eq!(provider.o("dns").unwrap().a("servers").unwrap()[0], json!("1.1.1.1"));
}

#[test]
fn lan_proxy_requires_password() {
    let p = profile(BALANCER);
    assert!(crate::xray::build(&state(json!({"allowLan": true})), &p, &[], 1).is_err());
    assert!(crate::xray::build(&state(json!({"allowLan": true, "proxyPassword": "longenough"})), &p, &[], 1).is_ok());
}

fn probe_runtime(src: &str) -> crate::json::Obj {
    let p = profile(src); let source = crate::xray::source(&p).unwrap(); let tags = crate::xray::proxy_tags(&source);
    let (mut i, mut o, mut r) = (vec![], vec![], vec![]); let mut dns = None;
    let eps: Vec<_> = source.objects("outbounds").into_iter().filter(|x| tags.contains(&x.s("tag"))).flat_map(|x| importer::endpoints(x)).collect();
    for (n, e) in eps.iter().enumerate() {
        let d = crate::xray::append_probe(&p, &AppState::default().settings, &source, e, n, 20000 + n as u16, &mut o, &mut i, &mut r).unwrap();
        if dns.is_none() { dns = d; }
    }
    crate::xray::assemble_probe(i, o, r, dns)
}

#[test]
fn every_server_of_an_auto_config_gets_a_probe() {
    let r = probe_runtime(BALANCER);
    assert_eq!(r.a("inbounds").unwrap().len(), 3);
    assert_eq!(r.objects("outbounds")[0].s("tag"), "wraith-probe-direct");
    assert!(r.o("routing").unwrap().objects("rules").iter().all(|x| !x.has("balancerTag")));
}

fn xray_exe() -> Option<std::path::PathBuf> {
    let p = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../.tools/xray-integration/core/xray.exe");
    p.exists().then_some(p)
}
fn xray_accepts(config: &crate::json::Obj, name: &str) {
    let Some(exe) = xray_exe() else { eprintln!("xray.exe not found, skipping"); return };
    let dir = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("target/test-configs"); std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join(format!("{name}.json")); std::fs::write(&path, serde_json::to_vec_pretty(config).unwrap()).unwrap();
    let out = std::process::Command::new(&exe).args(["run", "-test", "-c"]).arg(&path).env("XRAY_LOCATION_ASSET", exe.parent().unwrap()).output().unwrap();
    assert!(out.status.success(), "{name}: {}{}", String::from_utf8_lossy(&out.stdout), String::from_utf8_lossy(&out.stderr));
}

#[test]
fn real_xray_accepts_generated_configs() {
    let p = profile(BALANCER);
    for (name, settings) in [("proxy", json!({})), ("tun", json!({"tunnelMode": "tun"})), ("global", json!({"mode": "global"})),
        ("dpi", json!({"fragment": true, "noise": true, "mux": true})), ("auto", json!({"autoSelect": true}))] {
        xray_accepts(&crate::xray::build(&state(settings), &p, &["192.168.1.1".into()], 1234).unwrap().config, name);
    }
    let link = profile("vless://00948d0e-1437-0005-abfe-bb90bf6bc89a@example.com:443?type=ws&security=tls&sni=example.com&path=%2Fws&host=example.com&fp=chrome#ws");
    let mut st = state(json!({"tunnelMode": "tun"}));
    st.rules.push(json!({"kind": "process", "value": "telegram.exe", "action": "direct", "enabled": true}).as_object().unwrap().clone());
    st.rules.push(json!({"kind": "geosite", "value": "category-ads-all", "action": "block", "enabled": true}).as_object().unwrap().clone());
    xray_accepts(&crate::xray::build(&st, &link, &[], 1).unwrap().config, "link-rules");
    let hy = profile("hy2://pass@example.com:443?sni=example.com&mport=20000-30000#hy");
    xray_accepts(&crate::xray::build(&state(json!({})), &hy, &[], 1).unwrap().config, "hysteria2");
    xray_accepts(&probe_runtime(BALANCER), "probe");
}

/// End to end: a local Xray VLESS server, then the real probe path (temporary Xray + HTTP inlet + ureq).
#[test]
#[ignore]
fn live_probe_through_local_server() {
    let Some(exe) = xray_exe() else { return };
    let tmp = std::env::temp_dir().join(format!("wraith-live-{}", crate::util::random_hex(4)));
    std::fs::create_dir_all(&tmp).unwrap();
    std::env::set_var("LOCALAPPDATA", &tmp);
    let _ = std::fs::create_dir_all(crate::store::folder());
    std::fs::copy(&exe, crate::util::app_dir().join("xray.exe")).ok();
    for f in ["geoip.dat", "geosite.dat"] { std::fs::copy(exe.parent().unwrap().join(f), crate::util::app_dir().join(f)).ok(); }
    let port = crate::util::free_port();
    let server = json!({"log": {"loglevel": "warning"}, "inbounds": [{"listen": "127.0.0.1", "port": port, "protocol": "vless", "settings": {"clients": [{"id": "00948d0e-1437-0005-abfe-bb90bf6bc89a"}], "decryption": "none"}}], "outbounds": [{"protocol": "freedom"}]});
    let cfg = tmp.join("server.json"); std::fs::write(&cfg, server.to_string()).unwrap();
    let mut srv = std::process::Command::new(&exe).args(["run", "-c"]).arg(&cfg).stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null()).spawn().unwrap();
    std::thread::sleep(std::time::Duration::from_millis(800));
    let link = profile(&format!("vless://00948d0e-1437-0005-abfe-bb90bf6bc89a@127.0.0.1:{port}?type=tcp&security=none#local"));
    // An auto config: one dead server (closed port) and the live one; the row must report the live latency.
    let auto_src = format!(r#"{{"remarks":"auto","outbounds":[
        {{"tag":"proxy-dead","protocol":"vless","settings":{{"vnext":[{{"address":"127.0.0.1","port":1,"users":[{{"id":"00948d0e-1437-0005-abfe-bb90bf6bc89a","encryption":"none"}}]}}]}}}},
        {{"tag":"proxy-live","protocol":"vless","settings":{{"vnext":[{{"address":"127.0.0.1","port":{port},"users":[{{"id":"00948d0e-1437-0005-abfe-bb90bf6bc89a","encryption":"none"}}]}}]}}}},
        {{"tag":"direct","protocol":"freedom"}}],
        "observatory":{{"subjectSelector":["proxy-"]}},
        "dns":{{"servers":["fakedns",{{"address":"1.1.1.1","domains":["geosite:google"]}},"localhost"],"tag":"dns-in"}},
        "routing":{{"balancers":[{{"tag":"b","selector":["proxy-"],"strategy":{{"type":"leastPing"}}}}],"rules":[{{"type":"field","network":"tcp,udp","balancerTag":"b"}}]}}}}"#);
    let auto = profile(&auto_src);
    let results = std::sync::Arc::new(std::sync::Mutex::new(vec![]));
    let r2 = results.clone();
    let report: crate::pinger::Report = std::sync::Arc::new(move |id: &str, ms: i64, err: String| r2.lock().unwrap().push((id.to_string(), ms, err)));
    let mut st = AppState::default(); st.set("pingUrl", "https://www.gstatic.com/generate_204".into());
    crate::pinger::run(st, vec![link.clone(), auto.clone()], report, std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false)));
    let _ = srv.kill();
    let results = results.lock().unwrap().clone();
    eprintln!("{results:?}");
    for p in [&link, &auto] {
        let r = results.iter().find(|r| r.0 == p.id).expect("reported");
        assert!(r.1 > 0, "{} failed: {}", p.name, r.2);
    }
}

/// The tunnel config itself (system-proxy mode, without touching Windows settings): traffic flows and counters move.
#[test]
#[ignore]
fn live_tunnel_config_carries_traffic() {
    let Some(exe) = xray_exe() else { return };
    let tmp = std::env::temp_dir().join(format!("wraith-live-{}", crate::util::random_hex(4))); std::fs::create_dir_all(&tmp).unwrap();
    let sport = crate::util::free_port();
    let server = json!({"inbounds": [{"listen": "127.0.0.1", "port": sport, "protocol": "vless", "settings": {"clients": [{"id": "00948d0e-1437-0005-abfe-bb90bf6bc89a"}], "decryption": "none"}}], "outbounds": [{"protocol": "freedom"}]});
    std::fs::write(tmp.join("s.json"), server.to_string()).unwrap();
    let mut srv = std::process::Command::new(&exe).args(["run", "-c"]).arg(tmp.join("s.json")).stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null()).spawn().unwrap();
    let p = profile(&format!("vless://00948d0e-1437-0005-abfe-bb90bf6bc89a@127.0.0.1:{sport}?type=tcp&security=none#local"));
    let (http, socks, metrics) = (crate::util::free_port(), crate::util::free_port(), crate::util::free_port());
    let mut st = state(json!({"httpProxyPort": http, "proxyPort": socks, "mode": "global"}));
    st.rules.push(json!({"kind": "domain_suffix", "value": "example.invalid", "action": "block", "enabled": true}).as_object().unwrap().clone());
    let built = crate::xray::build(&st, &p, &[], metrics).unwrap();
    std::fs::write(tmp.join("c.json"), serde_json::to_vec(&built.config).unwrap()).unwrap();
    let mut core = std::process::Command::new(&exe).args(["run", "-c"]).arg(tmp.join("c.json")).env("XRAY_LOCATION_ASSET", exe.parent().unwrap()).stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null()).spawn().unwrap();
    for _ in 0..50 { if crate::util::port_open(http) { break; } std::thread::sleep(std::time::Duration::from_millis(100)); }
    let ms = crate::pinger::measure("https://www.gstatic.com/generate_204", false, true, 8000, http);
    let stats: Result<crate::json::Obj, _> = ureq::get(&format!("http://127.0.0.1:{metrics}/debug/vars")).call().map_err(|e| e.to_string()).and_then(|r| r.into_json().map_err(|e| e.to_string()));
    let _ = core.kill(); let _ = srv.kill();
    assert!(ms.is_ok(), "through tunnel: {ms:?}");
    let down = stats.unwrap().o("stats").and_then(|s| s.o("outbound")).and_then(|o| o.o(&built.proxy_tags[0])).map(|t| t.i("downlink", 0)).unwrap_or(0);
    eprintln!("latency {:?}, proxy downlink {down} bytes", ms);
    assert!(down > 0, "traffic counter did not move");
}

#[test]
fn real_singbox_accepts_generated_configs() {
    let exe = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../.tools/sing-box/sing-box.exe");
    if !exe.exists() { return; }
    let dir = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("target/test-configs"); std::fs::create_dir_all(&dir).unwrap();
    let cases = [
        ("tuic", "tuic://00948d0e-1437-0005-abfe-bb90bf6bc89a:pass@example.com:443?sni=example.com&congestion_control=bbr#t", json!({})),
        ("hy2-obfs", "hy2://pass@example.com:443?sni=example.com&obfs=salamander&obfs-password=x&mport=20000-30000#h", json!({"tunnelMode": "tun"})),
        ("anytls", "anytls://pass@example.com:443?sni=example.com#a", json!({"customDns": true, "mode": "global"})),
        ("vless-h2", "vless://00948d0e-1437-0005-abfe-bb90bf6bc89a@example.com:443?type=http&security=tls&sni=example.com&path=%2Fh#h2", json!({})),
    ];
    for (name, link, settings) in cases {
        let p = profile(link); assert!(p.is_singbox(), "{name} should use sing-box");
        let mut st = state(settings); st.rules.push(json!({"kind": "process", "value": "telegram.exe", "action": "direct", "enabled": true}).as_object().unwrap().clone());
        let c = crate::singbox::build(&st, &p, &["192.168.1.1".into()], 9090, "x").unwrap();
        let path = dir.join(format!("sb-{name}.json")); std::fs::write(&path, serde_json::to_vec_pretty(&c).unwrap()).unwrap();
        let out = std::process::Command::new(&exe).args(["check", "-c"]).arg(&path).output().unwrap();
        assert!(out.status.success(), "{name}: {}", String::from_utf8_lossy(&out.stderr));
        let probe = crate::singbox::build_probe(&st, &p, 20000).unwrap();
        let path = dir.join(format!("sb-probe-{name}.json")); std::fs::write(&path, serde_json::to_vec_pretty(&probe).unwrap()).unwrap();
        let out = std::process::Command::new(&exe).args(["check", "-c"]).arg(&path).output().unwrap();
        assert!(out.status.success(), "probe {name}: {}", String::from_utf8_lossy(&out.stderr));
    }
}
