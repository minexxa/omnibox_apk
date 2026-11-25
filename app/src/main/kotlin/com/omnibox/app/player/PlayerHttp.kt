package com.omnibox.app.player

import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.net.URLDecoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** 播放请求头：setMediaItem 前由播放器写入，DataSource open() 时注入。 */
object PlayerHeaders {
    @Volatile
    var current: Map<String, String> = emptyMap()
}

const val DEFAULT_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/** 信任所有证书的 OkHttp（片源常见自签名/证书链不全），与 ubox 行为一致。 */
private fun trustAllClient(): OkHttpClient {
    val tm = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }
    return OkHttpClient.Builder()
        .sslSocketFactory(ssl.socketFactory, tm)
        .hostnameVerifier { _, _ -> true }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
}

private val playerClient by lazy { trustAllClient() }

/** DataSource 工厂：OkHttp + 头注入。 */
@UnstableApi
object PlayerDataSourceFactory : DataSource.Factory {
    override fun createDataSource(): DataSource = HeaderedDataSource()
}

@UnstableApi
private class HeaderedDataSource : DataSource {
    private val upstream: HttpDataSource = OkHttpDataSource.Factory(playerClient)
        .setDefaultRequestProperties(mapOf("User-Agent" to DEFAULT_UA))
        .createDataSource()

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val headers = PlayerHeaders.current
        val spec = if (headers.isEmpty()) dataSpec else dataSpec.buildUpon()
            .setHttpRequestHeaders(dataSpec.httpRequestHeaders + headers).build()
        return upstream.open(spec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)
    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() = upstream.close()
}

/** 按 URL 推断 mime：内核返回直链多为 .m3u8，显式指定避免 extractor 误判。 */
fun inferMimeType(url: String): String? {
    val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url).lowercase()
    return when {
        decoded.contains(".m3u8") || decoded.contains("do=m3u8") || decoded.contains("do=live") -> MimeTypes.APPLICATION_M3U8
        decoded.contains(".mp4") -> MimeTypes.VIDEO_MP4
        decoded.contains(".mkv") -> MimeTypes.VIDEO_MATROSKA
        else -> null
    }
}
