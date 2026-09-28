package com.mirunubi.bjstock.core.instrument

import com.mirunubi.bjstock.core.model.Board
import java.nio.charset.Charset

object MstFixtures {
    private val charset: Charset = Charset.forName("MS949")
    private const val NAME_FIELD_BYTES = 40

    fun kospiLine(
        symbol: String,
        standardCode: String,
        name: String,
        groupCode: String = "ST",
        spac: String = "N",
        etp: String = " ",
        preferred: String = "0",
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
        groupCode = groupCode,
        spac = spac,
        etp = etp,
        preferred = preferred,
        listedDate = listedDate,
    )

    fun kosdaqLine(
        symbol: String,
        standardCode: String,
        name: String,
        groupCode: String = "ST",
        spac: String = "N",
        etp: String = " ",
        preferred: String = "0",
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
        groupCode = groupCode,
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
        groupCode: String,
        spac: String,
        etp: String,
        preferred: String,
        listedDate: String,
    ): ByteArray {
        val nameBytes = name.toByteArray(charset)
        val nameField = if (nameBytes.size < NAME_FIELD_BYTES) {
            nameBytes + ByteArray(NAME_FIELD_BYTES - nameBytes.size) { 0x20 }
        } else {
            nameBytes
        }
        val part1 = pad(symbol, 9) + pad(standardCode, 12) + nameField
        val tail = ByteArray(tailLen) { 0x20 }
        writeAscii(tail, 0, groupCode)
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

/** Byte-exact lines copied from the live KIS master files (2026-09-29), base64 of raw CP949 bytes. */
object RealMstLines {
    // KOSPI, 288 bytes each
    const val KOSPI_005930 =
        "MDA1OTMwICAgS1I3MDA1OTMwMDAzu++8usD8wNogICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgIFNUMTAwMjcwMDEzMDAwMCBOTjVZWVkgWVlOTk5OTk5OME5OTk5OTk5ZMDAwMjcwMDAwMDAwMDEwMDAwMU5OTjAwTk5OMDUwMDAwMDIwWTA5MDAwMDAyMTM0NjA2NDAwMDAwMDAwMDEwMDE5NzUwNjExMDAwMDAwMDA1ODQ2Mjc4MDAwMDAwMDAwNzc4MDQ2Njg1MDAwMTIgICAgICAgMCBOWVkwMDMwNTM3MjkwMDE0NjcyNTIwMDE1MzI2ODIxMTg4NDAwMDAzMS4zOTIwMjYwNjMwMDE1Nzg0OTUyNTExTk5Z"
    const val KOSPI_000660 =
        "MDAwNjYwICAgS1I3MDAwNjYwMDAxU0vHz8DMtNC9uiAgICAgICAgICAgICAgICAgICAgICAgICAgICAgIFNUMTAwMjcwMDEzMDAwMCBOTjVZWVkgWVlOWU5OTk5OME5OTk5OTk5ZMDAxNzY4MDAwMDAwMDEwMDAwMU5OTjAwTk5OMDAwMDAwMDIwWTA5MDAwMDAwMzMwMjc0ODAwMDAwMDAwNTAwMDE5OTYxMjI2MDAwMDAwMDAwNzMwNDkyMDAwMDAwMDAzNzQ2NjAyMDUwMDAwMTIgICAgICAgMCBOWVkwMDEzMTg5NTAwMDA5ODE1MjkwMDE3NDMyNTIxMzQyNjAwMDA5Mi42ODIwMjYwNjMwMDEyOTE1MTA1NTM1Tk5O"
    const val KOSPI_005935 =
        "MDA1OTM1ICAgS1I3MDA1OTMxMDAxu++8usD8wNq/7CAgICAgICAgICAgICAgICAgICAgICAgICAgICAgIFNUMDAwMjcwMDEzMDAwMCBOTjBOTk4gTk5OTk5OTk5OME5OTk5OTk5OMDAwMjA3MDAwMDAwMDEwMDAwMU5OTjAwTk5OMDUwMDAwMDIwWTA5MDAwMDAwNjg3NTkyOTAwMDAwMDAwMDEwMDE5ODkwOTI1MDAwMDAwMDAwODAyMzcxMDAwMDAwMDAwMTE5NDY3MTM1MDAwMTIgICAgICAgMSBOTk4wMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMC4wMCAgICAgICAgMDAxNjYwOTA4ICAgTk5Z"
    const val KOSPI_000087 =
        "MDAwMDg3ICAgS1I3MDAwMDgyMDA4x8/AzMauwfi3zjK/7EIgICAgICAgICAgICAgICAgICAgICAgICAgIFNUMDAwMjcwMDA1MDAwMCBOTjBOTk4gTk5OTk5OTk5OME5OTk5OTk5OMDAwMDEyMzEwMDAwMDEwMDAwMU5OTjAwTk5OMDAwMDAwMTAwTjA5MDAwMDAwMDAwMjEwNjAwMDAwMDAwNTAwMDIwMTEwOTI2MDAwMDAwMDAwMDAxMTMwMDAwMDAwMDAwMDA1NjUwNjkwMDAwMTIgICAgICAgMiBOTk4wMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMC4wMCAgICAgICAgMDAwMDAwMTM5ICAgTk5O"
    const val KOSPI_069500 =
        "MDY5NTAwICAgS1I3MDY5NTAwMDA3S09ERVggMjAwICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgIEVGIDAwMDAwMDAwMDAwME5OTjBOTk4yTk5OTk5OTk5OME5OWU5OTk5OMDAwMTA5NTAwMDAwMDEwMDAwMU5OTjAwTk5OMDAwMDAwMDMwWTA5MDAwMDAyMzYxMTQ2MDAwMDAwMDAwMDAwMDIwMDIxMDE0MDAwMDAwMDAwMjI3ODAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMTIgICAgICAgMCBOTk4wMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMC4wMCAgICAgICAgMDAwMjQ5NDQxICAgTk5Z"
    const val KOSPI_088260_REIT =
        "MDg4MjYwICAgS1I3MDg4MjYwMDA1wMy4rsP3xNrFqbe+ICAgICAgICAgICAgICAgICAgICAgICAgICAgIFJUMzAwMjgwMDAwMDAwMCBOIDBOTk4gTk4gICAgTiAgMCAgWSAgICAgMDAwMDAzMjk1MDAwMDEwMDAwMU5OTjAwTk5OMDAwMDAwMTAwTjA5MDAwMDAwMDA0ODY1NTAwMDAwMDAwMDUwMDIwMTgwNjI3MDAwMDAwMDAwMDYzMzQxMDAwMDAwMDAwMDMxNjcwNzk1MDAwICAgICAgICAgMCBOTlkwMDAwMDAyMzMwMDAwMDAxODMwMDAwMDAwNjgwMDA2ODAwMDAwNi4zODIwMjYwNjMwMDAwMDAyMDg3NjUyTk5O"
    const val KOSPI_276970_LONG_NAME =
        "Mjc2OTcwICAgS1I3Mjc2OTcwMDAxS09ERVggucyxuVMmUDUwMLnotOexzcG3xL+59rXlxN0ox9W8uiBIKUVGIDAwMDAwMDAwMDAwME5OIDBOTk4yTk4gICAgTiAgMCAgWSAgICAgMDAwMDA3NDI1MDAwMDEwMDAwMU5OTjAwTk5OMDAwMDAwMTAwTjA5MDAwMDAwMDAzNTU4NTAwMDAwMDAwMDAwMDIwMTcwODEwMDAwMDAwMDAwMDA0NzAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMTIgICAgICAgMCBOTk4wMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMC4wMCAgICAgICAgMDAwMDAwMzQ4ICAgTk5O"

    // KOSDAQ, 282 bytes each
    const val KOSDAQ_247540 =
        "MjQ3NTQwICAgS1I3MjQ3NTQwMDA4v6HE2sfBt8668b+lICAgICAgICAgICAgICAgICAgICAgICAgICAgIFNUMTEwMDkxMDI4MDAwMCBOWSBZICAgIE4gIDAgIE4gICAgWTAwMDExMTMwMDAwMDAxMDAwMDFOTk4wME5OTjAwMDAwMDA0MFkwOTAwMDAwMDA1ODA2NTYwMDAwMDAwMDA1MDAyMDE5MDMwNTAwMDAwMDAwMDA5NzgzMDAwMDAwMDAwMDA0ODk1NTQ1NDAwMDEyICAgICAgIDAgTlkwMDAwMTE4MjEwMDAwMDAzOTAwMDAwMDAxNDIwMDExNzAwMDAwMDAwMzIwMjYwNjMwMDAwMTA4ODg1TEQyTk5O"
    const val KOSDAQ_466690_SPAC =
        "NDY2NjkwICAgS1I3NDY2NjkwMDA1xbC/8sj3vu63zsGmMcijvbrG0SAgICAgICAgICAgICAgICAgICAgIFNUMzEwMTQwMDAwMDAwMCBOTiBOICAgIFkgIDAgIE4gICAgTjAwMDAwMjAwNTAwMDAxMDAwMDFOTk4wME5OTjAwMDAwMDEwME4wOTAwMDAwMDAwMDE2MDgwMDAwMDAwMDAxMDAyMDI1MTIxMjAwMDAwMDAwMDAwNTA5NTAwMDAwMDAwMDAwMDUwOTUwMDAwMDEyICAgICAgIDAgTk4wMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMC0wMDAwMDAwNTIwMjQxMjMxMDAwMDAwMTAyICAgTk5O"
    const val KOSDAQ_900110_FOREIGN =
        "OTAwMTEwICAgSEswMDAwMDU3MTk3tfbEv7jTvbogICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgIEZTIDAwMDAwMDAwMDAwME5OTiBOTk5OTk5OTjBOTk5OTk5OTjAwMDAwNTM4MDAwMDAxMDAwMDFOTk4wME5OTjAwMDAwMDEwME4wOTAwMDAwMDAwMDI1MjEwMDAwMDAwMDAwMDAyMDEwMDQyMzAwMDAwMDAwMDAwMzY4NzAwMDAwMDAwMDAwMDk5Mzc1NTQ5MTEyICAgICAgIDAgTk4wMDAwMDA0NzcwMDAwMDAwNTkwMDAwMDAwNzcwMDA1NjAwMDAwMDAwMjIwMjUwOTMwMDAwMDAwMTk4ICAgTk5O"

    fun decode(base64: String): ByteArray = java.util.Base64.getDecoder().decode(base64)
}
