package com.mardous.booming.data.local.lyrics.ttml

/**
 * 纯 Kotlin 原生手搓的魔改 Triple DES 引擎
 * 完全剥离 UInt 实验特性，使用标准 Int 与 ushr 确保 100% 编译兼容
 */
object QQMusicDES {

    private const val ENCRYPT = 1
    private const val DECRYPT = 0

    private val sbox = arrayOf(
        intArrayOf(
            14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
            0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
            4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
            15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13
        ),
        intArrayOf(
            15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
            3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5,
            0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
            13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9
        ),
        intArrayOf(
            10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
            13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
            13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
            1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12
        ),
        intArrayOf(
            7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
            13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
            10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
            3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14
        ),
        intArrayOf(
            2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
            14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
            4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
            11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3
        ),
        intArrayOf(
            12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
            10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
            9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
            4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13
        ),
        intArrayOf(
            4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
            13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
            1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
            6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12
        ),
        intArrayOf(
            13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
            1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
            7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
            2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11
        )
    )

    private val QRC_KEY = "!@#)(*\$%123ZXC!@!@#)(NHL".toByteArray(Charsets.UTF_8)
    private val tripleDesKeys by lazy { tripledes_key_setup(QRC_KEY, DECRYPT) }

    // ====== 底层位运算工具 (全系 Int + ushr) ======
    private fun bitnum(a: ByteArray, b: Int, c: Int): Int {
        val byteVal = a[(b / 32) * 4 + 3 - (b % 32) / 8].toInt() and 0xFF
        return ((byteVal ushr (7 - b % 8)) and 1) shl c
    }

    private fun bitnum_intr(a: Int, b: Int, c: Int): Int {
        return ((a ushr (31 - b)) and 1) shl c
    }

    private fun bitnum_intl(a: Int, b: Int, c: Int): Int {
        return ((a ushr (31 - b)) and 1) shl (31 - c)
    }

    private fun sbox_bit(a: Int): Int {
        return (a and 32) or ((a and 31) ushr 1) or ((a and 1) shl 4)
    }

    // ====== 魔改 DES 置换过程 ======
    private fun initial_permutation(input_data: ByteArray): Pair<Int, Int> {
        val s0 = (bitnum(input_data, 57, 31) or bitnum(input_data, 49, 30) or bitnum(input_data, 41, 29) or bitnum(input_data, 33, 28) or
                  bitnum(input_data, 25, 27) or bitnum(input_data, 17, 26) or bitnum(input_data, 9, 25) or bitnum(input_data, 1, 24) or
                  bitnum(input_data, 59, 23) or bitnum(input_data, 51, 22) or bitnum(input_data, 43, 21) or bitnum(input_data, 35, 20) or
                  bitnum(input_data, 27, 19) or bitnum(input_data, 19, 18) or bitnum(input_data, 11, 17) or bitnum(input_data, 3, 16) or
                  bitnum(input_data, 61, 15) or bitnum(input_data, 53, 14) or bitnum(input_data, 45, 13) or bitnum(input_data, 37, 12) or
                  bitnum(input_data, 29, 11) or bitnum(input_data, 21, 10) or bitnum(input_data, 13, 9) or bitnum(input_data, 5, 8) or
                  bitnum(input_data, 63, 7) or bitnum(input_data, 55, 6) or bitnum(input_data, 47, 5) or bitnum(input_data, 39, 4) or
                  bitnum(input_data, 31, 3) or bitnum(input_data, 23, 2) or bitnum(input_data, 15, 1) or bitnum(input_data, 7, 0))

        val s1 = (bitnum(input_data, 56, 31) or bitnum(input_data, 48, 30) or bitnum(input_data, 40, 29) or bitnum(input_data, 32, 28) or
                  bitnum(input_data, 24, 27) or bitnum(input_data, 16, 26) or bitnum(input_data, 8, 25) or bitnum(input_data, 0, 24) or
                  bitnum(input_data, 58, 23) or bitnum(input_data, 50, 22) or bitnum(input_data, 42, 21) or bitnum(input_data, 34, 20) or
                  bitnum(input_data, 26, 19) or bitnum(input_data, 18, 18) or bitnum(input_data, 10, 17) or bitnum(input_data, 2, 16) or
                  bitnum(input_data, 60, 15) or bitnum(input_data, 52, 14) or bitnum(input_data, 44, 13) or bitnum(input_data, 36, 12) or
                  bitnum(input_data, 28, 11) or bitnum(input_data, 20, 10) or bitnum(input_data, 12, 9) or bitnum(input_data, 4, 8) or
                  bitnum(input_data, 62, 7) or bitnum(input_data, 54, 6) or bitnum(input_data, 46, 5) or bitnum(input_data, 38, 4) or
                  bitnum(input_data, 30, 3) or bitnum(input_data, 22, 2) or bitnum(input_data, 14, 1) or bitnum(input_data, 6, 0))
        return Pair(s0, s1)
    }

    private fun inverse_permutation(s0: Int, s1: Int): ByteArray {
        val data = ByteArray(8)
        data[3] = (bitnum_intr(s1, 7, 7) or bitnum_intr(s0, 7, 6) or bitnum_intr(s1, 15, 5) or
                   bitnum_intr(s0, 15, 4) or bitnum_intr(s1, 23, 3) or bitnum_intr(s0, 23, 2) or
                   bitnum_intr(s1, 31, 1) or bitnum_intr(s0, 31, 0)).toByte()

        data[2] = (bitnum_intr(s1, 6, 7) or bitnum_intr(s0, 6, 6) or bitnum_intr(s1, 14, 5) or
                   bitnum_intr(s0, 14, 4) or bitnum_intr(s1, 22, 3) or bitnum_intr(s0, 22, 2) or
                   bitnum_intr(s1, 30, 1) or bitnum_intr(s0, 30, 0)).toByte()

        data[1] = (bitnum_intr(s1, 5, 7) or bitnum_intr(s0, 5, 6) or bitnum_intr(s1, 13, 5) or
                   bitnum_intr(s0, 13, 4) or bitnum_intr(s1, 21, 3) or bitnum_intr(s0, 21, 2) or
                   bitnum_intr(s1, 29, 1) or bitnum_intr(s0, 29, 0)).toByte()

        data[0] = (bitnum_intr(s1, 4, 7) or bitnum_intr(s0, 4, 6) or bitnum_intr(s1, 12, 5) or
                   bitnum_intr(s0, 12, 4) or bitnum_intr(s1, 20, 3) or bitnum_intr(s0, 20, 2) or
                   bitnum_intr(s1, 28, 1) or bitnum_intr(s0, 28, 0)).toByte()

        data[7] = (bitnum_intr(s1, 3, 7) or bitnum_intr(s0, 3, 6) or bitnum_intr(s1, 11, 5) or
                   bitnum_intr(s0, 11, 4) or bitnum_intr(s1, 19, 3) or bitnum_intr(s0, 19, 2) or
                   bitnum_intr(s1, 27, 1) or bitnum_intr(s0, 27, 0)).toByte()

        data[6] = (bitnum_intr(s1, 2, 7) or bitnum_intr(s0, 2, 6) or bitnum_intr(s1, 10, 5) or
                   bitnum_intr(s0, 10, 4) or bitnum_intr(s1, 18, 3) or bitnum_intr(s0, 18, 2) or
                   bitnum_intr(s1, 26, 1) or bitnum_intr(s0, 26, 0)).toByte()

        data[5] = (bitnum_intr(s1, 1, 7) or bitnum_intr(s0, 1, 6) or bitnum_intr(s1, 9, 5) or
                   bitnum_intr(s0, 9, 4) or bitnum_intr(s1, 17, 3) or bitnum_intr(s0, 17, 2) or
                   bitnum_intr(s1, 25, 1) or bitnum_intr(s0, 25, 0)).toByte()

        data[4] = (bitnum_intr(s1, 0, 7) or bitnum_intr(s0, 0, 6) or bitnum_intr(s1, 8, 5) or
                   bitnum_intr(s0, 8, 4) or bitnum_intr(s1, 16, 3) or bitnum_intr(s0, 16, 2) or
                   bitnum_intr(s1, 24, 1) or bitnum_intr(s0, 24, 0)).toByte()
        return data
    }

    private fun f(state: Int, key: IntArray): Int {
        val t1 = (bitnum_intl(state, 31, 0) or
                  ((state and 0xf0000000.toInt()) ushr 1) or
                  bitnum_intl(state, 4, 5) or
                  bitnum_intl(state, 3, 6) or
                  ((state and 0x0f000000) ushr 3) or
                  bitnum_intl(state, 8, 11) or
                  bitnum_intl(state, 7, 12) or
                  ((state and 0x00f00000) ushr 5) or
                  bitnum_intl(state, 12, 17) or
                  bitnum_intl(state, 11, 18) or
                  ((state and 0x000f0000) ushr 7) or
                  bitnum_intl(state, 16, 23))

        val t2 = (bitnum_intl(state, 15, 0) or
                  ((state and 0x0000f000) shl 15) or
                  bitnum_intl(state, 20, 5) or
                  bitnum_intl(state, 19, 6) or
                  ((state and 0x00000f00) shl 13) or
                  bitnum_intl(state, 24, 11) or
                  bitnum_intl(state, 23, 12) or
                  ((state and 0x000000f0) shl 11) or
                  bitnum_intl(state, 28, 17) or
                  bitnum_intl(state, 27, 18) or
                  ((state and 0x0000000f) shl 9) or
                  bitnum_intl(state, 0, 23))

        val lrg0 = ((t1 ushr 24) and 0xff) xor key[0]
        val lrg1 = ((t1 ushr 16) and 0xff) xor key[1]
        val lrg2 = ((t1 ushr 8) and 0xff) xor key[2]
        val lrg3 = ((t2 ushr 24) and 0xff) xor key[3]
        val lrg4 = ((t2 ushr 16) and 0xff) xor key[4]
        val lrg5 = ((t2 ushr 8) and 0xff) xor key[5]

        val st = ((sbox[0][sbox_bit(lrg0 ushr 2)] shl 28) or
                  (sbox[1][sbox_bit(((lrg0 and 0x03) shl 4) or (lrg1 ushr 4))] shl 24) or
                  (sbox[2][sbox_bit(((lrg1 and 0x0f) shl 2) or (lrg2 ushr 6))] shl 20) or
                  (sbox[3][sbox_bit(lrg2 and 0x3f)] shl 16) or
                  (sbox[4][sbox_bit(lrg3 ushr 2)] shl 12) or
                  (sbox[5][sbox_bit(((lrg3 and 0x03) shl 4) or (lrg4 ushr 4))] shl 8) or
                  (sbox[6][sbox_bit(((lrg4 and 0x0f) shl 2) or (lrg5 ushr 6))] shl 4) or
                  sbox[7][sbox_bit(lrg5 and 0x3f)])

        return (bitnum_intl(st, 15, 0) or bitnum_intl(st, 6, 1) or bitnum_intl(st, 19, 2) or
                bitnum_intl(st, 20, 3) or bitnum_intl(st, 28, 4) or bitnum_intl(st, 11, 5) or
                bitnum_intl(st, 27, 6) or bitnum_intl(st, 16, 7) or bitnum_intl(st, 0, 8) or
                bitnum_intl(st, 14, 9) or bitnum_intl(st, 22, 10) or bitnum_intl(st, 25, 11) or
                bitnum_intl(st, 4, 12) or bitnum_intl(st, 17, 13) or bitnum_intl(st, 30, 14) or
                bitnum_intl(st, 9, 15) or bitnum_intl(st, 1, 16) or bitnum_intl(st, 7, 17) or
                bitnum_intl(st, 23, 18) or bitnum_intl(st, 13, 19) or bitnum_intl(st, 31, 20) or
                bitnum_intl(st, 26, 21) or bitnum_intl(st, 2, 22) or bitnum_intl(st, 8, 23) or
                bitnum_intl(st, 18, 24) or bitnum_intl(st, 12, 25) or bitnum_intl(st, 29, 26) or
                bitnum_intl(st, 5, 27) or bitnum_intl(st, 21, 28) or bitnum_intl(st, 10, 29) or
                bitnum_intl(st, 3, 30) or bitnum_intl(st, 24, 31))
    }

    private fun crypt(input_data: ByteArray, key: Array<IntArray>): ByteArray {
        var (s0, s1) = initial_permutation(input_data)
        for (idx in 0..14) {
            val previous_s1 = s1
            s1 = f(s1, key[idx]) xor s0
            s0 = previous_s1
        }
        s0 = f(s1, key[15]) xor s0
        return inverse_permutation(s0, s1)
    }

    private fun key_schedule(key: ByteArray, mode: Int): Array<IntArray> {
        val schedule = Array(16) { IntArray(6) }
        val key_rnd_shift = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
        val key_perm_c = intArrayOf(56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35)
        val key_perm_d = intArrayOf(62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3)
        val key_compression = intArrayOf(13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3, 25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36,
                                         46, 54, 29, 39, 50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31)

        var c = 0
        for (i in 0..27) c += bitnum(key, key_perm_c[i], 31 - i)
        var d = 0
        for (i in 0..27) d += bitnum(key, key_perm_d[i], 31 - i)

        for (i in 0..15) {
            val shift = key_rnd_shift[i]
            c = ((c shl shift) or (c ushr (28 - shift))) and 0xfffffff0.toInt()
            d = ((d shl shift) or (d ushr (28 - shift))) and 0xfffffff0.toInt()

            val togen = if (mode == DECRYPT) 15 - i else i

            for (j in 0..23) {
                schedule[togen][j / 8] = schedule[togen][j / 8] or bitnum_intr(c, key_compression[j], 7 - (j % 8))
            }
            for (j in 24..47) {
                schedule[togen][j / 8] = schedule[togen][j / 8] or bitnum_intr(d, key_compression[j] - 27, 7 - (j % 8))
            }
        }
        return schedule
    }

    private fun tripledes_key_setup(key: ByteArray, mode: Int): Array<Array<IntArray>> {
        return if (mode == ENCRYPT) {
            arrayOf(key_schedule(key.copyOfRange(0, 8), ENCRYPT),
                    key_schedule(key.copyOfRange(8, 16), DECRYPT),
                    key_schedule(key.copyOfRange(16, 24), ENCRYPT))
        } else {
            arrayOf(key_schedule(key.copyOfRange(16, 24), DECRYPT),
                    key_schedule(key.copyOfRange(8, 16), ENCRYPT),
                    key_schedule(key.copyOfRange(0, 8), DECRYPT))
        }
    }

    // ====== 解密入口 ======
    fun decrypt(encryptedData: ByteArray): ByteArray {
        val decryptedStream = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < encryptedData.size) {
            val chunkSize = minOf(8, encryptedData.size - i)
            if (chunkSize == 8) {
                val block = encryptedData.copyOfRange(i, i + 8)
                var temp = crypt(block, tripleDesKeys[0])
                temp = crypt(temp, tripleDesKeys[1])
                temp = crypt(temp, tripleDesKeys[2])
                decryptedStream.write(temp)
            } else {
                // 不足 8 字节的尾巴保留原样（这也是 QQ 音乐算法的一个坑）
                decryptedStream.write(encryptedData, i, chunkSize)
            }
            i += 8
        }
        return decryptedStream.toByteArray()
    }
}