package com.mirunubi.bjstock.probe.intraday

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Column order from the official KIS sample `ccnl_krx.py` (H0STCNT0, 47 columns). */
object H0stcnt0Columns {
    val NAMES: List<String> = listOf(
        "MKSC_SHRN_ISCD", "STCK_CNTG_HOUR", "STCK_PRPR", "PRDY_VRSS_SIGN",
        "PRDY_VRSS", "PRDY_CTRT", "WGHN_AVRG_STCK_PRC", "STCK_OPRC",
        "STCK_HGPR", "STCK_LWPR", "ASKP1", "BIDP1", "CNTG_VOL", "ACML_VOL",
        "ACML_TR_PBMN", "SELN_CNTG_CSNU", "SHNU_CNTG_CSNU", "NTBY_CNTG_CSNU",
        "CTTR", "SELN_CNTG_SMTN", "SHNU_CNTG_SMTN", "CCLD_DVSN", "SHNU_RATE",
        "PRDY_VOL_VRSS_ACML_VOL_RATE", "OPRC_HOUR", "OPRC_VRSS_PRPR_SIGN",
        "OPRC_VRSS_PRPR", "HGPR_HOUR", "HGPR_VRSS_PRPR_SIGN", "HGPR_VRSS_PRPR",
        "LWPR_HOUR", "LWPR_VRSS_PRPR_SIGN", "LWPR_VRSS_PRPR", "BSOP_DATE",
        "NEW_MKOP_CLS_CODE", "TRHT_YN", "ASKP_RSQN1", "BIDP_RSQN1",
        "TOTAL_ASKP_RSQN", "TOTAL_BIDP_RSQN", "VOL_TNRT",
        "PRDY_SMNS_HOUR_ACML_VOL", "PRDY_SMNS_HOUR_ACML_VOL_RATE",
        "HOUR_CLS_CODE", "MRKT_TRTM_CLS_CODE", "VI_STND_PRC", "MARKET_CLS_CODE",
    )
    val WIDTH: Int = NAMES.size

    fun index(name: String): Int = NAMES.indexOf(name).also { require(it >= 0) }
}

enum class FrameIssue {
    ENCRYPTED_UNSUPPORTED,
    UNEXPECTED_TR,
    DECLARED_COUNT_NOT_NUMERIC,
    DECLARED_COUNT_NOT_POSITIVE,
    FIELD_COUNT_NOT_DIVISIBLE,
    WIDTH_MISMATCH,
}

/** Provider values are kept as the original strings; nothing is converted or given sub-second precision. */
data class TradeRecord(
    val indexInFrame: Int,
    val symbol: String,
    val tradeTimeHhmmss: String,
    val price: String,
    val tradeVolume: String,
    val cumulativeVolume: String,
    val businessDate: String,
    val newMarketOperationCode: String,
    val tradingHaltYn: String,
    val hourClassCode: String,
    val marketTerminationCode: String,
    val viStandardPrice: String,
    val marketClassCode: String,
    val conclusionType: String,
)

sealed interface KisStreamMessage {
    data class TradeFrame(
        val encryptedFlag: String,
        val trId: String,
        val declaredCountRaw: String,
        val declaredCount: Int?,
        val fieldCount: Int,
        val observedWidth: Int?,
        val records: List<TradeRecord>,
        val issue: FrameIssue?,
        val payloadSha256: String,
        val payloadLength: Int,
    ) : KisStreamMessage

    /** Only whitelisted fields; the body `output` (encryption iv / key) is never retained. */
    data class Control(
        val trId: String?,
        val trKey: String?,
        val encrypt: String?,
        val rtCd: String?,
        val msgCd: String?,
        val msg1: String?,
        val isPingPong: Boolean,
    ) : KisStreamMessage

    data class Malformed(val reason: String, val length: Int, val sha256: String) : KisStreamMessage
}

object H0stcnt0FrameParser {
    private val json = Json { ignoreUnknownKeys = true }

    private val iSymbol = H0stcnt0Columns.index("MKSC_SHRN_ISCD")
    private val iTime = H0stcnt0Columns.index("STCK_CNTG_HOUR")
    private val iPrice = H0stcnt0Columns.index("STCK_PRPR")
    private val iTradeVolume = H0stcnt0Columns.index("CNTG_VOL")
    private val iCumulativeVolume = H0stcnt0Columns.index("ACML_VOL")
    private val iConclusionType = H0stcnt0Columns.index("CCLD_DVSN")
    private val iBusinessDate = H0stcnt0Columns.index("BSOP_DATE")
    private val iNewMarketOperation = H0stcnt0Columns.index("NEW_MKOP_CLS_CODE")
    private val iTradingHalt = H0stcnt0Columns.index("TRHT_YN")
    private val iHourClass = H0stcnt0Columns.index("HOUR_CLS_CODE")
    private val iMarketTermination = H0stcnt0Columns.index("MRKT_TRTM_CLS_CODE")
    private val iViStandard = H0stcnt0Columns.index("VI_STND_PRC")
    private val iMarketClass = H0stcnt0Columns.index("MARKET_CLS_CODE")

    fun parse(raw: String): KisStreamMessage {
        if (raw.isEmpty()) return KisStreamMessage.Malformed("EMPTY", 0, sha256(raw))
        return when (raw[0]) {
            '0', '1' -> parseDataFrame(raw)
            '{' -> parseControl(raw)
            else -> KisStreamMessage.Malformed("UNKNOWN_PREFIX", raw.length, sha256(raw))
        }
    }

    private fun parseDataFrame(raw: String): KisStreamMessage {
        val parts = raw.split("|", limit = 4)
        if (parts.size < 4) return KisStreamMessage.Malformed("PIPE_PARTS", raw.length, sha256(raw))
        val (flag, trId, countRaw, payload) = parts
        val fields = payload.split("^")
        fun frame(issue: FrameIssue?, count: Int?, width: Int?, records: List<TradeRecord>) = KisStreamMessage.TradeFrame(
            encryptedFlag = flag,
            trId = trId,
            declaredCountRaw = countRaw,
            declaredCount = count,
            fieldCount = fields.size,
            observedWidth = width,
            records = records,
            issue = issue,
            payloadSha256 = sha256(payload),
            payloadLength = payload.length,
        )
        if (flag != "0") return frame(FrameIssue.ENCRYPTED_UNSUPPORTED, null, null, emptyList())
        if (trId != ProbeScope.STREAM_TR_ID) return frame(FrameIssue.UNEXPECTED_TR, null, null, emptyList())
        val count = countRaw.toIntOrNull() ?: return frame(FrameIssue.DECLARED_COUNT_NOT_NUMERIC, null, null, emptyList())
        if (count <= 0) return frame(FrameIssue.DECLARED_COUNT_NOT_POSITIVE, count, null, emptyList())
        if (fields.size % count != 0) return frame(FrameIssue.FIELD_COUNT_NOT_DIVISIBLE, count, null, emptyList())
        val width = fields.size / count
        // Any other width means column alignment is unproven, so no record is derived from misaligned fields.
        if (width != H0stcnt0Columns.WIDTH) return frame(FrameIssue.WIDTH_MISMATCH, count, width, emptyList())
        val records = (0 until count).map { index ->
            val record = fields.subList(index * width, (index + 1) * width)
            TradeRecord(
                indexInFrame = index,
                symbol = record[iSymbol],
                tradeTimeHhmmss = record[iTime],
                price = record[iPrice],
                tradeVolume = record[iTradeVolume],
                cumulativeVolume = record[iCumulativeVolume],
                businessDate = record[iBusinessDate],
                newMarketOperationCode = record[iNewMarketOperation],
                tradingHaltYn = record[iTradingHalt],
                hourClassCode = record[iHourClass],
                marketTerminationCode = record[iMarketTermination],
                viStandardPrice = record[iViStandard],
                marketClassCode = record[iMarketClass],
                conclusionType = record[iConclusionType],
            )
        }
        return frame(null, count, width, records)
    }

    private fun parseControl(raw: String): KisStreamMessage {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return KisStreamMessage.Malformed("CONTROL_JSON", raw.length, sha256(raw))
        val header = root["header"] as? JsonObject
            ?: return KisStreamMessage.Malformed("CONTROL_HEADER", raw.length, sha256(raw))
        val body = root["body"] as? JsonObject
        val trId = header.string("tr_id")
        return KisStreamMessage.Control(
            trId = trId,
            trKey = header.string("tr_key"),
            encrypt = header.string("encrypt"),
            rtCd = body?.string("rt_cd"),
            msgCd = body?.string("msg_cd"),
            msg1 = body?.string("msg1")?.take(MAX_MESSAGE_LENGTH),
            isPingPong = trId == "PINGPONG" && body == null,
        )
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private const val MAX_MESSAGE_LENGTH = 120
}
