// img.rs —— 图片取字节（供 Flutter 通过 FFI 获取，带防盗链 Referer）
// 无 axum 依赖：ob_img 调用 img::fetch() 拿到 {bytes, content_type}，
// Rust 侧写回调用方提供的缓冲区指针；Dart 侧用 Image.memory 显示。

use crate::registry;
use anyhow::{anyhow, Result};
use std::sync::OnceLock;
use std::time::Duration;

const PLAY_UA: &str = "Lavf/57.83.100";

fn client() -> &'static reqwest::Client {
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .timeout(Duration::from_secs(20))
            .danger_accept_invalid_certs(true)
            .build()
            .expect("构建图片客户端失败")
    })
}

/// 取图片字节。url 必须是公网 http(s)；豆瓣图片自动带 m.douban.com Referer。
pub async fn fetch(url: &str) -> Result<ImgResult> {
    if !url.starts_with("http://") && !url.starts_with("https://") {
        return Err(anyhow!("图片 URL 必须是 http(s)"));
    }
    // 内网拦截，防止脚本借图片接口探测内网
    if let Ok(parsed) = reqwest::Url::parse(url) {
        if let Some(host) = parsed.host_str() {
            if registry::is_private_host(host) {
                return Err(anyhow!("禁止访问内网地址"));
            }
        }
    }
    let referer = if url.contains("doubanio") || url.contains("douban.com") {
        "https://m.douban.com"
    } else {
        url
    };
    let resp = client()
        .get(url)
        .header("Referer", referer)
        .header("User-Agent", PLAY_UA)
        .send()
        .await
        .map_err(|e| anyhow!("图片请求失败: {e}"))?;
    if !resp.status().is_success() {
        return Err(anyhow!("图片 HTTP {}", resp.status().as_u16()));
    }
    let content_type = resp
        .headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("image/jpeg")
        .to_string();
    let bytes = resp.bytes().await.map_err(|e| anyhow!("读取图片失败: {e}"))?;
    // 限制 8MB，防止超大图
    if bytes.len() > 8 * 1024 * 1024 {
        return Err(anyhow!("图片过大"));
    }
    Ok(ImgResult {
        bytes: bytes.to_vec(),
        content_type,
    })
}

pub struct ImgResult {
    pub bytes: Vec<u8>,
    pub content_type: String,
}
