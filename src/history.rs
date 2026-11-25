// history.rs —— 播放历史：持久化到 <DATA_DIR>/history.json
// 与脚本注册表同级目录，随数据目录一起迁移。按 src+vod_id 去重，保留最近播放，
// 最多保留 200 条，避免无限增长。

use anyhow::{Context as _, Result};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::sync::{OnceLock, RwLock};

const MAX_ITEMS: usize = 200;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HistoryItem {
    /// 去重键：src + "|" + vod_id
    pub key: String,
    pub src: String,
    pub vod_id: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub pic: String,
    /// 播放到第几集（下标）
    #[serde(default)]
    pub ep_index: usize,
    #[serde(default)]
    pub ep_name: String,
    /// 最近播放时间戳（秒）
    pub played_at: u64,
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct HistoryFile {
    items: Vec<HistoryItem>,
}

static STORE: OnceLock<RwLock<HistoryFile>> = OnceLock::new();

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

fn file_path() -> PathBuf {
    dir().join("history.json")
}

fn store() -> &'static RwLock<HistoryFile> {
    STORE.get_or_init(|| {
        let mut f = HistoryFile::default();
        if let Ok(text) = std::fs::read_to_string(file_path()) {
            if let Ok(parsed) = serde_json::from_str::<HistoryFile>(&text) {
                f = parsed;
            }
        }
        RwLock::new(f)
    })
}

fn flush(items: &[HistoryItem]) -> Result<()> {
    let f = HistoryFile { items: items.to_vec() };
    let text = serde_json::to_string_pretty(&f)?;
    std::fs::write(file_path(), text).context("写入播放历史失败")?;
    Ok(())
}

fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// 全部历史（最近播放在前）
pub fn list() -> Vec<HistoryItem> {
    store().read().unwrap().items.clone()
}

/// 记录一次播放：同 src+vod_id 去重并前移，保留最新集数与时间
pub fn add(src: &str, vod_id: &str, name: &str, pic: &str, ep_index: usize, ep_name: &str) -> Result<Vec<HistoryItem>> {
    let key = format!("{}|{}", src, vod_id);
    let mut g = store().write().unwrap();
    g.items.retain(|x| x.key != key);
    g.items.insert(0, HistoryItem {
        key,
        src: src.to_string(),
        vod_id: vod_id.to_string(),
        name: name.to_string(),
        pic: pic.to_string(),
        ep_index,
        ep_name: ep_name.to_string(),
        played_at: now_secs(),
    });
    if g.items.len() > MAX_ITEMS {
        g.items.truncate(MAX_ITEMS);
    }
    flush(&g.items)?;
    Ok(g.items.clone())
}

/// 删除单条（按 key）
pub fn remove(key: &str) -> Result<Vec<HistoryItem>> {
    let mut g = store().write().unwrap();
    g.items.retain(|x| x.key != key);
    flush(&g.items)?;
    Ok(g.items.clone())
}

/// 清空历史
pub fn clear() -> Result<()> {
    let mut g = store().write().unwrap();
    g.items.clear();
    flush(&g.items)
}
