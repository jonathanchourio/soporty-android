package com.soporty.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Error devuelto por el servidor de Soporty (mensaje listo para mostrar). */
class ApiException(message: String, val status: Int) : IOException(message)

/**
 * Cliente HTTP mínimo para la API de Soporty.
 * Todas las peticiones llevan X-Soporty: 1; las de la sesión, X-Client-Auth: "<id>:<token>".
 */
object Api {
    val base: String = BuildConfig.SERVER_URL.trimEnd('/')

    /** Origen (esquema + host + puerto), para rutas absolutas que devuelve el servidor (p. ej. /api/file.php?id=). */
    val origin: String by lazy {
        val u = URL(base)
        u.protocol + "://" + u.host + (if (u.port > 0) ":" + u.port else "")
    }

    @Throws(IOException::class)
    fun post(path: String, body: JSONObject, auth: String? = null): JSONObject =
        request("POST", "$base/${path.trimStart('/')}", body.toString(), auth)

    @Throws(IOException::class)
    fun get(path: String, auth: String? = null): JSONObject =
        request("GET", "$base/${path.trimStart('/')}", null, auth)

    /** Descarga binaria (imágenes que manda el técnico). `url` puede ser absoluta o una ruta del servidor. */
    @Throws(IOException::class)
    fun bytes(url: String, auth: String?): ByteArray {
        val full = if (url.startsWith("http")) url else origin + (if (url.startsWith("/")) url else "/$url")
        val c = open(full, "GET", auth)
        try {
            if (c.responseCode >= 400) throw ApiException("No se pudo descargar la imagen", c.responseCode)
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    private fun open(url: String, method: String, auth: String?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10_000
        c.readTimeout = 25_000
        c.useCaches = false
        c.setRequestProperty("X-Soporty", "1")
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "SoportyAndroid/${BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL})")
        if (auth != null) c.setRequestProperty("X-Client-Auth", auth)
        return c
    }

    private fun request(method: String, url: String, body: String?, auth: String?): JSONObject {
        val c = open(url, method, auth)
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
            if (code >= 400 || !json.optBoolean("ok", false)) {
                throw ApiException(json.optString("error").ifEmpty { "Error del servidor ($code)" }, code)
            }
            return json
        } finally { c.disconnect() }
    }
}
