package com.omnibox.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnibox.app.OmniBoxApp
import com.omnibox.app.OmniBoxNative
import com.omnibox.app.TvMode
import com.omnibox.app.data.Core
import com.omnibox.app.data.Spider
import com.omnibox.app.data.parseSpiders
import com.omnibox.app.ui.Toast
import com.omnibox.app.ui.components.TopBarButton
import com.omnibox.app.ui.theme.AppColors
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { OmniBoxApp.prefs() }

    var section by remember { mutableStateOf("main") } // main / spiders / import / player / system

    // 子段时返回键回到设置主菜单；main 时不拦截，交给全局 BackHandler 退出设置页
    BackHandler(enabled = section != "main") { section = "main" }

    Column(Modifier.fillMaxSize().background(Color(0xFF101418))) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (section) {
                    "spiders" -> "脚本管理"
                    "import" -> "导入 JS 脚本"
                    "player" -> "播放设置"
                    "system" -> "系统 / TV"
                    else -> "设置"
                },
                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            )
        }

        when (section) {
            "main" -> MainSettings(
                onSpiders = { section = "spiders" },
                onPlayer = { section = "player" },
                onSystem = { section = "system" },
            )
            "spiders" -> SpidersSection(onImport = { section = "import" })
            "import" -> ImportSection { section = "spiders" }
            "player" -> PlayerSection(prefs)
            "system" -> SystemSection(ctx, prefs)
        }
    }
}

@Composable
private fun MainSettings(onSpiders: () -> Unit, onPlayer: () -> Unit, onSystem: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SectionHeader("影视源")
        TopBarButton("脚本管理（导入/启停/删除/测试）") { onSpiders() }
        Spacer(Modifier.height(8.dp))
        SectionHeader("播放")
        TopBarButton("播放设置（解码/倍速/续播）") { onPlayer() }
        Spacer(Modifier.height(8.dp))
        SectionHeader("系统")
        TopBarButton("系统 / TV 模式 / 内核信息") { onSystem() }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, color = AppColors.Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun SpidersSection(onImport: () -> Unit) {
    val scope = rememberCoroutineScope()
    var spiders by remember { mutableStateOf<List<Spider>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    var kw by remember { mutableStateOf("测试") }
    var source by remember { mutableStateOf("") }
    var showSource by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        loading = true
        spiders = parseSpiders(Core.spidersList())
        loading = false
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TopBarButton("＋ 导入 JS（文件/粘贴/URL）") { onImport() }
        Spacer(Modifier.height(8.dp))
        if (loading) {
            Text("加载中…", color = Color.Gray)
        } else if (spiders.isEmpty()) {
            Text("尚未导入任何脚本。影视源全部来自你导入的 OmniBox 格式 JS。", color = Color.Gray, fontSize = 14.sp)
        } else {
            OutlinedTextField(
                value = kw, onValueChange = { kw = it }, singleLine = true,
                label = { Text("测试关键词") },
                textStyle = TextStyle(color = Color.White),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            spiders.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${s.label}  ${if (s.enabled) "✅启用" else "⛔停用"}",
                        color = Color.White, fontSize = 15.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TopBarButton(if (s.enabled) "停用" else "启用") {
                        scope.launch {
                            Core.call("spiders_toggle", JSONObject().put("id", s.id).put("enabled", !s.enabled))
                            reload += 1
                        }
                    }
                    TopBarButton("测试") {
                        scope.launch {
                            Toast.show("正在测试 ${s.label}…")
                            val resp = Core.call("spiders_test", JSONObject().put("id", s.id).put("kw", kw))
                            val ok = resp.optBoolean("ok", false)
                            val count = runCatching {
                                resp.optJSONObject("result")?.optJSONArray("list")?.length() ?: 0
                            }.getOrDefault(0)
                            Toast.show(if (ok) "测试成功，返回 $count 条" else "测试失败：${resp.optString("error")}")
                        }
                    }
                    TopBarButton("源码") {
                        scope.launch {
                            source = Core.call("spiders_source", JSONObject().put("id", s.id)).optString("source", "")
                            showSource = source.isNotEmpty()
                        }
                    }
                    TopBarButton("删除") {
                        scope.launch {
                            Core.call("spiders_remove", JSONObject().put("id", s.id))
                            reload += 1
                        }
                    }
                }
                if (s.lastError.isNotEmpty()) {
                    Text("  最近错误：${s.lastError}", color = Color(0xFFCF6679), fontSize = 12.sp)
                }
            }
            if (showSource) {
                Spacer(Modifier.height(8.dp))
                Text("源码预览：", color = Color.Gray, fontSize = 13.sp)
                Text(source, color = Color(0xFFB0BEC5), fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().height(160.dp).verticalScroll(rememberScrollState()))
            }
        }
    }
}

@Composable
private fun ImportSection(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("spider.js") }
    var paste by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val text = readUri(ctx, uri)
            if (text != null) { paste = text; name = uri.lastPathSegment ?: "imported.js" }
            else Toast.show("读取文件失败")
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("OmniBox 格式脚本：导出 home/category/search/detail/play。", color = Color.Gray, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        TopBarButton("① 选择本地 .js 文件") { filePicker.launch("*/*") }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = name, onValueChange = { name = it }, singleLine = true, label = { Text("脚本文件名") },
            textStyle = TextStyle(color = Color.White), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = paste, onValueChange = { paste = it }, label = { Text("脚本源码（可直接粘贴）") },
            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
            modifier = Modifier.fillMaxWidth().height(180.dp),
        )
        TopBarButton("② 导入以上源码") {
            if (paste.isBlank()) Toast.show("源码为空") else {
                scope.launch {
                    val resp = Core.call("spiders_add", JSONObject().put("name", name).put("source", paste))
                    Toast.show(if (resp.optBoolean("ok")) "导入成功" else "导入失败：${resp.optString("error")}")
                    if (resp.optBoolean("ok")) onDone()
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = url, onValueChange = { url = it }, singleLine = true, label = { Text("或：远程 raw URL") },
            textStyle = TextStyle(color = Color.White), modifier = Modifier.fillMaxWidth(),
        )
        TopBarButton("③ 从 URL 拉取") {
            if (url.isBlank()) Toast.show("URL 为空") else {
                scope.launch {
                    val resp = Core.call("spiders_add_url", JSONObject().put("url", url.trim()))
                    Toast.show(if (resp.optBoolean("ok")) "拉取成功" else "拉取失败：${resp.optString("error")}")
                    if (resp.optBoolean("ok")) onDone()
                }
            }
        }
    }
}

private fun readUri(ctx: Context, uri: Uri): String? = runCatching {
    ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
}.getOrNull()

@Composable
private fun PlayerSection(prefs: com.omnibox.app.data.Prefs) {
    var decoder by remember { mutableStateOf(prefs.decoder) }
    var speed by remember { mutableStateOf(prefs.speed) }
    var autoNext by remember { mutableStateOf(prefs.autoNext) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        SectionHeader("解码方式")
        listOf("auto" to "自动（硬解优先，失败回退）", "hard" to "硬解优先", "soft" to "软解优先（需设备/扩展支持）")
            .forEach { (v, label) ->
                TopBarButton("${if (decoder == v) "● " else "○ "}$label") {
                    decoder = v; prefs.decoder = v
                }
            }
        Spacer(Modifier.height(12.dp))
        SectionHeader("默认倍速")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                TopBarButton("${if (speed == s) "● " else ""}${s}x") { speed = s; prefs.speed = s }
            }
        }
        Spacer(Modifier.height(12.dp))
        SectionHeader("播放行为")
        TopBarButton("${if (autoNext) "● " else "○ "}播完自动下一集") {
            autoNext = !autoNext; prefs.autoNext = autoNext
        }
        Text("提示：HLS(m3u8) 需设备支持或后续接入软解扩展。", color = Color.Gray, fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun SystemSection(ctx: Context, prefs: com.omnibox.app.data.Prefs) {
    var tvMode by remember { mutableStateOf(prefs.tvMode) }
    var health by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val resp = Core.call("health")
        health = "引擎：${resp.optString("engine")}\n版本：${resp.optString("version")}\n数据目录：${resp.optString("data_dir")}"
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        SectionHeader("界面模式")
        listOf("auto" to "自动检测", "on" to "TV 模式", "off" to "普通模式").forEach { (v, label) ->
            TopBarButton("${if (tvMode == v) "● " else "○ "}$label") {
                tvMode = v; prefs.tvMode = v; TvMode.init()
            }
        }
        Spacer(Modifier.height(16.dp))
        SectionHeader("内核")
        Text(if (OmniBoxApp.coreReady) "✅ 已就绪" else "❌ 未就绪", color = Color.White, fontSize = 14.sp)
        Text(health, color = Color(0xFFB0BEC5), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(16.dp))
        Text("脚本目录：${ctx.filesDir}/spiders/", color = Color.Gray, fontSize = 12.sp)
    }
}
