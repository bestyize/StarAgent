package com.starringbox.edge.tts

/**
 * Small dependency-free SHA-256 implementation.
 *
 * The token calculation is the only place this library needs hashing. Using a
 * common implementation keeps the protocol layer identical on JVM, Android and
 * Kotlin/Native without pulling a crypto dependency into every consumer.
 */
internal object Sha256 {
    private val k = longArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5,
        0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
        0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3,
        0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
        0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
        0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ).map { it.toInt() }.toIntArray()

    fun hexUpper(input: ByteArray): String =
        hash(input).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0').uppercase()
        }

    fun hash(input: ByteArray): ByteArray {
        var h0 = 0x6a09e667.toInt()
        var h1 = 0xbb67ae85.toInt()
        var h2 = 0x3c6ef372.toInt()
        var h3 = 0xa54ff53a.toInt()
        var h4 = 0x510e527f.toInt()
        var h5 = 0x9b05688c.toInt()
        var h6 = 0x1f83d9ab.toInt()
        var h7 = 0x5be0cd19.toInt()

        val message = input + padding(input.size.toLong())
        val w = IntArray(64)
        for (block in message.indices step 64) {
            for (i in 0 until 16) {
                val offset = block + i * 4
                w[i] = ((message[offset].toInt() and 0xff) shl 24) or
                    ((message[offset + 1].toInt() and 0xff) shl 16) or
                    ((message[offset + 2].toInt() and 0xff) shl 8) or
                    (message[offset + 3].toInt() and 0xff)
            }
            for (i in 16 until 64) {
                val s0 = rotr(w[i - 15], 7) xor rotr(w[i - 15], 18) xor (w[i - 15] ushr 3)
                val s1 = rotr(w[i - 2], 17) xor rotr(w[i - 2], 19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }

            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4
            var f = h5
            var g = h6
            var h = h7

            for (i in 0 until 64) {
                val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = h + s1 + ch + k[i] + w[i]
                val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj

                h = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }

            h0 += a
            h1 += b
            h2 += c
            h3 += d
            h4 += e
            h5 += f
            h6 += g
            h7 += h
        }

        return byteArrayOf(
            h0.byteAt(24), h0.byteAt(16), h0.byteAt(8), h0.byteAt(0),
            h1.byteAt(24), h1.byteAt(16), h1.byteAt(8), h1.byteAt(0),
            h2.byteAt(24), h2.byteAt(16), h2.byteAt(8), h2.byteAt(0),
            h3.byteAt(24), h3.byteAt(16), h3.byteAt(8), h3.byteAt(0),
            h4.byteAt(24), h4.byteAt(16), h4.byteAt(8), h4.byteAt(0),
            h5.byteAt(24), h5.byteAt(16), h5.byteAt(8), h5.byteAt(0),
            h6.byteAt(24), h6.byteAt(16), h6.byteAt(8), h6.byteAt(0),
            h7.byteAt(24), h7.byteAt(16), h7.byteAt(8), h7.byteAt(0),
        )
    }

    private fun padding(lengthBytes: Long): ByteArray {
        val bitLength = lengthBytes * 8
        val remainder = (lengthBytes + 9) % 64L
        val padBytes = if (remainder == 0L) 0 else (64 - remainder).toInt()
        val result = ByteArray(1 + padBytes + 8)
        result[0] = 0x80.toByte()
        for (i in 0 until 8) {
            result[result.size - 1 - i] = (bitLength ushr (8 * i)).toByte()
        }
        return result
    }

    private fun rotr(value: Int, amount: Int): Int =
        (value ushr amount) or (value shl (32 - amount))

    private fun Int.byteAt(shift: Int): Byte = (this ushr shift).toByte()
}
