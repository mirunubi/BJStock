package com.mirunubi.bjstock.core.instrument

import com.mirunubi.bjstock.core.model.Board

interface InstrumentMasterDownloader {
    suspend fun downloadMstBytes(board: Board): ByteArray
}
