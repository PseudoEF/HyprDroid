package dev.hyprdroid.launcher

import java.io.ByteArrayOutputStream

/** Two encoded one-pixel frames; never a production bitmap-frame cache. */
internal fun wallpaperTestGif(): ByteArray = ByteArrayOutputStream().apply {
    fun hex(value: String) { value.split(' ').filter { it.isNotEmpty() }.forEach { write(it.toInt(16)) } }
    write("GIF89a".toByteArray(Charsets.US_ASCII))
    hex("01 00 01 00 80 00 00 00 00 00 FF FF FF")
    hex("21 FF 0B"); write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII)); hex("03 01 00 00 00")
    for (code in listOf("44", "4C")) {
        hex("21 F9 04 00 0A 00 00 00 2C 00 00 00 00 01 00 01 00 00 02 02 $code 01 00")
    }
    hex("3B")
}.toByteArray()
