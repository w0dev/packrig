package net.packset.data.qrz

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Real HTTP transport for the QRZ Logbook API. Form-encoded POST over
 * HTTPS, 10 s timeouts. Never logs or echoes the API key.
 */
class HttpQrzClient(
    private val endpoint: String = "https://logbook.qrz.com/api",
) : QrzClient {

    override suspend fun status(apiKey: String): QrzOutcome =
        post(linkedMapOf("KEY" to apiKey, "ACTION" to "STATUS"))

    override suspend fun insert(apiKey: String, adifRecord: String): QrzOutcome =
        post(linkedMapOf("KEY" to apiKey, "ACTION" to "INSERT", "ADIF" to adifRecord))

    private suspend fun post(params: Map<String, String>): QrzOutcome =
        withContext(Dispatchers.IO) {
            try {
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = TIMEOUT_MS
                    connection.readTimeout = TIMEOUT_MS
                    connection.doOutput = true
                    connection.setRequestProperty(
                        "Content-Type", "application/x-www-form-urlencoded",
                    )
                    connection.outputStream.use {
                        it.write(QrzWire.encodeForm(params).toByteArray(Charsets.UTF_8))
                    }
                    val code = connection.responseCode
                    if (code != HttpURLConnection.HTTP_OK) {
                        return@withContext QrzOutcome.Failure("QRZ returned HTTP $code")
                    }
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    interpretResponse(QrzWire.parse(body))
                } finally {
                    connection.disconnect()
                }
            } catch (e: IOException) {
                QrzOutcome.Failure(e.message ?: "Network error")
            }
        }

    private companion object {
        const val TIMEOUT_MS = 10_000
    }
}
