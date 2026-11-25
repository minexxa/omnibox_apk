// spider.rs —— QuickJS 运行时 + OmniBox SDK 兼容层
// 每个脚本调用创建独立 AsyncRuntime，注入 CommonJS 环境与 SDK polyfill，
// 通过 Rust 原语（__log / __request / __b64*）桥接网络与工具能力。

use crate::registry;
use anyhow::{Context as _, Result};
use rquickjs::prelude::*;
use rquickjs::{AsyncContext, AsyncRuntime, CatchResultExt, Ctx, Promise};
use serde_json::Value as JsonValue;
use std::sync::Arc;
use std::time::{Duration, Instant};

const CRYPTO_JS_SHIM: &str = include_str!("../static/crypto-js-shim.js");
const CHEERIO_SHIM: &str = include_str!("../static/cheerio-shim.js");
const NODE_COMPAT: &str = include_str!("../static/node-compat.js");
const MEMORY_LIMIT: usize = 64 * 1024 * 1024;

/// JS 侧 polyfill：CommonJS + OmniBox SDK + Buffer + axios + URL
/// 所有能力最终落到 Rust 注入的 __log / __request / __b64* 原语上。
const POLYFILL: &str = r#"
var __mods = {};

// ---- omnibox_sdk ----
__mods["omnibox_sdk"] = {
  log: function(level, msg){
    try { __log(String(level), String(msg)); } catch(e) {}
  },
  request: async function(url, opts){
    var o = opts || {};
    var r = await __request(String(url), JSON.stringify(o));
    var parsed = JSON.parse(r);
    if (parsed && parsed.__err) throw new Error(parsed.__err);
    return parsed; // { statusCode, body, headers }
  },
  getCache: function(key){ try { return __cacheGet(String(key)); } catch(e){ return null; } },
  setCache: function(key, val, ttl){ try { __cacheSet(String(key), JSON.stringify(val), Number(ttl)||300); } catch(e){} },
  sniffVideo: async function(){ return []; },
  processScraping: async function(){ return null; },
  getScrapeMetadata: async function(){ return null; },
  getDanmakuByFileName: async function(){ return []; },
  getDriveFileList: async function(){ return []; },
  getDriveVideoPlayInfo: async function(){ return null; },
  getDriveInfoByShareURL: async function(){ return null; }
};

// ---- spider_runner ----
__mods["spider_runner"] = {
  // Node 版靠 stdin 循环分发；QuickJS 版直接把传入的方法表当作模块导出（兼容 runner.run({home, search, ...}) 写法）
  run: function(o){ if (o && typeof o === "object") globalThis.module.exports = o; }
};

// axios / http / https / events / url / util 等见 static/node-compat.js


// ---- Buffer 兼容层（utf8 / base64 / base64url / hex）----
// 内部用 latin1 字节串表示，支持字节级操作（indexOf/slice/[i]/concat）
function __mkbuf(bytes){
  var s = "";
  for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i] & 255);
  var b = {
    _bytes: bytes,
    length: bytes.length,
    toString: function(o){
      o = o || "utf8";
      if (o === "base64" || o === "base64url") return __hexToB64(__bytesToHex(bytes), o);
      if (o === "hex") return __bytesToHex(bytes);
      if (o === "latin1" || o === "binary") return s;
      return __latin1ToUtf8(s);
    },
    slice: function(a, b){ return __mkbuf(bytes.slice(a, b)); },
    subarray: function(a, b){ return __mkbuf(bytes.slice(a, b)); },
    indexOf: function(v, from){
      var x = (typeof v === "number") ? v : (v & 255);
      return bytes.indexOf(x, from || 0);
    }
  };
  for (var k = 0; k < bytes.length; k++) b[k] = bytes[k];
  return b;
}
function __bytesToHex(bytes){
  var h = "";
  for (var i = 0; i < bytes.length; i++){ var v = bytes[i] & 255; h += (v < 16 ? "0" : "") + v.toString(16); }
  return h;
}
function __utf8ToBytes(str){
  str = String(str);
  var out = [], i = 0;
  while (i < str.length){
    var c = str.charCodeAt(i);
    if (c < 128) { out.push(c); i++; }
    else if (c < 2048) { out.push(192|(c>>6), 128|(c&63)); i++; }
    else if (c >= 55296 && c <= 56319 && i+1 < str.length){
      var c2 = str.charCodeAt(i+1);
      var cp = 65536 + ((c-55296)<<10) + (c2-56320);
      out.push(240|(cp>>18), 128|((cp>>12)&63), 128|((cp>>6)&63), 128|(cp&63)); i+=2;
    } else { out.push(224|(c>>12), 128|((c>>6)&63), 128|(c&63)); i++; }
  }
  return out;
}
function __latin1ToUtf8(s){
  var bytes = [];
  for (var i=0;i<s.length;i++) bytes.push(s.charCodeAt(i)&255);
  return __bytesToUtf8(bytes);
}
function __bytesToUtf8(bytes){
  var out = "", i = 0;
  while (i < bytes.length){
    var c = bytes[i];
    if (c < 128) { out += String.fromCharCode(c); i++; }
    else if (c >= 192 && c < 224) { out += String.fromCharCode(((c&31)<<6)|(bytes[i+1]&63)); i+=2; }
    else if (c >= 224 && c < 240) { out += String.fromCharCode(((c&15)<<12)|((bytes[i+1]&63)<<6)|(bytes[i+2]&63)); i+=3; }
    else if (c >= 240) { var cp=((c&7)<<18)|((bytes[i+1]&63)<<12)|((bytes[i+2]&63)<<6)|(bytes[i+3]&63); cp-=65536; out += String.fromCharCode(55296|(cp>>10), 56320|(cp&63)); i+=4; }
    else { out += String.fromCharCode(c); i++; }
  }
  return out;
}
function __strToBytes(data, enc){
  enc = enc || "utf8";
  if (typeof data === "number") return new Array(Math.max(0, data|0)).fill(0);
  if (Array.isArray(data)) return data.map(function(x){ return x & 255; });
  if (data && data._bytes) return data._bytes.slice();
  if (enc === "base64" || enc === "base64url"){
    return __hexToBytes(__b64ToHex(String(data), enc));
  }
  if (enc === "latin1" || enc === "binary" || enc === "ascii"){
    var l = String(data), lo = [];
    for (var li = 0; li < l.length; li++) lo.push(l.charCodeAt(li) & 255);
    return lo;
  }
  if (enc === "hex"){
    var h = String(data), out=[];
    for (var i=0;i+1<h.length;i+=2){ var v=parseInt(h.substr(i,2),16); if(!isNaN(v)) out.push(v); }
    return out;
  }
  return __utf8ToBytes(String(data));
}
globalThis.Buffer = {
  from: function(data, enc){ return __mkbuf(__strToBytes(data, enc)); },
  concat: function(list){
    var out = [];
    for (var i=0;i<list.length;i++){
      var b = list[i]._bytes || [];
      for (var j=0;j<b.length;j++) out.push(b[j]&255);
    }
    return __mkbuf(out);
  },
  alloc: function(n){ return __mkbuf(new Array(Math.max(0,n|0)).fill(0)); },
  isBuffer: function(x){ return !!(x && x._bytes); },
  byteLength: function(s, enc){ return __strToBytes(s, enc || "utf8").length; }
};

// ---- URLSearchParams / URL ----
globalThis.URLSearchParams = function(init){
  var m = [];
  if (typeof init === "string"){
    init = init.replace(/^\?/, "");
    init.split("&").forEach(function(pair){
      if (!pair) return;
      var i = pair.indexOf("=");
      var k = i < 0 ? pair : pair.slice(0, i);
      var v = i < 0 ? "" : pair.slice(i + 1);
      m.push([decodeURIComponent(k.replace(/\+/g," ")), decodeURIComponent(v.replace(/\+/g," "))]);
    });
  } else if (init && typeof init === "object"){
    Object.keys(init).forEach(function(k){ m.push([k, String(init[k])]); });
  }
  this._m = m;
};
globalThis.URLSearchParams.prototype = {
  toString: function(){
    return this._m.map(function(p){ return encodeURIComponent(p[0]) + "=" + encodeURIComponent(p[1]); }).join("&");
  },
  append: function(k,v){ this._m.push([String(k), String(v)]); },
  set: function(k,v){
    k = String(k); var found = false;
    for (var i=0;i<this._m.length;i++){ if (this._m[i][0]===k){ this._m[i][1]=String(v); } else if(!found){ /* noop */ } }
    var exists = this._m.some(function(p){ return p[0]===k; });
    if (!exists) this._m.push([k, String(v)]);
  },
  get: function(k){ for (var i=0;i<this._m.length;i++) if (this._m[i][0]===k) return this._m[i][1]; return null; },
  has: function(k){ return this._m.some(function(p){ return p[0]===k; }); },
  forEach: function(cb){ this._m.forEach(function(p){ cb(p[1], p[0]); }); },
  entries: function(){ return this._m.map(function(p){ return [p[0], p[1]]; })[Symbol.iterator](); },
  keys: function(){ return this._m.map(function(p){ return p[0]; }); },
  values: function(){ return this._m.map(function(p){ return p[1]; }); }
};
globalThis.URLSearchParams.prototype[Symbol.iterator] = globalThis.URLSearchParams.prototype.entries;
globalThis.URL = function(u, base){
  if (base && !/^https?:/i.test(String(u))){
    var b = String(base).replace(/\/[^/]*$/, "/");
    u = b + String(u).replace(/^\.\//,"").replace(/^\//,"");
  }
  this.href = String(u);
  var m = /^(https?:)\/\/([^\/?#]+)([^?#]*)(\?[^#]*)?(#.*)?$/.exec(String(u));
  if (m){
    this.protocol = m[1]; this.host = m[2]; this.pathname = m[3] || "/";
    this.search = m[4] || ""; this.hash = m[5] || "";
    this.searchParams = new globalThis.URLSearchParams(this.search || "");
  } else {
    this.protocol=""; this.host=""; this.pathname=String(u); this.search=""; this.hash="";
    this.searchParams = new globalThis.URLSearchParams();
  }
};
globalThis.encodeURIComponent = encodeURIComponent;
globalThis.decodeURIComponent = decodeURIComponent;



// ---- 其他模块占位 ----
var __osMod = {
  tmpdir: function(){ return "/tmp"; },
  homedir: function(){ return "/tmp"; },
  platform: function(){ return "linux"; },
  EOL: "\n"
};
var __pathMod = {
  join: function(){ var a = Array.prototype.slice.call(arguments); return a.join("/").replace(/\/+/g,"/"); },
  resolve: function(){ var a = Array.prototype.slice.call(arguments); return a.join("/").replace(/\/+/g,"/"); },
  basename: function(p){ return String(p).split("/").pop(); },
  dirname: function(p){ var s=String(p).split("/"); s.pop(); return s.join("/")||"/"; },
  extname: function(p){ var b=__pathMod.basename(p); var i=b.lastIndexOf("."); return i<0?"":b.slice(i); }
};
var __fsMod = {
  // 路径只作为键，内容持久化在 数据目录/.fs/ 下（跨调用保留，如登录 token）
  existsSync: function(p){ return JSON.parse(__fsGet(String(p))) !== null; },
  readFileSync: function(p){ var v = JSON.parse(__fsGet(String(p))); return v === null ? "" : v; },
  writeFileSync: function(p, d){
    var str = (d && d._bytes) ? d.toString("utf8") : String(d);
    if (!__fsSet(String(p), str)) throw new Error("写入失败（超过 1MB 或目录不可写）: " + p);
  },
  unlinkSync: function(p){ __fsDel(String(p)); },
  mkdirSync: function(){},
  readdirSync: function(){ return []; }
};
// ---- crypto 兼容层：哈希/AES/RSA/随机数全部落到 Rust 原语（见 jscrypto.rs）----
function __hexToBytes(h){ var out=[]; for(var i=0;i+1<h.length;i+=2) out.push(parseInt(h.substr(i,2),16)); return out; }
function __chk(r){ if (typeof r === "string" && r.indexOf("ERR:") === 0) throw new Error(r.slice(4)); return r; }
function __encOut(bytes, enc){ var b = __mkbuf(bytes); return enc ? b.toString(enc) : b; }
function __pemOf(k){ return (k && typeof k === "object" && !k._bytes) ? String(k.key) : String(k); }
function __padOf(k, def){ return (k && typeof k === "object" && !k._bytes && k.padding != null) ? (k.padding|0) : def; }
function __Cipher(dec, alg, key, iv){
  this._dec = dec; this._alg = String(alg); this._pad = true; this._in = [];
  this._key = __bytesToHex(__strToBytes(key));
  this._iv = iv == null ? "" : __bytesToHex(__strToBytes(iv));
}
__Cipher.prototype.setAutoPadding = function(v){ this._pad = v !== false; return this; };
// 与 Node 的差别：update 只缓存，数据在 final 一次性处理；update()+final() 拼接后的结果与 Node 一致
__Cipher.prototype.update = function(data, inEnc, outEnc){
  var b = __strToBytes(data, inEnc);
  for (var i = 0; i < b.length; i++) this._in.push(b[i]);
  return outEnc ? "" : __mkbuf([]);
};
__Cipher.prototype.final = function(outEnc){
  var r = __chk(__aes(this._dec ? "dec" : "enc", this._alg, this._key, this._iv, __bytesToHex(this._in), this._pad));
  this._in = [];
  return __encOut(__hexToBytes(r), outEnc);
};
function __Hash(alg){ this._alg = String(alg); this._in = []; }
__Hash.prototype.update = function(d, enc){
  var b = __strToBytes(d, enc);
  for (var i = 0; i < b.length; i++) this._in.push(b[i]);
  return this;
};
__Hash.prototype.digest = function(enc){
  var r = __chk(__hash(this._alg, __bytesToHex(this._in)));
  return __encOut(__hexToBytes(r), enc);
};
var __cryptoMod = {
  createHash: function(alg){ return new __Hash(alg); },
  randomBytes: function(n){ return __mkbuf(__hexToBytes(__randHex(Number(n) || 0))); },
  randomUUID: function(){ var h = __randHex(16).split(""); h[12] = "4"; h[16] = "89ab"[parseInt(h[16], 16) & 3]; h = h.join(""); return h.slice(0,8)+"-"+h.slice(8,12)+"-"+h.slice(12,16)+"-"+h.slice(16,20)+"-"+h.slice(20); },
  randomInt: function(a, b){ if (b === undefined) { b = a; a = 0; } return a + Math.floor(parseInt(__randHex(6), 16) / 281474976710656 * (b - a)); },
  randomFillSync: function(buf){
    var n = buf.length, r = __hexToBytes(__randHex(n));
    for (var i = 0; i < n; i++) buf[i] = r[i];
    return buf;
  },
  getRandomValues: function(buf){ return __cryptoMod.randomFillSync(buf); },
  createHmac: function(alg, key){
    var a = String(alg).toLowerCase().replace("-", ""), block = a === "sha512" ? 128 : 64;
    var k = __strToBytes(key);
    if (k.length > block) k = __hexToBytes(__chk(__hash(a, __bytesToHex(k))));
    var ik = [], ok = [];
    for (var i = 0; i < block; i++) { var kb = i < k.length ? k[i] : 0; ik.push(kb ^ 0x36); ok.push(kb ^ 0x5c); }
    var data = [];
    var h = { update: function(d, enc){ var b = __strToBytes(d, enc); for (var j = 0; j < b.length; j++) data.push(b[j]); return h; },
      digest: function(enc){
        var inner = __hexToBytes(__chk(__hash(a, __bytesToHex(ik.concat(data)))));
        return __encOut(__hexToBytes(__chk(__hash(a, __bytesToHex(ok.concat(inner))))), enc);
      } };
    return h;
  },
  createSign: function(alg){
    var data = [];
    var s = { update: function(d, enc){ var b = __strToBytes(d, enc); for (var j = 0; j < b.length; j++) data.push(b[j]); return s; }, end: function(){ return s; },
      sign: function(key, enc){ return __encOut(__hexToBytes(__chk(__rsa("sign", __pemOf(key), __bytesToHex(data), 1, String(alg)))), enc); } };
    return s;
  },
  createVerify: function(alg){
    var data = [];
    var v = { update: function(d, enc){ var b = __strToBytes(d, enc); for (var j = 0; j < b.length; j++) data.push(b[j]); return v; }, end: function(){ return v; },
      verify: function(key, sig, enc){ return __chk(__rsa("verify", __pemOf(key), __bytesToHex(data), 1, String(alg) + "|" + __bytesToHex(__strToBytes(sig, enc)))) === "01"; } };
    return v;
  },
  constants: { RSA_PKCS1_PADDING: 1, RSA_NO_PADDING: 3, RSA_PKCS1_OAEP_PADDING: 4 },
  createCipheriv: function(alg, key, iv){ return new __Cipher(false, alg, key, iv); },
  createDecipheriv: function(alg, key, iv){ return new __Cipher(true, alg, key, iv); },
  // 注意 Node 的 publicEncrypt/privateDecrypt 默认填充是 OAEP(4)
  publicEncrypt: function(key, data){
    return __mkbuf(__hexToBytes(__chk(__rsa("pub_enc", __pemOf(key), __bytesToHex(__strToBytes(data)), __padOf(key, 4), ""))));
  },
  privateDecrypt: function(key, data){
    return __mkbuf(__hexToBytes(__chk(__rsa("priv_dec", __pemOf(key), __bytesToHex(__strToBytes(data)), __padOf(key, 4), ""))));
  }
};
__mods["crypto"] = __cryptoMod;

__mods["os"] = __osMod;
__mods["path"] = __pathMod;
__mods["fs"] = __fsMod;
["cheerio","crypto-js","stream","buffer","net","tls","zlib"].forEach(function(m){
  if (!__mods[m]) __mods[m] = {};
});

globalThis.require = function(name){
  if (Object.prototype.hasOwnProperty.call(__mods, name)) return __mods[name];
  return {};
};
globalThis.module = { exports: {} };
globalThis.exports = globalThis.module.exports;
globalThis.process = { env: __env || {}, version: "v20.0.0", platform: "linux" };
globalThis.console = {
  log: function(){ __log("info", Array.prototype.map.call(arguments, String).join(" ")); },
  warn: function(){ __log("warn", Array.prototype.map.call(arguments, String).join(" ")); },
  error: function(){ __log("error", Array.prototype.map.call(arguments, String).join(" ")); }
};
"#;

/// 把 serde_json::Value 转成 JS 可用的 JSON 字符串（作为调用参数）
fn params_to_js(params: &JsonValue) -> String {
    serde_json::to_string(params).unwrap_or_else(|_| "{}".into())
}

/// 执行一次脚本方法调用，返回 JS 返回值的 JSON
pub async fn call_script(script_id: &str, method: &str, params: JsonValue) -> Result<JsonValue> {
    let source = registry::read_source(script_id)
        .with_context(|| format!("读取脚本 {script_id} 失败"))?;
    call_script_source(&source, method, params).await
}

/// 用给定源码直接调用（测试接口用，不依赖 registry）
pub async fn call_script_source(
    source: &str,
    method: &str,
    params: JsonValue,
) -> Result<JsonValue> {
    let started = Instant::now();

    let rt = AsyncRuntime::new().context("创建 QuickJS Runtime 失败")?;
    rt.set_memory_limit(MEMORY_LIMIT).await;
    {
        let deadline = started + Duration::from_secs(20);
        rt.set_interrupt_handler(Some(Box::new(move || Instant::now() >= deadline)))
            .await;
    }
    let ctx = AsyncContext::full(&rt).await.context("创建 QuickJS Context 失败")?;

    let source = Arc::new(source.to_string());
    let method = Arc::new(method.to_string());
    let params_json = Arc::new(params_to_js(&params));

    let owned_all = crate::jsdom::new_owned();
    let owned_in = owned_all.clone();
    let out: (String, Vec<String>) = ctx
        .async_with(async |ctx: Ctx| {
            let logs: Arc<std::sync::Mutex<Vec<String>>> = Arc::new(std::sync::Mutex::new(Vec::new()));

            // ---- 注入原语 ----
            let lg = logs.clone();
            ctx.globals()
                .set("__log", Func::from(move |level: String, msg: String| {
                    if let Ok(mut v) = lg.lock() {
                        v.push(format!("[{level}] {msg}"));
                    }
                }))
                .unwrap();

            ctx.globals()
                .set("__request", Func::from(Async(js_request)))
                .unwrap();
            ctx.globals()
                .set("__sleep", Func::from(Async(js_sleep)))
                .unwrap();
            ctx.globals()
                .set("__cacheGet", Func::from(js_cache_get))
                .unwrap();
            ctx.globals()
                .set("__cacheSet", Func::from(js_cache_set))
                .unwrap();
            ctx.globals()
                .set("__b64encode", Func::from(js_b64_encode))
                .unwrap();
            ctx.globals()
                .set("__b64decode", Func::from(js_b64_decode))
                .unwrap();
            ctx.globals()
                .set("__hexencode", Func::from(js_hex_encode))
                .unwrap();
            ctx.globals()
                .set("__hexdecode", Func::from(js_hex_decode))
                .unwrap();
            ctx.globals()
                .set("__md5", Func::from(js_md5))
                .unwrap();
            ctx.globals().set("__aes", Func::from(crate::jscrypto::js_aes)).unwrap();
            ctx.globals().set("__rsa", Func::from(crate::jscrypto::js_rsa)).unwrap();
            ctx.globals().set("__hash", Func::from(crate::jscrypto::js_hash)).unwrap();
            ctx.globals().set("__randHex", Func::from(crate::jscrypto::js_rand_hex)).unwrap();
            ctx.globals().set("__b64ToHex", Func::from(crate::jscrypto::js_b64_to_hex)).unwrap();
            ctx.globals().set("__hexToB64", Func::from(crate::jscrypto::js_hex_to_b64)).unwrap();
            // ---- DOM 原语（cheerio 兼容层用）----
            let owned = owned_in.clone();
            {
                let o = owned.clone();
                ctx.globals().set("__domLoad", Func::from(move |html: String, frag: bool| crate::jsdom::load(&o, &html, frag))).unwrap();
                let o = owned.clone();
                ctx.globals().set("__domClone", Func::from(move |d: f64, i: f64| crate::jsdom::clone_node(&o, d as u64, i as usize))).unwrap();
            }
            ctx.globals().set("__domRoot", Func::from(|d: f64| crate::jsdom::root_of(d as u64) as f64)).unwrap();
            ctx.globals().set("__domInfo", Func::from(|d: f64, i: f64| crate::jsdom::info(d as u64, i as usize))).unwrap();
            ctx.globals().set("__domChildren", Func::from(|d: f64, i: f64| crate::jsdom::children(d as u64, i as usize))).unwrap();
            ctx.globals().set("__domText", Func::from(|d: f64, i: f64| crate::jsdom::text(d as u64, i as usize))).unwrap();
            ctx.globals().set("__domHtml", Func::from(|d: f64, i: f64, outer: bool| crate::jsdom::html(d as u64, i as usize, outer))).unwrap();
            ctx.globals().set("__domSelect", Func::from(|d: f64, ctx_json: String, sel: String| crate::jsdom::select(d as u64, &ctx_json, &sel))).unwrap();
            ctx.globals().set("__domMatches", Func::from(|d: f64, i: f64, sel: String| crate::jsdom::matches(d as u64, i as usize, &sel))).unwrap();
            ctx.globals().set("__domRemove", Func::from(|d: f64, i: f64| crate::jsdom::remove(d as u64, i as usize))).unwrap();
            ctx.globals().set("__fsGet", Func::from(js_fs_get)).unwrap();
            ctx.globals().set("__fsSet", Func::from(js_fs_set)).unwrap();
            ctx.globals().set("__fsDel", Func::from(js_fs_del)).unwrap();

            // 环境变量
            let env = serde_json::json!({
                "SITE_API": std::env::var("SITE_API").unwrap_or_default(),
                "DANMU_API": std::env::var("DANMU_API").unwrap_or_default(),
            });
            ctx.globals()
                .set("__env", env.to_string())
                .unwrap();

            // 先跑 polyfill，再跑脚本
            ctx.eval::<(), _>(POLYFILL).catch(&ctx).unwrap();
            ctx.eval::<(), _>(NODE_COMPAT).catch(&ctx).unwrap();
            // 内置 npm 包兼容层：惰性加载（首次 require 时才求值，避免每次调用都付出解析成本）
            for (name, src) in [("crypto-js", CRYPTO_JS_SHIM), ("cheerio", CHEERIO_SHIM)] {
                let loader = format!(
                    r#"(function(){{
  var __c;
  Object.defineProperty(__mods, {}, {{ configurable: true, enumerable: true, get: function(){{
    if (!__c) {{
      var m = {{ exports: {{}} }};
      (new Function("module", "exports", "require", {}))(m, m.exports, globalThis.require);
      __c = m.exports;
    }}
    return __c;
  }} }});
}})();"#,
                    json_literal(name),
                    json_literal(src)
                );
                ctx.eval::<(), _>(loader.as_str()).catch(&ctx).unwrap();
            }

            let call_expr = format!(
                r#"
var __src = {};
var __params = JSON.parse({});
var __method = {};
globalThis.module = {{ exports: {{}} }};
globalThis.exports = globalThis.module.exports;
(function(){{ return eval(__src); }})();
(async function(){{
  var fn = module.exports[__method];
  if (typeof fn !== "function") {{ return JSON.stringify({{__err: "脚本未导出方法 " + __method}}); }}
  try {{
    var r = await fn(__params, {{from:"web"}});
    return JSON.stringify(r === undefined ? null : r);
  }} catch(e) {{
    return JSON.stringify({{__err: String(e && e.message || e)}});
  }}
}})()
"#,
                json_literal(&source),
                json_literal(&params_json),
                json_literal(&method),
            );

            let result: String = match ctx.eval::<Promise, _>(call_expr.as_str()).catch(&ctx) {
                Ok(promise) => promise
                    .into_future::<String>()
                    .await
                    .catch(&ctx)
                    .unwrap_or_else(|e| {
                        serde_json::json!({"__err": format!("JS 执行异常: {:?}", e)}).to_string()
                    }),
                Err(e) => {
                    serde_json::json!({"__err": format!("脚本求值失败: {:?}", e)}).to_string()
                }
            };
            let logs = logs.lock().map(|v| v.clone()).unwrap_or_default();
            (result, logs)
        })
        .await;

    crate::jsdom::free_owned(&owned_all);
    let (json_str, logs) = out;
    if std::env::var("OMNIBOX_JS_LOG").is_ok() {
        for l in &logs {
            eprintln!("[js] {l}");
        }
    }
    let value: JsonValue = serde_json::from_str(&json_str).unwrap_or_else(|_| {
        serde_json::json!({ "__err": format!("返回值解析失败: {}", &json_str[..json_str.len().min(200)]) })
    });
    if let Some(err) = value.get("__err").and_then(JsonValue::as_str) {
        anyhow::bail!("{err}");
    }
    Ok(value)
}

/// 把字符串编码成 JS 字符串字面量（安全转义）
fn json_literal(s: &str) -> String {
    serde_json::to_string(s).unwrap_or_else(|_| "\"\"".into())
}

// ===== Rust 原语 =====

/// 定时器原语：供 JS 侧 setTimeout/setInterval 使用（单次最多 15s）
async fn js_sleep(ms: f64) -> rquickjs::Result<bool> {
    let ms = if ms.is_finite() { ms.max(0.0).min(15_000.0) as u64 } else { 0 };
    tokio::time::sleep(Duration::from_millis(ms)).await;
    Ok(true)
}

/// 网络请求原语。为绕过 rquickjs 对异步函数返回类型的严格约束，
/// 永远返回 Ok(String)：失败时 JSON 里带 __err，由 JS polyfill 抛出。
async fn js_request(url: String, opts_json: String) -> rquickjs::Result<String> {
    let result = js_request_inner(&url, &opts_json).await;
    Ok(match result {
        Ok(v) => v.to_string(),
        Err(e) => serde_json::json!({"__err": e.to_string()}).to_string(),
    })
}

async fn js_request_inner(url: &str, opts_json: &str) -> anyhow::Result<JsonValue> {
    let opts: JsonValue = serde_json::from_str(opts_json).unwrap_or_else(|_| serde_json::json!({}));
    let method = opts.get("method").and_then(JsonValue::as_str).unwrap_or("GET").to_string();
    let url_parsed = reqwest::Url::parse(url).map_err(|e| anyhow::anyhow!("URL 解析失败: {e}"))?;
    match url_parsed.scheme() {
        "http" | "https" => {}
        _ => anyhow::bail!("仅支持 http/https"),
    }
    if let Some(host) = url_parsed.host_str() {
        if registry::is_private_host(host) {
            anyhow::bail!("禁止访问内网地址");
        }
    }

    // 可选：timeout(ms，上限 18s) / maxRedirects（0 = 不跟随，便于读取 Location）
    let timeout_ms = opts.get("timeout").and_then(JsonValue::as_f64).filter(|v| *v > 0.0).map(|v| v.min(18_000.0) as u64).unwrap_or(15_000);
    let max_redirects = opts.get("maxRedirects").and_then(JsonValue::as_u64).map(|v| v.min(10) as usize).unwrap_or(3);
    let binary = opts.get("binary").and_then(JsonValue::as_bool).unwrap_or(false);
    // 重定向的每一跳都做内网校验（避免外网地址 302 到内网）
    let policy = reqwest::redirect::Policy::custom(move |attempt| {
        if attempt.previous().len() >= max_redirects {
            return attempt.stop();
        }
        if let Some(h) = attempt.url().host_str() {
            if registry::is_private_host(h) {
                return attempt.error("禁止访问内网地址");
            }
        }
        attempt.follow()
    });
    let client = reqwest::Client::builder()
        .timeout(Duration::from_millis(timeout_ms))
        .danger_accept_invalid_certs(true)
        .redirect(policy)
        .build()
        .map_err(|e| anyhow::anyhow!("HTTP 客户端失败: {e}"))?;

    let mut req = client.request(
        method.parse().unwrap_or(reqwest::Method::GET),
        url_parsed,
    );
    if let Some(headers) = opts.get("headers").and_then(JsonValue::as_object) {
        for (k, v) in headers {
            let sv = match v {
                JsonValue::String(s) => Some(s.clone()),
                JsonValue::Number(n) => Some(n.to_string()),
                JsonValue::Bool(b) => Some(b.to_string()),
                _ => None,
            };
            if let Some(s) = sv {
                if let Ok(name) = reqwest::header::HeaderName::from_bytes(k.as_bytes()) {
                    if let Ok(val) = reqwest::header::HeaderValue::from_str(&s) {
                        req = req.header(name, val);
                    }
                }
            }
        }
    }
    if let Some(hex) = opts.get("bodyHex").and_then(JsonValue::as_str) {
        let bytes: Vec<u8> = (0..hex.len() / 2)
            .filter_map(|i| u8::from_str_radix(&hex[i * 2..i * 2 + 2], 16).ok())
            .collect();
        req = req.body(bytes);
    } else if let Some(body) = opts.get("body").and_then(JsonValue::as_str) {
        req = req.body(body.to_string());
    }

    let resp = req.send().await.map_err(|e| anyhow::anyhow!("请求失败: {e}"))?;
    let status = resp.status().as_u16();
    let final_url = resp.url().to_string();
    let mut headers: serde_json::Map<String, JsonValue> = serde_json::Map::new();
    for (k, v) in resp.headers().iter() {
        if let Ok(sv) = v.to_str() {
            let key = k.as_str().to_string();
            if key == "set-cookie" {
                // Node 里 set-cookie 是数组
                let entry = headers.entry(key).or_insert_with(|| JsonValue::Array(vec![]));
                if let JsonValue::Array(a) = entry {
                    a.push(JsonValue::String(sv.to_string()));
                }
            } else if let Some(old) = headers.get_mut(&key) {
                if let JsonValue::String(o) = old {
                    o.push_str(", ");
                    o.push_str(sv);
                }
            } else {
                headers.insert(key, JsonValue::String(sv.to_string()));
            }
        }
    }
    const MAX_BODY: usize = 5 * 1024 * 1024;
    if binary {
        let bytes = resp.bytes().await.map_err(|e| anyhow::anyhow!("读取响应失败: {e}"))?;
        let cut = &bytes[..bytes.len().min(MAX_BODY)];
        let hex: String = cut.iter().map(|b| format!("{b:02x}")).collect();
        return Ok(serde_json::json!({"statusCode": status, "body": "", "bodyHex": hex, "headers": headers, "url": final_url}));
    }
    let body = resp.text().await.map_err(|e| anyhow::anyhow!("读取响应失败: {e}"))?;
    let body = if body.len() > MAX_BODY {
        let mut cut = MAX_BODY;
        while !body.is_char_boundary(cut) {
            cut -= 1;
        }
        body[..cut].to_string()
    } else {
        body
    };
    Ok(serde_json::json!({"statusCode": status, "body": body, "headers": headers, "url": final_url}))
}

// ---- fs 持久化：脚本里的任意路径只当作"键"，映射到 数据目录/.fs/ 下的固定文件，不会触达真实文件系统 ----
const FS_MAX_BYTES: usize = 1024 * 1024;

fn fs_file(path: &str) -> std::path::PathBuf {
    use sha2::Digest;
    let safe: String = path
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '.' || c == '-' { c } else { '_' })
        .rev()
        .take(60)
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .collect();
    let h = sha2::Sha256::digest(path.as_bytes());
    let h: String = h.iter().take(8).map(|b| format!("{b:02x}")).collect();
    registry::debug_dir().join(".fs").join(format!("{safe}-{h}"))
}

/// 返回 JSON：文件内容字符串，或 null（不存在）
fn js_fs_get(path: String) -> String {
    match std::fs::read_to_string(fs_file(&path)) {
        Ok(s) => serde_json::to_string(&s).unwrap_or_else(|_| "null".into()),
        Err(_) => "null".into(),
    }
}

fn js_fs_set(path: String, data: String) -> bool {
    if data.len() > FS_MAX_BYTES {
        return false;
    }
    let f = fs_file(&path);
    if let Some(dir) = f.parent() {
        if std::fs::create_dir_all(dir).is_err() {
            return false;
        }
    }
    std::fs::write(f, data).is_ok()
}

fn js_fs_del(path: String) -> bool {
    std::fs::remove_file(fs_file(&path)).is_ok()
}

fn js_cache_get(key: String) -> String {
    match crate::cache::get(&key) {
        Some(v) => v.to_string(),
        None => "null".to_string(),
    }
}

fn js_cache_set(key: String, val_json: String, ttl: f64) {
    if let Ok(v) = serde_json::from_str::<JsonValue>(&val_json) {
        let ttl = if ttl <= 0.0 { 300.0 } else { ttl };
        crate::cache::set(&key, v, Duration::from_secs_f64(ttl));
    }
}

fn js_b64_encode(data: String, variant: String) -> String {
    use base64::Engine;
    let bytes = data.as_bytes();
    if variant == "base64url" {
        base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
    } else {
        base64::engine::general_purpose::STANDARD.encode(bytes)
    }
}

fn js_b64_decode(data: String, variant: String) -> String {
    use base64::Engine;
    let r = if variant == "base64url" {
        base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(data.as_bytes())
            .or_else(|_| base64::engine::general_purpose::URL_SAFE.decode(data.as_bytes()))
    } else {
        base64::engine::general_purpose::STANDARD.decode(data.as_bytes())
    };
    String::from_utf8_lossy(&r.unwrap_or_default()).into_owned()
}

fn js_hex_encode(data: String) -> String {
    data.as_bytes().iter().map(|b| format!("{b:02x}")).collect()
}

fn js_hex_decode(data: String) -> String {
    let mut out = Vec::new();
    let b = data.as_bytes();
    let mut i = 0;
    while i + 1 < b.len() {
        if let Ok(v) = u8::from_str_radix(&String::from_utf8_lossy(&b[i..i + 2]), 16) {
            out.push(v);
        }
        i += 2;
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// 轻量 MD5 实现（无外部依赖），返回小写 hex
fn js_md5(input: String) -> String {
    fn md5(bytes: &[u8]) -> [u8; 16] {
        const S: [u32; 64] = [
            7,12,17,22, 7,12,17,22, 7,12,17,22, 7,12,17,22,
            5, 9,14,20, 5, 9,14,20, 5, 9,14,20, 5, 9,14,20,
            4,11,16,23, 4,11,16,23, 4,11,16,23, 4,11,16,23,
            6,10,15,21, 6,10,15,21, 6,10,15,21, 6,10,15,21,
        ];
        const K: [u32; 64] = [
            0xd76aa478,0xe8c7b756,0x242070db,0xc1bdceee,0xf57c0faf,0x4787c62a,0xa8304613,0xfd469501,
            0x698098d8,0x8b44f7af,0xffff5bb1,0x895cd7be,0x6b901122,0xfd987193,0xa679438e,0x49b40821,
            0xf61e2562,0xc040b340,0x265e5a51,0xe9b6c7aa,0xd62f105d,0x02441453,0xd8a1e681,0xe7d3fbc8,
            0x21e1cde6,0xc33707d6,0xf4d50d87,0x455a14ed,0xa9e3e905,0xfcefa3f8,0x676f02d9,0x8d2a4c8a,
            0xfffa3942,0x8771f681,0x6d9d6122,0xfde5380c,0xa4beea44,0x4bdecfa9,0xf6bb4b60,0xbebfbc70,
            0x289b7ec6,0xeaa127fa,0xd4ef3085,0x04881d05,0xd9d4d039,0xe6db99e5,0x1fa27cf8,0xc4ac5665,
            0xf4292244,0x432aff97,0xab9423a7,0xfc93a039,0x655b59c3,0x8f0ccc92,0xffeff47d,0x85845dd1,
            0x6fa87e4f,0xfe2ce6e0,0xa3014314,0x4e0811a1,0xf7537e82,0xbd3af235,0x2ad7d2bb,0xeb86d391,
        ];
        let mut msg = bytes.to_vec();
        let bit_len = (msg.len() as u64).wrapping_mul(8);
        msg.push(0x80);
        while msg.len() % 64 != 56 { msg.push(0); }
        msg.extend_from_slice(&bit_len.to_le_bytes());
        let (mut a0,mut b0,mut c0,mut d0) = (0x67452301u32,0xefcdab89u32,0x98badcfeu32,0x10325476u32);
        for chunk in msg.chunks(64) {
            let mut m = [0u32; 16];
            for i in 0..16 {
                m[i] = u32::from_le_bytes([chunk[i*4],chunk[i*4+1],chunk[i*4+2],chunk[i*4+3]]);
            }
            let (mut a,mut b,mut c,mut d) = (a0,b0,c0,d0);
            for i in 0..64 {
                let (f,g) = if i < 16 { ((b & c) | ((!b) & d), i) }
                    else if i < 32 { ((d & b) | ((!d) & c), (5*i+1)%16) }
                    else if i < 48 { (b ^ c ^ d, (3*i+5)%16) }
                    else { (c ^ (b | (!d)), (7*i)%16) };
                let tmp = d;
                d = c; c = b;
                b = b.wrapping_add(a.wrapping_add(f).wrapping_add(K[i]).wrapping_add(m[g]).rotate_left(S[i]));
                a = tmp;
            }
            a0 = a0.wrapping_add(a); b0 = b0.wrapping_add(b);
            c0 = c0.wrapping_add(c); d0 = d0.wrapping_add(d);
        }
        let mut out = [0u8; 16];
        out[0..4].copy_from_slice(&a0.to_le_bytes());
        out[4..8].copy_from_slice(&b0.to_le_bytes());
        out[8..12].copy_from_slice(&c0.to_le_bytes());
        out[12..16].copy_from_slice(&d0.to_le_bytes());
        out
    }
    md5(input.as_bytes()).iter().map(|b| format!("{b:02x}")).collect()
}
