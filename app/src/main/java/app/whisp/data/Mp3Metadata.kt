// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

/** Reads an MP3's frame-count index without downloading the audio payload. */
object Mp3Metadata {
    fun duration(size: Long, read: (Long, Int) -> ByteArray): Long {
        val tag = read(0, 10)
        val offset =
            if (tag.size >= 10 && tag.copyOfRange(0, 3).toString(Charsets.US_ASCII) == "ID3") {
                (6..9).fold(0L) { value, i -> (value shl 7) or (tag[i].toLong() and 0x7f) } +
                    10 +
                    (if ((tag[5].toInt() and 0x10) != 0) 10 else 0)
            } else 0L
        if (offset >= size) return 0
        val data = read(offset, minOf(8192L, size - offset).toInt())
        fun u(index: Int) = data.getOrNull(index)?.toInt()?.and(255) ?: 0
        fun int32(index: Int): Long =
            (0..3).fold(0L) { value, i -> (value shl 8) or u(index + i).toLong() }
        for (i in 0 until (data.size - 80).coerceAtLeast(0)) {
            if (u(i) != 255 || u(i + 1) and 0xe0 != 0xe0) continue
            val version = u(i + 1) shr 3 and 3
            if (version == 1 || u(i + 1) shr 1 and 3 != 1) continue // Layer III only.
            val sampleIndex = u(i + 2) shr 2 and 3
            val bitrateIndex = u(i + 2) shr 4 and 15
            if (sampleIndex == 3 || bitrateIndex !in 1..14) continue
            val sampleRate =
                intArrayOf(44100, 48000, 32000)[sampleIndex] /
                    when (version) {
                        3 -> 1
                        2 -> 2
                        else -> 4
                    }
            val samplesPerFrame = if (version == 3) 1152L else 576L
            val mono = u(i + 3) shr 6 == 3
            val sideInfo =
                if (version == 3) {
                    if (mono) 17 else 32
                } else {
                    if (mono) 9 else 17
                }
            val crc = if (u(i + 1) and 1 == 0) 2 else 0
            val index = i + 4 + crc + sideInfo
            val label = data.copyOfRange(index, index + 4).toString(Charsets.US_ASCII)
            val frames =
                when {
                    label in setOf("Xing", "Info") && int32(index + 4) and 1L != 0L ->
                        int32(index + 8)
                    data.copyOfRange(i + 36, i + 40).toString(Charsets.US_ASCII) == "VBRI" ->
                        int32(i + 50)
                    else -> 0L
                }
            return if (frames > 0) frames * samplesPerFrame * 1000 / sampleRate else 0
        }
        return 0
    }
}
