//! HTTPS subscriptions with the documented Happ headers (profile-title, subscription-userinfo, announce, routing…).

use crate::json::base64_text;
use crate::model::Subscription;
use std::collections::HashMap;
use std::io::Read;
use std::time::Duration;

pub const PARSE_VERSION: i64 = 1;
const MAX_BYTES: u64 = 8 * 1024 * 1024;
/// Panels (Remnawave) return per-host Xray JSON for this Happ signature.
const HAPP_AGENT: &str = "Happ/4.4.1/Android";

pub fn create(url: &str) -> Result<Subscription, String> {
    let u = url::Url::parse(url.trim()).map_err(|_| "Некорректный адрес подписки.")?;
    if u.scheme() != "https" || !u.username().is_empty() || u.password().is_some() { return Err("Подписка должна быть обычной HTTPS-ссылкой без логина в адресе.".into()); }
    let sub = Subscription { url: u.to_string(), name: u.host_str().unwrap_or("Подписка").to_string(), ..Default::default() };
    update(sub.clone())?;
    Ok(sub)
}

pub fn update(original: Subscription) -> Result<(), String> {
    let mut sub = original.clone();
    let url = sub.url.clone();
    match fetch(&mut sub, &url) {
        Ok(()) => Ok(()),
        Err(primary) => {
            // Documented Happ behaviour: an unavailable main URL falls back to the backup one.
            if sub.fallback_url.is_empty() || sub.fallback_url == sub.url { return Err(primary); }
            crate::log::add(format!("Основной адрес подписки недоступен, пробуем резервный: {}", crate::log::safe(&primary)));
            let fallback = sub.fallback_url.clone();
            fetch(&mut sub, &fallback)
        }
    }
}

pub fn update_all(only_due: bool) {
    let subs = crate::store::get().subscriptions.clone();
    for sub in subs {
        let stale = sub.parsed < PARSE_VERSION;
        if only_due && !stale && sub.updated > 0 && crate::util::unix_now() - sub.updated < sub.interval_hours.max(1) * 3600 { continue; }
        if let Err(e) = update(sub.clone()) {
            let e = crate::log::safe(e);
            crate::log::add(format!("Подписка «{}»: {e}", sub.name));
            crate::store::update(|s| if let Some(x) = s.subscriptions.iter_mut().find(|x| x.id == sub.id) { x.error = e.clone(); });
        }
    }
}

fn client_id() -> String {
    let id = crate::store::get().s("clientNumericId");
    if id.len() >= 19 && id.chars().all(|c| c.is_ascii_digit()) { return id; }
    let mut b = [0u8; 8]; let _ = getrandom::getrandom(&mut b);
    let id = format!("{:019}", u64::from_le_bytes(b));
    crate::store::update(|s| s.set("clientNumericId", id.clone().into()));
    id
}

fn fetch(sub: &mut Subscription, start_url: &str) -> Result<(), String> {
    let custom = crate::store::get().s("userAgent").trim().to_string();
    let agent = if custom.is_empty() { format!("{HAPP_AGENT}/{}", client_id()) } else { custom };
    let (body, headers) = download(start_url, &agent)?;
    let mut body = body;
    let t = body.trim();
    if !t.contains("://") && !t.starts_with('{') && !t.starts_with('[') { if let Ok(d) = base64_text(t) { body = d; } }
    let mut meta: HashMap<String, String> = HashMap::new();
    for line in body.lines() {
        let l = line.trim();
        if let Some(rest) = l.strip_prefix('#') { if let Some((k, v)) = rest.split_once(':') { meta.insert(k.trim().to_ascii_lowercase(), v.trim().to_string()); } }
    }
    let parsed = crate::importer::parse(&body)?;
    sub.format = if parsed.json { "json" } else { "links" }.into();
    sub.routing_link = parsed.routing_link.clone();
    if let Some(first) = parsed.skipped.first() { crate::log::add(format!("Подписка «{}»: пропущено {} строк. {first}", sub.name, parsed.skipped.len())); }
    for key in ["profile-title", "subscription-userinfo", "profile-update-interval", "support-url", "profile-web-page-url", "announce", "routing", "routing-enable", "fallback-url", "new-url", "new-domain", "sub-info-text"] {
        let Some(mut value) = headers.get(key).or_else(|| meta.get(key)).cloned() else { continue };
        if let Some(b) = value.strip_prefix("base64:") { match base64_text(b) { Ok(d) => value = d, Err(_) => continue } }
        let value = value.trim().to_string();
        match key {
            "profile-title" => sub.name = value.chars().take(25).collect(),
            "subscription-userinfo" => sub.user_info = value,
            "profile-update-interval" => if let Ok(h) = value.parse::<i64>() { sub.interval_hours = h.clamp(1, 168) },
            "support-url" => if safe_link(&value) { sub.support_url = value },
            "profile-web-page-url" => if safe_link(&value) { sub.web_page_url = value },
            "announce" => sub.announce = value.chars().take(200).collect(),
            "routing" => if value.starts_with("happ://routing/") { sub.routing_link = value },
            "routing-enable" => sub.routing_enabled = value.is_empty() || value == "1" || value.eq_ignore_ascii_case("true"),
            "fallback-url" => if value.starts_with("https://") { sub.fallback_url = value },
            "new-url" => if value.starts_with("https://") && value != sub.url { sub.url = value; crate::log::add(format!("Провайдер сменил адрес подписки «{}».", sub.name)); },
            "new-domain" => if !value.is_empty() && value.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-') {
                if let Ok(mut u) = url::Url::parse(&sub.url) { if u.host_str() != Some(value.as_str()) && u.set_host(Some(&value)).is_ok() { sub.url = u.to_string(); crate::log::add(format!("Провайдер сменил домен подписки «{}».", sub.name)); } }
            },
            "sub-info-text" => sub.info_text = value.chars().take(200).collect(),
            _ => {}
        }
    }
    sub.updated = crate::util::unix_now(); sub.parsed = PARSE_VERSION; sub.error.clear();
    let count = parsed.profiles.len() as i64;
    crate::store::replace_subscription(sub.clone(), parsed.profiles);
    crate::log::add(format!("Подписка «{}» обновлена: {count} {}.", sub.name, crate::util::plural(count, "сервер", "сервера", "серверов")));
    Ok(())
}
fn safe_link(u: &str) -> bool { u.starts_with("https://") || u.starts_with("tg://") || u.starts_with("http://t.me/") }

fn download(start: &str, user_agent: &str) -> Result<(String, HashMap<String, String>), String> {
    let (hwid, send_hwid) = { let s = crate::store::get(); (s.s("hwid"), s.settings.get("sendHwid").and_then(|v| v.as_bool()).unwrap_or(true)) };
    let agent = ureq::AgentBuilder::new().timeout(Duration::from_secs(30)).redirects(0).build();
    let mut url = start.to_string();
    for _ in 0..6 {
        let u = url::Url::parse(&url).map_err(|_| "Некорректный адрес подписки.")?;
        if u.scheme() != "https" { return Err("Небезопасное перенаправление подписки на HTTP отклонено.".into()); }
        let mut req = agent.get(&url).set("User-Agent", user_agent).set("X-Wraith-Version", env!("CARGO_PKG_VERSION"));
        if send_hwid { req = req.set("X-Hwid", &hwid).set("X-Device-Os", "Windows").set("X-Device-Model", "PC"); }
        let response = match req.call() { Ok(r) => r, Err(ureq::Error::Status(_, r)) => r, Err(e) => return Err(crate::pinger::short_error(&e.to_string())) };
        let status = response.status();
        if (300..400).contains(&status) {
            let target = response.header("Location").ok_or("Пустое перенаправление подписки.")?;
            url = u.join(target).map_err(|_| "Некорректное перенаправление.")?.to_string(); continue;
        }
        if status != 200 { return Err(format!("Сервер подписки вернул HTTP {status}.")); }
        if response.header("encrypt-tag").is_some() { return Err("Подписка зашифрована для другого клиента. Попросите у провайдера обычную ссылку.".into()); }
        let headers: HashMap<String, String> = response.headers_names().into_iter().filter_map(|n| response.header(&n).map(|v| (n.to_ascii_lowercase(), v.to_string()))).collect();
        let mut bytes = vec![];
        response.into_reader().take(MAX_BYTES + 1).read_to_end(&mut bytes).map_err(|e| e.to_string())?;
        if bytes.len() as u64 > MAX_BYTES { return Err("Подписка больше 8 МБ.".into()); }
        return Ok((String::from_utf8_lossy(&bytes).into_owned(), headers));
    }
    Err("Слишком много перенаправлений подписки.".into())
}

/// Updates from the latest GitHub release: the Windows zip asset, verified with GitHub's SHA-256 digest.
pub mod updater {
    use crate::json::Get;
    use sha2::Digest;
    use std::io::{Read, Write};
    use std::time::Duration;

    pub const REPO: &str = "trinity13338/wraith";
    const HOSTS: &[&str] = &["api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"];

    #[derive(Clone, Default)]
    pub struct Release { pub version: String, pub url: String, pub sha256: String, pub notes: String, pub size: i64, pub available: bool }

    fn agent() -> ureq::Agent { ureq::AgentBuilder::new().timeout_connect(Duration::from_secs(15)).timeout_read(Duration::from_secs(60)).redirects(0).user_agent(&format!("Wraith/{}/Windows", env!("CARGO_PKG_VERSION"))).build() }
    fn checked(url: &str) -> Result<(), String> {
        let u = url::Url::parse(url).map_err(|_| "Некорректный адрес обновления.")?;
        if u.scheme() != "https" || !HOSTS.contains(&u.host_str().unwrap_or("")) || !u.username().is_empty() { return Err("Обновления загружаются только с GitHub по HTTPS.".into()); }
        Ok(())
    }

    pub fn check() -> Result<Release, String> {
        let url = format!("https://api.github.com/repos/{REPO}/releases/latest"); checked(&url)?;
        let body = match agent().get(&url).set("Accept", "application/vnd.github+json").call() {
            Ok(r) => r.into_string().map_err(|e| e.to_string())?,
            Err(ureq::Error::Status(404, _)) => return Err("На GitHub пока нет релизов.".into()),
            Err(ureq::Error::Status(403 | 429, _)) => return Err("GitHub временно ограничил запросы, попробуйте позже.".into()),
            Err(e) => return Err(crate::pinger::short_error(&e.to_string())),
        };
        parse(&body, env!("CARGO_PKG_VERSION"))
    }

    pub fn parse(body: &str, current: &str) -> Result<Release, String> {
        let json = crate::json::parse_obj(body)?;
        if json.b("draft", false) || json.b("prerelease", false) { return Err("Последний релиз ещё не опубликован.".into()); }
        let version = json.s("tag_name").trim().trim_start_matches(['v', 'V']).to_string();
        if version.split('.').count() < 2 || !version.split('.').all(|p| !p.is_empty() && p.chars().all(|c| c.is_ascii_digit())) { return Err(format!("Некорректная версия в релизе: {version}")); }
        let body_text = json.s("body");
        let notes = body_text.split("\n## Установка").next().unwrap_or("").lines().map(|l| l.trim_start_matches('#').trim()).collect::<Vec<_>>().join("\n").replace(['`'], "").replace("**", "").trim().to_string();
        let mut release = Release { version: version.clone(), notes: notes.chars().take(600).collect(), ..Default::default() };
        // A release without a Windows build (Android-only) is not an update for this app.
        let Some(asset) = json.objects("assets").into_iter().find(|a| { let n = a.s("name").to_lowercase(); n.ends_with(".zip") && n.contains("windows") }) else { return Ok(release) };
        let url = asset.s("browser_download_url");
        let u = url::Url::parse(&url).map_err(|_| "Некорректная ссылка на архив.")?;
        if u.scheme() != "https" || u.host_str() != Some("github.com") || !u.path().starts_with(&format!("/{REPO}/releases/download/")) { return Err(format!("Архив должен лежать в релизах {REPO}.")); }
        let digest = asset.s("digest");
        let sha = digest.strip_prefix("sha256:").ok_or("GitHub не сообщил SHA-256 архива.")?.to_ascii_uppercase();
        if sha.len() != 64 || !sha.chars().all(|c| c.is_ascii_hexdigit()) { return Err("Некорректная SHA-256 в релизе.".into()); }
        release.url = url; release.sha256 = sha; release.size = asset.i("size", 0);
        release.available = compare(&version, current) > 0;
        Ok(release)
    }

    pub fn compare(a: &str, b: &str) -> i32 {
        let parse = |s: &str| -> Vec<u64> { s.split(|c: char| !c.is_ascii_digit() && c != '.').next().unwrap_or("").split('.').map(|x| x.parse().unwrap_or(0)).collect() };
        let (x, y) = (parse(a), parse(b));
        for i in 0..x.len().max(y.len()) { let (p, q) = (x.get(i).copied().unwrap_or(0), y.get(i).copied().unwrap_or(0)); if p != q { return if p < q { -1 } else { 1 }; } }
        0
    }

    /// Downloads the zip (following GitHub's redirect to its asset storage only) and checks the digest.
    pub fn download(release: &Release, progress: impl Fn(u64, u64)) -> Result<std::path::PathBuf, String> {
        let folder = crate::store::folder().join("update"); let _ = std::fs::remove_dir_all(&folder); std::fs::create_dir_all(&folder).map_err(|e| e.to_string())?;
        let zip = folder.join(format!("Wraith-{}.zip", release.version));
        let mut url = release.url.clone();
        for _ in 0..6 {
            checked(&url)?;
            let response = match agent().get(&url).set("Accept", "application/octet-stream").call() { Ok(r) => r, Err(ureq::Error::Status(_, r)) => r, Err(e) => return Err(crate::pinger::short_error(&e.to_string())) };
            if (300..400).contains(&response.status()) { url = url::Url::parse(&url).unwrap().join(response.header("Location").unwrap_or("")).map_err(|e| e.to_string())?.to_string(); continue; }
            if response.status() != 200 { return Err(format!("GitHub вернул HTTP {}.", response.status())); }
            let total: u64 = response.header("Content-Length").and_then(|v| v.parse().ok()).unwrap_or(0);
            let mut reader = response.into_reader(); let mut file = std::fs::File::create(&zip).map_err(|e| e.to_string())?;
            let mut hasher = sha2::Sha256::new(); let mut buf = vec![0u8; 64 * 1024]; let mut done = 0u64;
            loop {
                let n = reader.read(&mut buf).map_err(|e| e.to_string())?; if n == 0 { break; }
                done += n as u64; if done > 300 * 1024 * 1024 { return Err("Архив больше 300 МБ.".into()); }
                hasher.update(&buf[..n]); file.write_all(&buf[..n]).map_err(|e| e.to_string())?; progress(done, total);
            }
            let actual: String = hasher.finalize().iter().map(|b| format!("{b:02X}")).collect();
            if actual != release.sha256 { drop(file); let _ = std::fs::remove_file(&zip); return Err("SHA-256 скачанного архива не совпадает.".into()); }
            return Ok(zip);
        }
        Err("Слишком много перенаправлений при загрузке.".into())
    }

    /// Unpacks over the app folder after Wraith exits, then starts the new version.
    pub fn install_and_restart(zip: &std::path::Path) -> Result<(), String> {
        let target = crate::util::app_dir(); let q = |p: &std::path::Path| p.to_string_lossy().replace('\'', "''");
        let script = zip.with_file_name("install.ps1");
        let ps = format!("$ErrorActionPreference = 'Stop'\r\nWait-Process -Id {pid} -Timeout 60 -ErrorAction SilentlyContinue\r\nStart-Sleep -Milliseconds 500\r\n$tmp = Join-Path $env:TEMP ('wraith-update-' + [guid]::NewGuid())\r\nExpand-Archive -LiteralPath '{zip}' -DestinationPath $tmp -Force\r\n$root = $tmp; $inner = Get-ChildItem $tmp -Directory; if ((Get-ChildItem $tmp -File).Count -eq 0 -and $inner.Count -eq 1) {{ $root = $inner[0].FullName }}\r\nCopy-Item -Path (Join-Path $root '*') -Destination '{dir}' -Recurse -Force\r\nRemove-Item $tmp -Recurse -Force\r\nStart-Process -FilePath '{exe}'\r\n",
            pid = std::process::id(), zip = q(zip), dir = q(&target), exe = q(&target.join("Wraith.exe")));
        let mut bom = vec![0xEF, 0xBB, 0xBF]; bom.extend_from_slice(ps.as_bytes());
        std::fs::write(&script, bom).map_err(|e| e.to_string())?;
        let writable = std::fs::write(target.join(".wraith-write-test"), b"").is_ok(); let _ = std::fs::remove_file(target.join(".wraith-write-test"));
        let args = format!("-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File \"{}\"", script.to_string_lossy());
        if crate::util::shell_execute("powershell.exe", &args, if writable { Some("open") } else { Some("runas") }) { Ok(()) } else { Err("Установка отменена.".into()) }
    }
}
