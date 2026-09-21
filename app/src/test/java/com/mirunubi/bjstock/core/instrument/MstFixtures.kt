package com.mirunubi.bjstock.core.instrument

import com.mirunubi.bjstock.core.model.Board
import java.nio.charset.Charset

object MstFixtures {
    private val charset: Charset = Charset.forName("MS949")

    fun kospiLine(
        symbol: String,
        standardCode: String,
        name: String,
        spac: String = "N",
        etp: String = "N",
        preferred: String = "N",
        listedDate: String = "        ",
    ): ByteArray = line(
        symbol = symbol,
        standardCode = standardCode,
        name = name,
        tailLen = InstrumentMasterConfig.KOSPI_TAIL_BYTES,
        etpOffset = 22,
        spacOffset = 29,
        listedDateOffset = 105,
        preferredOffset = 158,
        spac = spac,
        etp = etp,
        preferred = preferred,
        listedDate = listedDate,
    )

    fun kosdaqLine(
        symbol: String,
        standardCode: String,
        name: String,
        spac: String = "N",
        etp: String = "N",
        preferred: String = "N",
        listedDate: String = "        ",
    ): ByteArray = line(
        symbol = symbol,
        standardCode = standardCode,
        name = name,
        tailLen = InstrumentMasterConfig.KOSDAQ_TAIL_BYTES,
        etpOffset = 18,
        spacOffset = 24,
        listedDateOffset = 100,
        preferredOffset = 153,
        spac = spac,
        etp = etp,
        preferred = preferred,
        listedDate = listedDate,
    )

    fun join(vararg lines: ByteArray): ByteArray {
        val newline = byteArrayOf(0x0A)
        val size = lines.sumOf { it.size + 1 }
        val out = ByteArray(size)
        var offset = 0
        lines.forEach { line ->
            line.copyInto(out, offset)
            offset += line.size
            newline.copyInto(out, offset)
            offset += 1
        }
        return out
    }

    private fun line(
        symbol: String,
        standardCode: String,
        name: String,
        tailLen: Int,
        etpOffset: Int,
        spacOffset: Int,
        listedDateOffset: Int,
        preferredOffset: Int,
        spac: String,
        etp: String,
        preferred: String,
        listedDate: String,
    ): ByteArray {
        val part1 = pad(symbol, 9) + pad(standardCode, 12) + name.toByteArray(charset)
        val tail = ByteArray(tailLen) { 0x20 }
        writeAscii(tail, etpOffset, etp)
        writeAscii(tail, spacOffset, spac)
        writeAscii(tail, preferredOffset, preferred)
        writeAscii(tail, listedDateOffset, listedDate)
        return part1 + tail
    }

    private fun pad(value: String, length: Int): ByteArray {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(length) { 0x20 }
        bytes.copyInto(out, 0, 0, minOf(bytes.size, length))
        return out
    }

    private fun writeAscii(target: ByteArray, offset: Int, value: String) {
        value.toByteArray(Charsets.US_ASCII).forEachIndexed { index, byte ->
            if (offset + index < target.size) {
                target[offset + index] = byte
            }
        }
    }
}
