/*
 * Erawan one-tap connect API client.
 * Talks to /app/register, /app/servers, /app/connect on the Erawan backend
 * (api/routes/app.py). Field names below were verified against that file
 * directly — do not change them without re-checking the backend.
 */
package org.amnezia.awg.erawan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class ErawanServer(
    val id: Int,
    val name: String,
    val country: String,
    val protocol: String,
    val currentLoad: Int
)

data class ErawanConnectResult(
    val config: String,
    val serverName: String,
    val protocol: String,
    val expiresAt: String
)

/**
 * [reasonCode] is the backend's `detail` string (e.g. "invalid_token",
 * "servers_busy_try_again") so callers can map it to a localized message.
 */
class ErawanApiException(val reasonCode: String, val httpStatus: Int, message: String) : IOException(message)

object ErawanApi {
    private const val BASE_URL = "https://erawangroups.com"
    private const val TIMEOUT_MS = 15_000

    suspend fun register(deviceId: String, platform: String = "android"): Pair<String, String> = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("device_id", deviceId)
            put("platform", platform)
        }
        val response = request("POST", "/app/register", body, null)
        Pair(response.getString("app_token"), response.getString("tier"))
    }

    suspend fun connect(appToken: String, serverId: Int? = null): ErawanConnectResult = withContext(Dispatchers.IO) {
        val body = JSONObject()
        if (serverId != null) body.put("server_id", serverId)
        val response = request("POST", "/app/connect", body, appToken)
        ErawanConnectResult(
            config = response.getString("config"),
            serverName = response.getString("server_name"),
            protocol = response.getString("protocol"),
            expiresAt = response.getString("expires_at")
        )
    }

    suspend fun servers(appToken: String): List<ErawanServer> = withContext(Dispatchers.IO) {
        val array = requestArray("GET", "/app/servers", appToken)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            ErawanServer(
                id = o.getInt("id"),
                name = o.getString("name"),
                country = o.getString("country"),
                protocol = o.getString("protocol"),
                currentLoad = o.getInt("current_load")
            )
        }
    }

    private fun openConnection(path: String, method: String, appToken: String?): HttpURLConnection {
        val connection = URL(BASE_URL + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json")
        if (appToken != null) connection.setRequestProperty("Authorization", "Bearer $appToken")
        return connection
    }

    private fun writeBody(connection: HttpURLConnection, body: JSONObject) {
        connection.doOutput = true
        connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
    }

    private fun readStream(connection: HttpURLConnection): String =
        connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

    private fun readErrorDetail(connection: HttpURLConnection): String? = try {
        connection.errorStream
            ?.bufferedReader(StandardCharsets.UTF_8)
            ?.use { it.readText() }
            ?.let { JSONObject(it) }
            ?.takeIf { it.has("detail") }
            ?.getString("detail")
    } catch (e: Exception) {
        null
    }

    private fun throwForStatus(connection: HttpURLConnection): Nothing {
        val code = connection.responseCode
        val detail = readErrorDetail(connection) ?: "http_$code"
        throw ErawanApiException(detail, code, "Erawan API error $code: $detail")
    }

    private fun request(method: String, path: String, body: JSONObject, appToken: String?): JSONObject {
        val connection = openConnection(path, method, appToken)
        try {
            writeBody(connection, body)
            if (connection.responseCode !in 200..299) throwForStatus(connection)
            return JSONObject(readStream(connection))
        } finally {
            connection.disconnect()
        }
    }


    data class BillingVerifyResult(val tier: String, val expiresAt: String?, val status: String)

    suspend fun verifyPurchase(appToken: String, purchaseToken: String, productId: String, orderId: String?): BillingVerifyResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("purchase_token", purchaseToken)
            put("product_id", productId)
            if (orderId != null) put("order_id", orderId)
        }
        val response = request("POST", "/app/billing/verify", body, appToken)
        BillingVerifyResult(
            tier = response.getString("tier"),
            expiresAt = response.optString("expires_at").ifEmpty { null },
            status = response.getString("status")
        )
    }

    private fun requestArray(method: String, path: String, appToken: String?): JSONArray {
        val connection = openConnection(path, method, appToken)
        try {
            if (connection.responseCode !in 200..299) throwForStatus(connection)
            return JSONArray(readStream(connection))
        } finally {
            connection.disconnect()
        }
    }
}
