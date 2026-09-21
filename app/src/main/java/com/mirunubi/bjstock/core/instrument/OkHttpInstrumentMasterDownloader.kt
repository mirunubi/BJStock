package com.mirunubi.bjstock.core.instrument

import com.mirunubi.bjstock.core.model.Board
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class OkHttpInstrumentMasterDownloader(
    private val client: OkHttpClient,
) : InstrumentMasterDownloader {
    override suspend fun downloadMstBytes(board: Board): ByteArray = withContext(Dispatchers.IO) {
        val url = InstrumentMasterConfig.url(board)
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw InstrumentMasterException(
                    kind = InstrumentMasterErrorKind.DOWNLOAD_FAILED,
                    publicMessage = "Instrument master download failed",
                )
            }
            val body = response.body ?: throw InstrumentMasterException(
                kind = InstrumentMasterErrorKind.DOWNLOAD_FAILED,
                publicMessage = "Instrument master download failed",
            )
            unzipMst(body.bytes())
        }
    }

    private fun unzipMst(zipBytes: ByteArray): ByteArray {
        ZipInputStream(zipBytes.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".mst", ignoreCase = true)) {
                    return zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }
        throw InstrumentMasterException(
            kind = InstrumentMasterErrorKind.DOWNLOAD_FAILED,
            publicMessage = "Instrument master zip did not contain an MST file",
        )
    }
}
