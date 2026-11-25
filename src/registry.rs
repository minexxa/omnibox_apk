// registry.rs —— 脚本注册表：上传 / URL 拉取 / 启停 / 删除 / 持久化
// 存储于 data/spiders/ 目录：registry.json + <id>.js

use anyhow::{anyhow, Context as _, Result};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{OnceLock, RwLock};
use std::time::Duration;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SpiderMeta {
    pub id: String,
    pub name: String,
    /// "upload" | "url"
    pub origin: String,
    /// URL 来源（origin=url 时有效）
    #[serde(default)]
    pub url: String,
    /// 脚本文件名（相对 data/spiders/）
    pub file: String,
    pub enabled: bool,
    pub added_at: u64,
    #[serde(default)]
    pub last_ok: Option<u64>,
    #[serde(default)]
    pub last_error: Option<String>,
    /// 从脚本 // @name 注释解析出的展示名
    #[serde(default)]
    pub display_name: String,
    // 旧版 registry.json 里的 runtime 字段会被 serde 忽略（现在统一使用内嵌 QuickJS）
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct RegistryFile {
    spiders: Vec<SpiderMeta>,
}

static REGISTRY: OnceLock<RwLock<HashMap<String, SpiderMeta>>> = OnceLock::new();

fn data_dir() -> PathBuf {
    std::env::var("DATA_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("data"))
        .join("spiders")
}

fn dir() -> PathBuf {
    let d = data_dir();
    let _ = std::fs::create_dir_all(&d);
    d
}

fn reg_path() -> PathBuf {
    dir().join("registry.json")
}

fn map() -> &'static RwLock<HashMap<String, SpiderMeta>> {
    REGISTRY.get_or_init(|| {
        let mut m = HashMap::new();
        if let Ok(text) = std::fs::read_to_string(reg_path()) {
            if let Ok(f) = serde_json::from_str::<RegistryFile>(&text) {
                for s in f.spiders {
                    m.insert(s.id.clone(), s);
                }
            }
        }
        RwLock::new(m)
    })
}

fn flush_locked(m: &HashMap<String, SpiderMeta>) -> Result<()> {
    let mut spiders: Vec<SpiderMeta> = m.values().cloned().collect();
    spiders.sort_by_key(|s| s.added_at);
    let f = RegistryFile { spiders };
    std::fs::write(reg_path(), serde_json::to_string_pretty(&f)?)?;
    Ok(())
}

fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// 从脚本头部注释解析 // @name
fn parse_display_name(source: &str) -> String {
    for line in source.lines().take(40) {
        let line = line.trim();
        if let Some(rest) = line.strip_prefix("// @name") {
            let name = rest.trim();
            if !name.is_empty() {
                return name.to_string();
            }
        }
    }
    String::new()
}

fn safe_id(name: &str) -> String {
    let base: String = name
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '-' || c == '_' {
                c
            } else {
                '-'
            }
        })
        .collect();
    let base = base.trim_matches('-').to_lowercase();
    if base.is_empty() {
        format!("spider-{}", now_secs())
    } else {
        base
    }
}

fn unique_id(desired: &str, m: &HashMap<String, SpiderMeta>) -> String {
    let mut id = desired.to_string();
    let mut i = 2;
    while m.contains_key(&id) {
        id = format!("{}-{}", desired, i);
        i += 1;
    }
    id
}

pub fn list() -> Vec<SpiderMeta> {
    let m = map().read().unwrap();
    let mut v: Vec<SpiderMeta> = m.values().cloned().collect();
    v.sort_by_key(|s| s.added_at);
    v
}

pub fn get(id: &str) -> Option<SpiderMeta> {
    map().read().unwrap().get(id).cloned()
}

pub fn source_path(id: &str) -> Option<PathBuf> {
    let meta = get(id)?;
    Some(dir().join(&meta.file))
}

pub fn read_source(id: &str) -> Result<String> {
    let path = source_path(id).ok_or_else(|| anyhow!("脚本不存在: {id}"))?;
    Ok(std::fs::read_to_string(path)?)
}

/// 上传脚本：name 为文件名，source 为内容
pub fn add_upload(name: &str, source: &str) -> Result<SpiderMeta> {
    if source.len() > 500 * 1024 {
        return Err(anyhow!("脚本超过 500KB 限制"));
    }
    let display = parse_display_name(source);
    let base = safe_id(name.trim_end_matches(".js"));
    let id = {
        let m = map().read().unwrap();
        unique_id(&base, &m)
    };
    let file = format!("{id}.js");
    std::fs::write(dir().join(&file), source)?;
    let meta = SpiderMeta {
        id: id.clone(),
        name: name.to_string(),
        origin: "upload".into(),
        url: String::new(),
        file,
        enabled: true,
        added_at: now_secs(),
        last_ok: None,
        last_error: None,
        display_name: display,
    };
    {
        let mut m = map().write().unwrap();
        m.insert(id.clone(), meta.clone());
        flush_locked(&m)?;
    }
    Ok(meta)
}

/// 从 URL 拉取脚本（校验协议/禁内网/限大小/超时）
pub async fn add_from_url(url: &str) -> Result<SpiderMeta> {
    let parsed = reqwest::Url::parse(url).context("URL 格式错误")?;
    match parsed.scheme() {
        "http" | "https" => {}
        _ => return Err(anyhow!("仅支持 http/https")),
    }
    if let Some(host) = parsed.host_str() {
        if is_private_host(host) {
            return Err(anyhow!("禁止访问内网地址"));
        }
    }
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(15))
        .redirect(reqwest::redirect::Policy::limited(3))
        .danger_accept_invalid_certs(true)
        .build()?;
    let resp = client
        .get(url)
        .header("User-Agent", "Mozilla/5.0")
        .send()
        .await
        .context("拉取脚本失败")?;
    if !resp.status().is_success() {
        return Err(anyhow!("HTTP {}", resp.status()));
    }
    let source = resp.text().await.context("读取脚本内容失败")?;
    if source.len() > 500 * 1024 {
        return Err(anyhow!("脚本超过 500KB 限制"));
    }
    let display = parse_display_name(&source);
    let base = safe_id(
        parsed
            .path_segments()
            .and_then(|s| s.last())
            .unwrap_or("spider")
            .trim_end_matches(".js"),
    );
    let id = {
        let m = map().read().unwrap();
        unique_id(&base, &m)
    };
    let file = format!("{id}.js");
    std::fs::write(dir().join(&file), &source)?;
    let meta = SpiderMeta {
        id: id.clone(),
        name: if display.is_empty() { url.to_string() } else { display.clone() },
        origin: "url".into(),
        url: url.to_string(),
        file,
        enabled: true,
        added_at: now_secs(),
        last_ok: None,
        last_error: None,
        display_name: display,
    };
    {
        let mut m = map().write().unwrap();
        m.insert(id.clone(), meta.clone());
        flush_locked(&m)?;
    }
    Ok(meta)
}

pub fn toggle(id: &str, enabled: bool) -> Result<SpiderMeta> {
    let mut m = map().write().unwrap();
    let meta = m.get_mut(id).ok_or_else(|| anyhow!("脚本不存在: {id}"))?;
    meta.enabled = enabled;
    let out = meta.clone();
    flush_locked(&m)?;
    Ok(out)
}

pub fn remove(id: &str) -> Result<()> {
    let meta = get(id).ok_or_else(|| anyhow!("脚本不存在: {id}"))?;
    let _ = std::fs::remove_file(dir().join(&meta.file));
    let mut m = map().write().unwrap();
    m.remove(id);
    flush_locked(&m)?;
    Ok(())
}

pub fn mark_result(id: &str, ok: bool, error: Option<String>) {
    let mut m = map().write().unwrap();
    if let Some(meta) = m.get_mut(id) {
        if ok {
            meta.last_ok = Some(now_secs());
            meta.last_error = None;
        } else {
            meta.last_error = error.map(|e| e.chars().take(300).collect());
        }
    }
    let _ = flush_locked(&m);
}

pub fn enabled() -> Vec<SpiderMeta> {
    list().into_iter().filter(|s| s.enabled).collect()
}

pub fn is_private_host(host: &str) -> bool {
    let h = host.to_lowercase();
    if h == "localhost" || h.ends_with(".localhost") || h.ends_with(".local") {
        return true;
    }
    let ip: std::net::IpAddr = match h.parse() {
        Ok(ip) => ip,
        Err(_) => return false, // 域名由 DNS 解析，request 层再拦一次
    };
    match ip {
        std::net::IpAddr::V4(v) => {
            v.is_private() || v.is_loopback() || v.is_link_local() || v.is_unspecified()
                || v.octets()[0] == 169
        }
        std::net::IpAddr::V6(v) => v.is_loopback() || v.is_unspecified(),
    }
}

/// 供测试/管理页使用：检查目录
pub fn debug_dir() -> PathBuf {
    dir()
}

/// 检查路径是否在数据目录内
pub fn _path_in_data(p: &Path) -> bool {
    p.starts_with(data_dir())
}
