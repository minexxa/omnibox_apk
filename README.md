# OmniBox Core — 豆瓣推荐 + QuickJS 动态影视源（Android .so 内核）

纯 Rust 内核，编译为 `libomnibox_core.so`，由 APK 通过 JNI 调用。**已移除网页面板与 HTTP 服务**（无 axum、无端口、无鉴权层），脚本统一由内嵌 QuickJS 运行，不依赖 Node.js。

> 本项目不内置任何影视爬虫脚本，脚本由用户自行导入，保存在 App 数据目录 `spiders/` 下。请遵守当地法律法规，仅用于学习研究。

## 构建

CI：`.github/workflows/android.yml`（手动触发或推送 main/master），产物 `libomnibox_core.so` 同时上传为 artifact 并发布到 Release `core-latest`。

本地：
```bash
cargo install cargo-ndk
rustup target add aarch64-linux-android
cargo ndk --target arm64-v8a --platform 26 -o app/src/main/jniLibs build --release --locked
```

## 接口

Kotlin 侧对象 `com.omnibox.app.OmniBoxNative`（包名/类名需与 `src/jni_bridge.rs` 中的函数名一致）：

```kotlin
object OmniBoxNative {
    init { System.loadLibrary("omnibox_core") }
    external fun init(dataDir: String, adminToken: String): Boolean
    external fun call(method: String, params: String): String        // 返回 JSON 字符串
    external fun image(url: String): Array<Any>?                     // [mime: String, bytes: ByteArray]
}
```

`call(method, paramsJson)` 支持的方法：

| method | params | 说明 |
|---|---|---|
| `health` | – | 健康检查 |
| `douban_home` | – | 豆瓣首页（banners/classes/movie/tv） |
| `douban_category` | `cat`, `page` | 豆瓣分类 |
| `search` | `src`(all/脚本id), `wd`, `page` | 聚合搜索 |
| `detail` | `src`, `id` | 详情 |
| `play` | `src`, `q` | 播放解析（返回 urls/header，由播放器自行带头请求） |
| `spiders_list/add/add_url/toggle/remove/source/test` | 见 `src/lib.rs` | 脚本管理 |
| `history_list/add/remove/clear` | 见 `src/lib.rs` | 播放历史 |

所有返回值均为 JSON；失败时 `{"ok":false,"error":"..."}`。豆瓣图片返回原始 URL，用 `image(url)` 取字节（内核自动带防盗链 Referer，并拦截内网地址）。

## 脚本格式与兼容性

与原 Web 版一致：脚本导出 `home/category/search/detail/play`；QuickJS 内置 `crypto`、`crypto-js`、`cheerio`（只读 API）、`axios`、`http(s)`、`Buffer`、`URL` 等兼容层（见 `static/*.js`、`src/jscrypto.rs`、`src/jsdom.rs`）。`WebAssembly`、`child_process`、`WebSocket` 不可用。脚本内网络请求仅 http/https、拦内网、响应限 5MB，QuickJS 每次调用独立 Runtime、64MB 内存、20s 超时。

## 与 Web 版的差异

- 删除：网页面板（`static/*.html`、`hls.min.js`、PWA）、`/api/*` 路由、`/api/stream` 视频流代理、访问鉴权、命令行参数。
- 播放：不再经本地代理，播放器（如 ExoPlayer）直接用 `play` 返回的 `header` 请求；若源站需要 m3u8 重写，需在 App 侧处理。
