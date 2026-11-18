package com.eband.aligner

import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Native HTTP bridge to the radio. Requests are made from Kotlin (not from the web page),
 * so there are no CORS problems, Basic auth works, and self-signed HTTPS certificates
 * (common on radios) can be accepted.
 */
class RadioBridge(context: Context, private val webView: WebView) {

    private val prefs = context.getSharedPreferences("radio_settings", Context.MODE_PRIVATE)
    private val executor = Executors.newFixedThreadPool(2)

    private val defaults = mapOf(
        "ip" to "192.168.200.66",
        "port" to "",
        "https" to "true",
        "trustAll" to "true",
        "user" to "admin",
        "pass" to "admin",
        "rslPath" to "/tables/rf-rsl-status-table?deviceId=0&columns=rf-rsl-status-rsl",
        "jsonPath" to "data.0.values.0.0",
        "rslColumn" to "rf-rsl-status-rsl",
        "pollMs" to "500"
    )

    private fun setting(key: String): String = prefs.getString(key, null) ?: defaults[key] ?: ""

    @JavascriptInterface
    fun getSettings(): String {
        val o = JSONObject()
        defaults.keys.forEach { o.put(it, setting(it)) }
        return o.toString()
    }

    @JavascriptInterface
    fun saveSettings(json: String) {
        val o = JSONObject(json)
        val e = prefs.edit()
        defaults.keys.forEach { k -> if (o.has(k)) e.putString(k, o.get(k).toString()) }
        e.apply()
    }

    /** Async request. Result is delivered to window.__radioCallback(id, status, body, error). */
    @JavascriptInterface
    fun request(id: String, method: String, path: String, body: String) {
        executor.execute {
            var status = -1
            var text = ""
            var error = ""
            try {
                val result = doRequest(method.uppercase(), path, body)
                status = result.first
                text = result.second
            } catch (e: Exception) {
                error = e.javaClass.simpleName + ": " + (e.message ?: "")
            }
            val js = "window.__radioCallback(${JSONObject.quote(id)},$status," +
                "${JSONObject.quote(text)},${JSONObject.quote(error)})"
            webView.post { webView.evaluateJavascript(js, null) }
        }
    }

    private fun doRequest(method: String, path: String, body: String): Pair<Int, String> {
        val scheme = if (setting("https") == "true") "https" else "http"
        val ip = setting("ip").trim()
        val port = setting("port").trim()
        val portPart = if (port.isEmpty()) "" else ":$port"
        val p = if (path.startsWith("/")) path else "/$path"
        val url = URL("$scheme://$ip$portPart$p")

        val conn = url.openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection && setting("trustAll") == "true") {
            conn.sslSocketFactory = trustAllFactory
            conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        conn.requestMethod = method
        conn.setRequestProperty("Accept", "application/json")

        val user = setting("user")
        if (user.isNotEmpty()) {
            val token = Base64.encodeToString("$user:${setting("pass")}".toByteArray(), Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $token")
        }
        if (body.isNotEmpty() && method != "GET") {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }

        return try {
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            code to text
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private val trustAllFactory: SSLSocketFactory by lazy {
            val tm = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            SSLContext.getInstance("TLS").apply { init(null, tm, SecureRandom()) }.socketFactory
        }
    }
}
