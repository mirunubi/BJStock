package com.mirunubi.bjstock.probe.intraday

import com.mirunubi.bjstock.core.kis.KisCredentials
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class ProbeTransportException(val code: ProbeErrorCode, val httpStatus: Int? = null) :
    IOException("${code.name}${httpStatus?.let { " HTTP $it" } ?: ""}")

data class MinuteBarObservation(
    val httpStatus: Int,
    val rtCd: String?,
    val msgCd: String?,
    val msg1: String?,
    val rowCount: Int,
    val firstRowBusinessDate: String?,
    val firstRowTradeHour: String?,
    val lastRowTradeHour: String?,
)

interface ProbeSocket {
    fun send(text: String): Boolean
    fun close(code: Int, reason: String)
}

interface ProbeSocketCallbacks {
    fun onOpen()
    fun onText(text: String)
    fun onClosed(code: Int)
    fun onFailure(error: Throwable)
}

interface ProbeNetwork {
    suspend fun issueToken(credentials: KisCredentials): ProbeSecret
    suspend fun requestApprovalKey(credentials: KisCredentials): ProbeSecret
    suspend fun fetchTodayMinuteBars(
        token: ProbeSecret,
        credentials: KisCredentials,
        symbol: String,
        inputHourHhmmss: String,
    ): MinuteBarObservation
    fun connect(callbacks: ProbeSocketCallbacks): ProbeSocket
}

/** No HTTP logging interceptor is installed: request headers carry credentials and are never logged. */
class KisProbeTransport internal constructor(
    private val endpoints: ProbeEndpoints,
    private val restClient: OkHttpClient,
    private val wsClient: OkHttpClient,
) : ProbeNetwork {

    override suspend fun issueToken(credentials: KisCredentials): ProbeSecret {
        val body = buildJsonObject {
            put("grant_type", "client_credentials")
            put("appkey", credentials.appKey)
            put("appsecret", credentials.appSecret)
        }
        val root = postJson(ProbePaths.TOKEN, body, ProbeErrorCode.VIRTUAL_TOKEN_FAILED)
        return ProbeSecret(root.string("access_token") ?: throw ProbeTransportException(ProbeErrorCode.VIRTUAL_TOKEN_FAILED))
    }

    override suspend fun requestApprovalKey(credentials: KisCredentials): ProbeSecret {
        val body = buildJsonObject {
            put("grant_type", "client_credentials")
            put("appkey", credentials.appKey)
            put("secretkey", credentials.appSecret)
        }
        val root = postJson(ProbePaths.APPROVAL, body, ProbeErrorCode.WS_APPROVAL_FAILED)
        return ProbeSecret(root.string("approval_key") ?: throw ProbeTransportException(ProbeErrorCode.WS_APPROVAL_FAILED))
    }

    override suspend fun fetchTodayMinuteBars(
        token: ProbeSecret,
        credentials: KisCredentials,
        symbol: String,
        inputHourHhmmss: String,
    ): MinuteBarObservation = withContext(Dispatchers.IO) {
        val url = (endpoints.restBaseUrl + ProbePaths.MINUTE_BARS).toHttpUrl().newBuilder()
            .addQueryParameter("FID_COND_MRKT_DIV_CODE", ProbeScope.VENUE_CODE)
            .addQueryParameter("FID_INPUT_ISCD", symbol)
            .addQueryParameter("FID_INPUT_HOUR_1", inputHourHhmmss)
            .addQueryParameter("FID_PW_DATA_INCU_YN", "N")
            .addQueryParameter("FID_ETC_CLS_CODE", "")
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("content-type", "application/json; charset=utf-8")
            .header("authorization", "Bearer ${token.reveal()}")
            .header("appkey", credentials.appKey)
            .header("appsecret", credentials.appSecret)
            .header("tr_id", ProbeScope.MINUTE_BAR_TR_ID)
            .header("custtype", ProbeScope.CUSTOMER_TYPE)
            .build()
        restClient.newCall(request).execute().use { response ->
            val root = parseBody(response, ProbeErrorCode.REST_FAILED)
            val rows = root["output2"] as? JsonArray
            val first = rows?.firstOrNull() as? JsonObject
            val last = rows?.lastOrNull() as? JsonObject
            MinuteBarObservation(
                httpStatus = response.code,
                rtCd = root.string("rt_cd"),
                msgCd = root.string("msg_cd"),
                msg1 = root.string("msg1")?.take(120),
                rowCount = rows?.size ?: 0,
                firstRowBusinessDate = first?.string("stck_bsop_date"),
                firstRowTradeHour = first?.string("stck_cntg_hour"),
                lastRowTradeHour = last?.string("stck_cntg_hour"),
            )
        }
    }

    override fun connect(callbacks: ProbeSocketCallbacks): ProbeSocket {
        val socket = wsClient.newWebSocket(
            Request.Builder().url(endpoints.wsUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = callbacks.onOpen()
                override fun onMessage(webSocket: WebSocket, text: String) = callbacks.onText(text)
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = callbacks.onClosed(code)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = callbacks.onFailure(t)
            },
        )
        return object : ProbeSocket {
            override fun send(text: String): Boolean = socket.send(text)
            override fun close(code: Int, reason: String) {
                socket.close(code, reason)
            }
        }
    }

    private suspend fun postJson(path: String, body: JsonObject, failure: ProbeErrorCode): JsonObject =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(endpoints.restBaseUrl + path)
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            restClient.newCall(request).execute().use { response -> parseBody(response, failure) }
        }

    private fun parseBody(response: Response, failure: ProbeErrorCode): JsonObject {
        if (!response.isSuccessful) throw ProbeTransportException(failure, response.code)
        val text = response.body?.string() ?: throw ProbeTransportException(failure, response.code)
        return runCatching { JSON.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw ProbeTransportException(failure, response.code)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun create(pass: VirtualGatePass): KisProbeTransport = forEndpoints(pass.endpoints)

        internal fun forEndpoints(endpoints: ProbeEndpoints): KisProbeTransport {
            val base = OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
            val restClient = base.newBuilder()
                .callTimeout(20, TimeUnit.SECONDS)
                .addInterceptor(ProbeRestAllowlistInterceptor(ProbeRestAllowlist(endpoints)))
                .build()
            val wsClient = base.newBuilder()
                .readTimeout(0, TimeUnit.SECONDS)
                .addInterceptor(ProbeWebSocketEndpointInterceptor(ProbeWebSocketAllowlist(endpoints)))
                .build()
            return KisProbeTransport(endpoints, restClient, wsClient)
        }
    }
}
