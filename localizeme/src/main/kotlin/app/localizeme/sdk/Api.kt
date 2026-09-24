package app.localizeme.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** One HTTP exchange, so tests can answer without a network. */
internal interface Transport {
    class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    fun exchange(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Response
}

internal class UrlConnectionTransport : Transport {
    override fun exchange(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Transport.Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.useCaches = false
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val bytes = stream?.use { input ->
                val out = ByteArrayOutputStream()
                input.copyTo(out)
                out.toByteArray()
            } ?: ByteArray(0)
            val responseHeaders = HashMap<String, String>()
            for ((name, values) in connection.headerFields) {
                if (name != null && values.isNotEmpty()) responseHeaders[name.lowercase()] = values.last()
            }
            return Transport.Response(status, responseHeaders, bytes)
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * The three calls the SDK makes, with the headers the dashboard reads.
 *
 * The install id and app version are suppliers: reading them touches
 * SharedPreferences and PackageManager, which belongs on the SDK thread, where
 * every request runs, and not in `Application.onCreate`.
 */
internal class Api(
    private val config: LocalizeMeConfig,
    private val transport: Transport,
    installId: () -> String,
    appVersion: () -> String,
    private val osVersion: String,
) {
    @Volatile var language: String? = null

    private val installId: String by lazy(installId)
    private val appVersion: String by lazy(appVersion)

    sealed class ManifestResult {
        object NotModified : ManifestResult()
        data class Fresh(val manifest: Manifest, val etag: String?) : ManifestResult()
    }

    fun fetchManifest(etag: String?): ManifestResult {
        val headers = baseHeaders()
        if (etag != null) headers["If-None-Match"] = etag
        val response = exchange("GET", "${config.baseUrl.trimEnd('/')}/ota/v1/manifest?platform=$PLATFORM", headers, null)
        if (response.status == 304) return ManifestResult.NotModified
        if (response.status != 200) throw errorFor(response)
        val manifest = try {
            Manifest.parse(String(response.body))
        } catch (e: LocalizeMeException) {
            throw e
        } catch (e: Exception) {
            throw LocalizeMeException(ErrorKind.BAD_RESPONSE, "manifest: ${e.message}")
        }
        return ManifestResult.Fresh(manifest, response.headers["etag"])
    }

    fun fetchBundle(language: ManifestLanguage): ByteArray {
        val response = exchange("GET", language.url, baseHeaders(), null)
        if (response.status != 200) throw errorFor(response)
        if (Sha256.hex(response.body) != language.sha256) {
            throw LocalizeMeException(ErrorKind.HASH_MISMATCH, "bundle ${language.sha256.take(8)}")
        }
        return response.body
    }

    fun report(errors: List<Map<String, String>>, missingKeys: List<Map<String, String>>): Boolean {
        val body = JSONObject()
        body.put("errors", JSONArray(errors.map { JSONObject(it) }))
        body.put("missing_keys", JSONArray(missingKeys.map { JSONObject(it) }))
        val headers = baseHeaders()
        headers["Content-Type"] = "application/json"
        return try {
            val response = exchange(
                "POST", "${config.baseUrl.trimEnd('/')}/ota/v1/report?platform=$PLATFORM",
                headers, body.toString().toByteArray(),
            )
            response.status in 200..299
        } catch (e: LocalizeMeException) {
            false
        }
    }

    private fun baseHeaders(): HashMap<String, String> {
        val headers = HashMap<String, String>()
        headers["Authorization"] = "Bearer ${config.sdkKey}"
        headers["X-LocalizeMe-Install"] = installId
        headers["X-LocalizeMe-App"] = appVersion
        headers["X-LocalizeMe-SDK"] = SDK_VERSION
        headers["X-LocalizeMe-OS"] = osVersion
        language?.let { headers["X-LocalizeMe-Language"] = it }
        headers["User-Agent"] = "localizeme-android/$SDK_VERSION"
        return headers
    }

    private fun exchange(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Transport.Response =
        try {
            transport.exchange(method, url, headers, body)
        } catch (e: Exception) {
            throw LocalizeMeException(ErrorKind.NETWORK, e.message ?: e.javaClass.simpleName)
        }

    private fun errorFor(response: Transport.Response): LocalizeMeException {
        val message = runCatching { JSONObject(String(response.body)).optString("message") }
            .getOrNull()?.takeIf { it.isNotEmpty() } ?: "HTTP ${response.status}"
        return when (response.status) {
            401, 403 -> LocalizeMeException(ErrorKind.UNAUTHORIZED, message)
            else -> LocalizeMeException(ErrorKind.BAD_RESPONSE, message)
        }
    }

    companion object {
        /** The dashboard's platform for Android strings; a platform override there wins over `all`. */
        const val PLATFORM = "android"
        /** From `version` in build.gradle.kts, via BuildConfig. */
        const val SDK_VERSION: String = BuildConfig.SDK_VERSION
    }
}
