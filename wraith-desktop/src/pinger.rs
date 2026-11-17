//! Latency checks. "Через прокси" runs one short-lived Xray with a loopback HTTP inlet per server
//! (every server of an auto config is measured, the best one counts), so no VPN is needed.

use crate::importer::{endpoints, Endpoint, LOCAL_PROTOCOLS};
use crate::json::{Get, Obj};
use crate::model::{AppState, Profile};
use crate::util;
use std::collections::{HashMap, HashSet};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

pub type Report = Arc<dyn Fn(&str, i64, String) + Send + Sync>;

pub fn run(state: AppState, profiles: Vec<Profile>, report: Report, cancel: Arc<AtomicBool>) {
    let s = &state.settings;
    let kind = s.s_or("pingType", "proxyGet"); let timeout = s.i("pingTimeout", 5000).max(1000) as u64;
    if kind == "tcp" || kind == "icmp" {
        parallel(profiles, 16, |p| { if cancel.load(Ordering::Relaxed) { return; } match if kind == "icmp" { icmp(&p, timeout) } else { tcp(&p, timeout) } { Ok(ms) => report(&p.id, ms, String::new()), Err(e) => report(&p.id, -1, e) } });
        return;
    }
    let url = s.s_or("pingUrl", "https://www.gstatic.com/generate_204"); let head = kind == "proxyHead"; let keep_alive = s.s_or("pingMode", "keepalive") != "double";
    let (sing, xray): (Vec<Profile>, Vec<Profile>) = profiles.into_iter().partition(|p| p.is_singbox());
    let st = state.clone(); let r2 = report.clone(); let c2 = cancel.clone(); let u2 = url.clone();
    let singbox_thread = std::thread::spawn(move || parallel(sing, 3, |p| {
        if c2.load(Ordering::Relaxed) { return; }
        match singbox_probe(&st, &p, &u2, head, keep_alive, timeout) { Ok(ms) => r2(&p.id, ms, String::new()), Err(e) => r2(&p.id, -1, crate::log::safe(e)) }
    }));
    xray_batch(&state, xray, &url, head, keep_alive, timeout, &report, &cancel);
    let _ = singbox_thread.join();
}

fn parallel<T: Send + 'static>(items: Vec<T>, threads: usize, f: impl Fn(T) + Send + Sync) {
    let queue = Mutex::new(items.into_iter()); let f = &f; let queue = &queue;
    std::thread::scope(|scope| { for _ in 0..threads { scope.spawn(move || loop { let next = queue.lock().unwrap().next(); match next { Some(item) => f(item), None => break } }); } });
}

fn tcp(p: &Profile, timeout: u64) -> Result<i64, String> {
    if p.host.is_empty() || p.port < 1 { return Err("Нет адреса сервера.".into()); }
    if ["hysteria2", "tuic", "wireguard"].contains(&p.protocol.as_str()) { return Err("UDP-протокол: используйте проверку через прокси.".into()); }
    use std::net::ToSocketAddrs;
    let addr = (p.host.as_str(), p.port as u16).to_socket_addrs().map_err(|e| format!("DNS: {e}"))?.next().ok_or("Адрес не найден")?;
    let start = Instant::now();
    std::net::TcpStream::connect_timeout(&addr, Duration::from_millis(timeout)).map_err(|_| "Нет ответа".to_string())?;
    Ok(start.elapsed().as_millis().max(1) as i64)
}
fn icmp(p: &Profile, timeout: u64) -> Result<i64, String> {
    if p.host.is_empty() { return Err("Нет адреса сервера.".into()); }
    let out = util::hidden(std::path::Path::new("ping")).args(["-n", "1", "-w", &timeout.to_string(), &p.host]).output().map_err(|e| e.to_string())?;
    let text = String::from_utf8_lossy(&out.stdout);
    let re = regex::Regex::new(r"[=<](\d+)\s?(ms|мс)").unwrap();
    re.captures(&text).and_then(|c| c[1].parse::<i64>().ok()).map(|v| v.max(1)).ok_or_else(|| "ICMP недоступен или заблокирован".into())
}

struct Job { profile: Profile, endpoint: Endpoint, port: u16 }

fn xray_batch(state: &AppState, profiles: Vec<Profile>, url: &str, head: bool, keep_alive: bool, timeout: u64, report: &Report, cancel: &Arc<AtomicBool>) {
    let mut jobs = vec![];
    for p in &profiles {
        let result = (|| -> Result<Vec<Endpoint>, String> {
            let source = crate::xray::source(p)?; let tags = crate::xray::proxy_tags(&source); let mut seen = HashSet::new();
            let list: Vec<Endpoint> = source.objects("outbounds").into_iter()
                .filter(|o| tags.contains(&o.s("tag")) || (o.s("tag").is_empty() && !LOCAL_PROTOCOLS.contains(&o.s("protocol").as_str())))
                .flat_map(|o| endpoints(o)).filter(|e| seen.insert(format!("{}{}:{}", e.protocol, e.host, e.port))).take(24).collect();
            if list.is_empty() { Err("Нет сервера для проверки.".into()) } else { Ok(list) }
        })();
        match result { Ok(list) => for e in list { jobs.push(Job { profile: p.clone(), endpoint: e, port: 0 }); }, Err(e) => report(&p.id, -1, e) }
    }
    // Each server of an auto config is a job; a row reports once all of its jobs finished, with the best result.
    let remaining: Mutex<HashMap<String, (usize, Vec<i64>, String)>> = Mutex::new(HashMap::new());
    for j in &jobs { remaining.lock().unwrap().entry(j.profile.id.clone()).or_insert((0, vec![], String::new())).0 += 1; }
    let done = |id: &str, ms: i64, err: String| {
        let mut map = remaining.lock().unwrap();
        let Some(entry) = map.get_mut(id) else { return };
        if ms > 0 { entry.1.push(ms); } else if entry.2.is_empty() { entry.2 = err; }
        entry.0 -= 1;
        if entry.0 == 0 {
            match entry.1.iter().min() { Some(best) => report(id, *best, String::new()), None => report(id, -1, if entry.2.is_empty() { "Нет ответа".into() } else { entry.2.clone() }) }
        }
    };
    let mut iter = jobs.into_iter().peekable();
    while iter.peek().is_some() {
        if cancel.load(Ordering::Relaxed) { return; }
        let chunk: Vec<Job> = iter.by_ref().take(48).collect();
        run_xray(state, chunk, url, head, keep_alive, timeout, cancel, &done);
    }
}

fn run_xray(state: &AppState, chunk: Vec<Job>, url: &str, head: bool, keep_alive: bool, timeout: u64, cancel: &Arc<AtomicBool>, done: &(dyn Fn(&str, i64, String) + Sync)) {
    let (mut inbounds, mut outbounds, mut rules) = (vec![], vec![], vec![]); let mut dns: Option<Obj> = None; let mut valid = vec![];
    for (i, mut job) in chunk.into_iter().enumerate() {
        job.port = util::free_port();
        let r = crate::xray::source(&job.profile).and_then(|src| crate::xray::append_probe(&job.profile, &state.settings, &src, &job.endpoint, i, job.port, &mut outbounds, &mut inbounds, &mut rules));
        match r { Ok(d) => { if dns.is_none() { dns = d; } valid.push(job); } Err(e) => done(&job.profile.id, -1, crate::log::safe(e)) }
    }
    if valid.is_empty() { return; }
    let path = crate::store::folder().join(format!("probe-{}.json", util::random_hex(8)));
    let result = (|| -> Result<(), String> {
        let exe = crate::core::xray_exe(); if !exe.exists() { return Err("Не найден xray.exe рядом с Wraith.exe.".into()); }
        std::fs::write(&path, serde_json::to_vec(&crate::xray::assemble_probe(inbounds, outbounds, rules, dns)).unwrap()).map_err(|e| e.to_string())?;
        let mut child = util::hidden(&exe).args(["run", "-c", &path.to_string_lossy()]).stdin(std::process::Stdio::null()).stdout(std::process::Stdio::null()).stderr(std::process::Stdio::piped()).spawn().map_err(|e| e.to_string())?;
        let guard = KillOnDrop(&mut child);
        let deadline = Instant::now() + Duration::from_secs(6);
        for job in &valid {
            while !util::port_open(job.port) {
                if let Ok(Some(_)) = guard.0.try_wait() {
                    let mut err = String::new(); if let Some(mut e) = guard.0.stderr.take() { use std::io::Read; let _ = e.read_to_string(&mut err); }
                    return Err(format!("Xray не запустил проверку: {}", util::tail(&err, 300)));
                }
                if Instant::now() > deadline || cancel.load(Ordering::Relaxed) { return Err("Xray не открыл локальный вход для проверки.".into()); }
                std::thread::sleep(Duration::from_millis(30));
            }
        }
        let jobs: Vec<(String, u16)> = valid.iter().map(|j| (j.profile.id.clone(), j.port)).collect();
        parallel(jobs, 16, |(id, port)| {
            if cancel.load(Ordering::Relaxed) { done(&id, -1, "Проверка отменена".into()); return; }
            match measure(url, head, keep_alive, timeout, port) { Ok(ms) => done(&id, ms, String::new()), Err(e) => done(&id, -1, e) }
        });
        drop(guard); Ok(())
    })();
    if let Err(e) = result { for job in &valid { done(&job.profile.id, -1, e.clone()); } }
    let _ = std::fs::remove_file(&path);
}
struct KillOnDrop<'a>(&'a mut std::process::Child);
impl Drop for KillOnDrop<'_> { fn drop(&mut self) { let _ = self.0.kill(); let _ = self.0.wait(); } }

fn singbox_probe(state: &AppState, p: &Profile, url: &str, head: bool, keep_alive: bool, timeout: u64) -> Result<i64, String> {
    let exe = crate::core::singbox_exe(); if !exe.exists() { return Err("Не найден sing-box.exe рядом с Wraith.exe.".into()); }
    let port = util::free_port(); let path = crate::store::folder().join(format!("probe-{}.json", util::random_hex(8)));
    std::fs::write(&path, serde_json::to_vec(&crate::singbox::build_probe(state, p, port)?).unwrap()).map_err(|e| e.to_string())?;
    let result = (|| {
        let mut child = util::hidden(&exe).args(["run", "-c", &path.to_string_lossy()]).stdin(std::process::Stdio::null()).stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null()).spawn().map_err(|e| e.to_string())?;
        let guard = KillOnDrop(&mut child);
        let deadline = Instant::now() + Duration::from_secs(6);
        while !util::port_open(port) {
            if let Ok(Some(_)) = guard.0.try_wait() { return Err("sing-box не запустил проверку.".to_string()); }
            if Instant::now() > deadline { return Err("sing-box не открыл вход для проверки.".into()); }
            std::thread::sleep(Duration::from_millis(30));
        }
        measure(url, head, keep_alive, timeout, port)
    })();
    let _ = std::fs::remove_file(&path);
    result
}

/// Two requests through the local inlet; the best one counts (keep-alive reuses the connection).
pub fn measure(url: &str, head: bool, keep_alive: bool, timeout: u64, port: u16) -> Result<i64, String> {
    let proxy = ureq::Proxy::new(format!("http://127.0.0.1:{port}")).map_err(|e| e.to_string())?;
    let agent = ureq::AgentBuilder::new().proxy(proxy).timeout(Duration::from_millis(timeout)).redirects(0).user_agent("Mozilla/5.0").max_idle_connections(if keep_alive { 4 } else { 0 }).build();
    let mut best = i64::MAX; let mut last = String::new();
    for _ in 0..2 {
        let start = Instant::now();
        let response = if head { agent.head(url).call() } else { agent.get(url).call() };
        match response {
            Ok(r) | Err(ureq::Error::Status(_, r)) => {
                if !head { let mut body = r.into_reader(); let mut buf = [0u8; 8192]; let mut total = 0; use std::io::Read; while total < 1 << 20 { match body.read(&mut buf) { Ok(0) | Err(_) => break, Ok(n) => total += n } } }
                best = best.min(start.elapsed().as_millis().max(1) as i64);
            }
            Err(e) => last = short_error(&e.to_string()),
        }
    }
    if best == i64::MAX { Err(if last.is_empty() { "Нет ответа".into() } else { last }) } else { Ok(best) }
}

pub fn short_error(e: &str) -> String {
    let l = e.to_lowercase();
    if l.contains("reality") { "Reality: проверка не пройдена".into() }
    else if l.contains("timed out") || l.contains("timeout") || l.contains("deadline") { "Нет ответа".into() }
    else if l.contains("refused") { "Соединение отклонено".into() }
    else if l.contains("certificate") || l.contains("x509") { "Ошибка сертификата TLS".into() }
    else if l.contains("connection reset") || l.contains("closed") { "Соединение сброшено".into() }
    else { crate::log::safe(e) }
}
