// vod.rs —— 脚本调度 + 数据归一化
// 影视源不再是 Rust 内置实现，而是 registry 中登记的 OmniBox 格式 JS 脚本，
// 由 spider.rs 的 QuickJS 运行时执行，本模块统一归一化输出给前端。

use crate::{registry, spider};
use base64::Engine as _;
use serde_json::{json, Value};
use std::time::Duration;

fn b64_encode(data: &[u8]) -> String {
    base64::engine::general_purpose::STANDARD.encode(data)
}
fn b64_decode(text: &str) -> anyhow::Result<Vec<u8>> {
    Ok(base64::engine::general_purpose::STANDARD.decode(text)?)
}

fn label(src: &str) -> String {
    match registry::get(src) {
        Some(m) if !m.display_name.is_empty() => m.display_name,
        Some(m) if !m.name.is_empty() => m.name,
        _ => src.to_string(),
    }
}

/// 调用脚本方法（统一走内嵌 QuickJS）。
/// src 为脚本 id；method 为 home/category/search/detail/play。
pub async fn dispatch(src: &str, method: &str, params: serde_json::Value) -> anyhow::Result<Value> {
    spider::call_script(src, method, params).await
}

/// 归一化搜索/列表结果
fn normalize_items(raw: &Value, src: &str) -> Vec<Value> {
    let arr = match raw.get("list").and_then(|v| v.as_array()) {
        Some(a) => a,
        None => return vec![],
    };
    arr.iter()
        .filter_map(|i| {
            let id = match i.get("vod_id") {
                Some(Value::String(s)) => s.clone(),
                Some(Value::Number(n)) => n.to_string(),
                _ => return None,
            };
            if id.is_empty() || id == "0" {
                return None;
            }
            let name = i.get("vod_name").and_then(Value::as_str).unwrap_or("未知标题");
            let pic = i.get("vod_pic").and_then(Value::as_str).unwrap_or("");
            let remarks = i
                .get("vod_remarks")
                .and_then(Value::as_str)
                .or_else(|| i.get("vod_year").and_then(Value::as_str))
                .unwrap_or("");
            Some(json!({
                "vod_id": id,
                "vod_name": name,
                "vod_pic": pic,
                "vod_remarks": remarks,
                "source": src,
            }))
        })
        .collect()
}

/// 归一化详情：把 vod_play_sources 的所有线路拍平成 episodes
fn normalize_detail(raw: &Value, src: &str, id: &str) -> anyhow::Result<Value> {
    let item = raw
        .get("list")
        .and_then(|v| v.as_array())
        .and_then(|a| a.first())
        .cloned()
        .ok_or_else(|| anyhow::anyhow!("详情为空"))?;

    let mut episodes = Vec::new();
    if let Some(sources) = item.get("vod_play_sources").and_then(Value::as_array) {
        for line in sources {
            let line_name = line.get("name").and_then(Value::as_str).unwrap_or("");
            if let Some(eps) = line.get("episodes").and_then(Value::as_array) {
                for ep in eps {
                    let play_id = ep
                        .get("playId")
                        .and_then(Value::as_str)
                        .or_else(|| ep.get("play_id").and_then(Value::as_str))
                        .unwrap_or("");
                    if play_id.is_empty() {
                        continue;
                    }
                    let ep_name = ep.get("name").and_then(Value::as_str).unwrap_or("播放");
                    let name = if line_name.is_empty() {
                        ep_name.to_string()
                    } else {
                        format!("【{line_name}】{ep_name}")
                    };
                    let meta = json!({"playId": play_id, "flag": src});
                    let q = b64_encode(meta.to_string().as_bytes());
                    episodes.push(json!({"name": name, "q": q, "res": []}));
                }
            }
        }
    }
    if episodes.is_empty() {
        if let Some(url) = item.get("vod_play_url").and_then(Value::as_str) {
            for (idx, seg) in url.split('#').enumerate() {
                let mut it = seg.splitn(2, '$');
                let ep_name = it.next().unwrap_or("").to_string();
                let play_id = it.next().unwrap_or("");
                if play_id.is_empty() {
                    continue;
                }
                let meta = json!({"playId": play_id, "flag": src});
                episodes.push(json!({
                    "name": if ep_name.is_empty() { format!("第{}集", idx + 1) } else { ep_name },
                    "q": b64_encode(meta.to_string().as_bytes()),
                    "res": [],
                }));
            }
        }
    }
    if episodes.is_empty() {
        anyhow::bail!("脚本详情无可用剧集");
    }

    Ok(json!({
        "vod": {
            "vod_id": id,
            "vod_name": item.get("vod_name").and_then(Value::as_str).unwrap_or(""),
            "vod_pic": item.get("vod_pic").and_then(Value::as_str).unwrap_or(""),
            "vod_year": item.get("vod_year").and_then(Value::as_str).unwrap_or(""),
            "vod_area": item.get("vod_area").and_then(Value::as_str).unwrap_or(""),
            "vod_actor": item.get("vod_actor").and_then(Value::as_str).unwrap_or(""),
            "vod_director": item.get("vod_director").and_then(Value::as_str).unwrap_or(""),
            "vod_content": item.get("vod_content").and_then(Value::as_str).unwrap_or(""),
        },
        "episodes": episodes,
        "source": src,
    }))
}

/// 归一化播放结果
fn normalize_play(raw: &Value) -> anyhow::Result<Value> {
    let urls = raw.get("urls").and_then(Value::as_array);
    let url = urls
        .and_then(|a| a.first())
        .and_then(|u| u.get("url").and_then(Value::as_str))
        .or_else(|| raw.get("url").and_then(Value::as_str))
        .unwrap_or("");
    if url.is_empty() {
        anyhow::bail!("脚本未返回播放地址");
    }
    let header = raw.get("header").cloned().unwrap_or_else(|| json!({}));
    let parse = raw.get("parse").and_then(Value::as_i64).unwrap_or(0);
    Ok(json!({"url": url, "header": header, "parse": parse}))
}

/// 聚合搜索：src=all 并发所有启用脚本；否则单脚本
pub async fn unified_search(src: &str, wd: &str, page: i64) -> Value {
    let targets: Vec<registry::SpiderMeta> = if src == "all" {
        registry::enabled()
    } else {
        match registry::get(src) {
            Some(m) if m.enabled => vec![m],
            Some(_) => {
                return json!({"ok": false, "error": format!("脚本 {} 已禁用", label(src)), "list": []});
            }
            None => {
                return json!({"ok": false, "error": format!("未找到脚本: {src}（请先在脚本管理页导入）"), "list": []});
            }
        }
    };

    if targets.is_empty() {
        return json!({
            "ok": false,
            "error": "尚未导入任何影视脚本，请访问 /spiders.html 导入或拉取脚本",
            "list": []
        });
    }

    let mut futures = Vec::new();
    for s in &targets {
        let id = s.id.clone();
        let params = json!({"keyword": wd, "wd": wd, "page": page});
        futures.push(async move {
            let res = tokio::time::timeout(
                Duration::from_secs(35),
                dispatch(&id, "search", params),
            )
            .await;
            (id, res)
        });
    }
    let results = futures::future::join_all(futures).await;

    let mut merged = Vec::new();
    let mut errors = Vec::new();
    for (id, res) in results {
        match res {
            Ok(Ok(raw)) => {
                merged.extend(normalize_items(&raw, &id));
            }
            Ok(Err(e)) => {
                registry::mark_result(&id, false, Some(e.to_string()));
                errors.push(format!("{}: {}", label(&id), e));
            }
            Err(_) => {
                registry::mark_result(&id, false, Some("超时".into()));
                errors.push(format!("{}: 超时", label(&id)));
            }
        }
    }

    let pagecount = (merged.len() / 20 + 1).max(1);
    json!({
        "ok": true,
        "list": merged,
        "page": page,
        "pagecount": pagecount,
        "errors": errors,
    })
}

pub async fn detail(src: &str, id: &str) -> Value {
    if registry::get(src).is_none() {
        return json!({"error": format!("未知脚本: {src}")});
    }
    let params = json!({"videoId": id, "id": id});
    let res = tokio::time::timeout(
        Duration::from_secs(35),
        dispatch(src, "detail", params),
    )
    .await;
    match res {
        Ok(Ok(raw)) => match normalize_detail(&raw, src, id) {
            Ok(v) => {
                registry::mark_result(src, true, None);
                v
            }
            Err(e) => {
                registry::mark_result(src, false, Some(e.to_string()));
                json!({"error": e.to_string()})
            }
        },
        Ok(Err(e)) => {
            registry::mark_result(src, false, Some(e.to_string()));
            json!({"error": format!("详情失败: {e}")})
        }
        Err(_) => json!({"error": "详情超时"}),
    }
}

pub async fn play(src: &str, q: &str) -> Value {
    let meta: Value = match b64_decode(q)
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
    {
        Some(v) => v,
        None => return json!({"error": "播放参数解析失败"}),
    };
    let play_id = meta.get("playId").and_then(Value::as_str).unwrap_or("");
    let flag = meta
        .get("flag")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .unwrap_or(src);
    if play_id.is_empty() {
        return json!({"error": "缺少 playId"});
    }
    let params = json!({"playId": play_id, "flag": flag});
    let res = tokio::time::timeout(
        Duration::from_secs(35),
        dispatch(src, "play", params),
    )
    .await;
    match res {
        Ok(Ok(raw)) => match normalize_play(&raw) {
            Ok(v) => v,
            Err(e) => json!({"error": e.to_string()}),
        },
        Ok(Err(e)) => json!({"error": format!("播放解析失败: {e}")}),
        Err(_) => json!({"error": "播放解析超时"}),
    }
}

