// lib.rs —— OmniBox Rust 内核 FFI 入口（cdylib，Android .so / 桌面 dll 通用）
// Android 由 jni_bridge.rs 适配给 Kotlin（OmniBoxNative），其他平台走 C ABI。所有业务逻辑为 async，FFI 边界用常驻
// tokio runtime + block_on 同步化；返回值是 JSON 字符串指针，调用方用完必须 ob_free。
//
// 导出函数：
//   ob_init(data_dir, admin_token) -> i32   初始化（必须在其他调用前，仅一次生效）
//   ob_hello() -> *mut c_char              连通性测试，返回 JSON
//   ob_call(method, params_json) -> *mut c_char  通用 RPC，返回 JSON
//   ob_free(ptr)                           释放 ob_* 返回的字符串
//   ob_free_bytes(ptr, len)                释放 ob_img 返回的字节

// 纯 Rust 内核：脚本统一走内嵌 QuickJS（原 Node.js 运行时已移除）。
// 加密（AES/RSA/哈希）与 DOM（cheerio）兼容层由 jscrypto.rs / jsdom.rs 提供。
mod cache;
mod douban;
mod history;
mod img;
#[cfg(target_os = "android")]
mod jni_bridge;
mod jscrypto;
mod jsdom;
mod registry;
mod spider;
mod vod;

use serde_json::{json, Value};
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int, c_uchar};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::OnceLock;
use tokio::runtime::{Builder, Runtime};

/// 常驻多线程 runtime：FFI 同步调用 -> block_on(async 业务)
static RT: OnceLock<Runtime> = OnceLock::new();

fn runtime() -> &'static Runtime {
    RT.get_or_init(|| {
        Builder::new_multi_thread()
            .worker_threads(4)
            .enable_all()
            .build()
            .expect("创建 tokio runtime 失败")
    })
}

/// 安全读 C 字符串指针；空指针返回空串
unsafe fn cstr(p: *const c_char) -> String {
    if p.is_null() {
        return String::new();
    }
    CStr::from_ptr(p).to_string_lossy().into_owned()
}

/// 把 Rust String 转成需由 C 侧 ob_free 释放的指针；内嵌 NUL 会被 CString 拒绝，做替换兜底
fn to_cptr(s: String) -> *mut c_char {
    let safe = s.replace('\0', "");
    CString::new(safe)
        .unwrap_or_else(|_| CString::new("{\"ok\":false,\"error\":\"结果含 NUL\"}").unwrap())
        .into_raw()
}

/// Rust panic 不跨 FFI 边界：统一转成 JSON 错误
fn guard<F>(f: F) -> *mut c_char
where
    F: FnOnce() -> Value,
{
    let v = match catch_unwind(AssertUnwindSafe(f)) {
        Ok(v) => v,
        Err(_) => json!({"ok": false, "error": "内核发生内部错误(panic)"}),
    };
    to_cptr(v.to_string())
}

// ===== 导出 API =====

/// 初始化：设置数据目录与管理员口令。必须在其他调用之前调用一次。
/// data_dir 为空时用 "./data"；admin_token 为空表示不校验。
#[no_mangle]
pub extern "C" fn ob_init(data_dir: *const c_char, admin_token: *const c_char) -> c_int {
    let d = unsafe { cstr(data_dir) };
    if !d.trim().is_empty() {
        std::env::set_var("DATA_DIR", d);
    }
    let t = unsafe { cstr(admin_token) };
    if !t.is_empty() {
        std::env::set_var("ADMIN_TOKEN", t);
    }
    // 预热数据目录，尽早暴露权限/路径问题
    let _ = registry::debug_dir();
    // 初始化 runtime
    let _ = runtime();
    1
}

/// 连通性测试
#[no_mangle]
pub extern "C" fn ob_hello() -> *mut c_char {
    guard(|| json!({"ok": true, "engine": "omnibox_core", "version": env!("CARGO_PKG_VERSION")}))
}

/// 通用 RPC：method + params(JSON 字符串) -> JSON 字符串指针
#[no_mangle]
pub extern "C" fn ob_call(method: *const c_char, params_json: *const c_char) -> *mut c_char {
    let method = unsafe { cstr(method) };
    let pj = unsafe { cstr(params_json) };
    let params: Value = serde_json::from_str(&pj).unwrap_or_else(|_| json!({}));

    guard(move || {
        let rt = runtime();
        match method.as_str() {
            "health" => json!({
                "ok": true,
                "engine": "quickjs",
                "engines": {"quickjs": true},
                "data_dir": registry::debug_dir().to_string_lossy()
            }),

            // ---- 豆瓣首页/分类 ----
            "douban_home" => rt.block_on(douban::home_inner()),
            "douban_category" => {
                let cat = params.get("cat").and_then(Value::as_str).unwrap_or("movie");
                let page = params.get("page").and_then(Value::as_i64).unwrap_or(1);
                match rt.block_on(douban::category_inner(cat, page, &json!({}))) {
                    Ok(v) => v,
                    Err(e) => json!({"ok": false, "error": e.to_string(), "list": []}),
                }
            }

            // ---- 影视搜索/详情/播放 ----
            "search" => {
                let src = params.get("src").and_then(Value::as_str).unwrap_or("all");
                let wd = params.get("wd").and_then(Value::as_str).unwrap_or("");
                let page = params.get("page").and_then(Value::as_i64).unwrap_or(1);
                if wd.trim().is_empty() {
                    json!({"ok": false, "error": "关键词不能为空", "list": []})
                } else {
                    rt.block_on(vod::unified_search(src, wd, page))
                }
            }
            "detail" => {
                let src = params.get("src").and_then(Value::as_str).unwrap_or("");
                let id = params.get("id").and_then(Value::as_str).unwrap_or("");
                if id.is_empty() { json!({"error": "id 不能为空"}) }
                else { rt.block_on(vod::detail(src, id)) }
            }
            "play" => {
                let src = params.get("src").and_then(Value::as_str).unwrap_or("");
                let q = params.get("q").and_then(Value::as_str).unwrap_or("");
                if q.is_empty() { json!({"error": "q 不能为空"}) }
                else { rt.block_on(vod::play(src, q)) }
            }

            // ---- 脚本管理 ----
            "spiders_list" => json!({"ok": true, "list": registry::list()}),
            "spiders_add" => {
                let name = params.get("name").and_then(Value::as_str).unwrap_or("pasted.js");
                let source = params.get("source").and_then(Value::as_str).unwrap_or("");
                if source.trim().is_empty() {
                    json!({"ok": false, "error": "源码不能为空"})
                } else {
                    match registry::add_upload(name, source) {
                        Ok(m) => json!({"ok": true, "spider": m}),
                        Err(e) => json!({"ok": false, "error": e.to_string()}),
                    }
                }
            }
            // 从 URL 远程拉取脚本（registry::add_from_url：校验协议/禁内网/限大小/超时）
            "spiders_add_url" => {
                let url = params.get("url").and_then(Value::as_str).unwrap_or("");
                if url.trim().is_empty() {
                    json!({"ok": false, "error": "URL 不能为空"})
                } else {
                    match rt.block_on(registry::add_from_url(url.trim())) {
                        Ok(m) => json!({"ok": true, "spider": m}),
                        Err(e) => json!({"ok": false, "error": e.to_string()}),
                    }
                }
            }
            "spiders_toggle" => {
                let id = params.get("id").and_then(Value::as_str).unwrap_or("");
                let on = params.get("enabled").and_then(Value::as_bool).unwrap_or(true);
                match registry::toggle(id, on) {
                    Ok(m) => json!({"ok": true, "spider": m}),
                    Err(e) => json!({"ok": false, "error": e.to_string()}),
                }
            }
            "spiders_remove" => {
                let id = params.get("id").and_then(Value::as_str).unwrap_or("");
                match registry::remove(id) {
                    Ok(_) => json!({"ok": true}),
                    Err(e) => json!({"ok": false, "error": e.to_string()}),
                }
            }
            "spiders_source" => {
                let id = params.get("id").and_then(Value::as_str).unwrap_or("");
                match registry::read_source(id) {
                    Ok(s) => json!({"ok": true, "source": s}),
                    Err(e) => json!({"ok": false, "error": e.to_string()}),
                }
            }
            "spiders_test" => {
                let id = params.get("id").and_then(Value::as_str).unwrap_or("");
                let kw = params.get("kw").and_then(Value::as_str).unwrap_or("测试");
                let p = json!({"keyword": kw, "wd": kw, "page": 1});
                match rt.block_on(vod::dispatch(id, "search", p)) {
                    Ok(v) => json!({"ok": true, "result": v}),
                    Err(e) => json!({"ok": false, "error": e.to_string()}),
                }
            }

            // ---- 播放历史 ----
            "history_list" => json!({"ok": true, "list": history::list()}),
            "history_add" => {
                let src = params.get("src").and_then(Value::as_str).unwrap_or("");
                let id = params.get("id").and_then(Value::as_str).unwrap_or("");
                if src.is_empty() || id.is_empty() {
                    json!({"ok": false, "error": "src/id 不能为空"})
                } else {
                    let name = params.get("name").and_then(Value::as_str).unwrap_or("");
                    let pic = params.get("pic").and_then(Value::as_str).unwrap_or("");
                    let ep = params.get("ep").and_then(Value::as_u64).unwrap_or(0) as usize;
                    let epname = params.get("epname").and_then(Value::as_str).unwrap_or("");
                    match history::add(src, id, name, pic, ep, epname) {
                        Ok(l) => json!({"ok": true, "list": l}),
                        Err(e) => json!({"ok": false, "error": e.to_string()}),
                    }
                }
            }
            "history_remove" => {
                let key = params.get("key").and_then(Value::as_str).unwrap_or("");
                match history::remove(key) {
                    Ok(l) => json!({"ok": true, "list": l}),
                    Err(e) => json!({"ok": false, "error": e.to_string()}),
                }
            }
            "history_clear" => match history::clear() {
                Ok(_) => json!({"ok": true}),
                Err(e) => json!({"ok": false, "error": e.to_string()}),
            },

            _ => json!({"ok": false, "error": format!("未知方法: {method}")}),
        }
    })
}

/// 释放 ob_* 返回的字符串
#[no_mangle]
pub unsafe extern "C" fn ob_free(ptr: *mut c_char) {
    if !ptr.is_null() {
        drop(CString::from_raw(ptr));
    }
}

/// 取图片字节：成功返回字节指针，并通过 out_len/out_ctype 回写长度与 MIME；
/// 失败返回 null 指针，out_ctype 写入错误信息。调用方用 ob_free_bytes / ob_free 释放。
#[no_mangle]
pub extern "C" fn ob_img(
    url: *const c_char,
    out_len: *mut usize,
    out_ctype: *mut *mut c_char,
) -> *mut c_uchar {
    let url = unsafe { cstr(url) };
    let result = guard(move || {
        match runtime().block_on(img::fetch(&url)) {
            Ok(r) => json!({"ok": true, "len": r.bytes.len(), "content_type": r.content_type, "bytes": r.bytes}),
            Err(e) => json!({"ok": false, "error": e.to_string()}),
        }
    });
    // result 是 JSON 字符串指针，解析出 bytes 数组再拷贝成裸指针（避免跨 FFI 传 Vec）
    let json_str = unsafe { CStr::from_ptr(result).to_string_lossy().into_owned() };
    unsafe { ob_free(result) };
    let v: Value = serde_json::from_str(&json_str).unwrap_or(json!({"ok": false}));
    if v.get("ok").and_then(Value::as_bool) != Some(true) {
        let err = v.get("error").and_then(Value::as_str).unwrap_or("图片获取失败");
        unsafe {
            if !out_len.is_null() { *out_len = 0; }
            if !out_ctype.is_null() { *out_ctype = CString::new(err).unwrap().into_raw(); }
        }
        return std::ptr::null_mut();
    }
    let bytes: Vec<u8> = v
        .get("bytes")
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(|n| n.as_u64().map(|x| x as u8)).collect())
        .unwrap_or_default();
    let ctype = v.get("content_type").and_then(Value::as_str).unwrap_or("image/jpeg").to_string();
    let len = bytes.len();
    let mut boxed = bytes.into_boxed_slice();
    let ptr = boxed.as_mut_ptr();
    std::mem::forget(boxed);
    unsafe {
        if !out_len.is_null() { *out_len = len; }
        if !out_ctype.is_null() { *out_ctype = CString::new(ctype).unwrap().into_raw(); }
    }
    ptr
}

/// 释放 ob_img 返回的字节
#[no_mangle]
pub unsafe extern "C" fn ob_free_bytes(ptr: *mut c_uchar, len: usize) {
    if !ptr.is_null() {
        drop(Vec::from_raw_parts(ptr, len, len));
    }
}
