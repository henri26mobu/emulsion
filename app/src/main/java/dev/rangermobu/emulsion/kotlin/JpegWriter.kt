package dev.rangermobu.emulsion.kotlin

import java.io.OutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Encodeur JPEG baseline 4:2:0 en flux : le framework Android n'encode qu'un Bitmap entier
 * (192 Mo pour 8000x6000 en ARGB_8888). Ici on garde 16 lignes au plus (une rangée de MCU 16x16).
 * Tables de quantification et de Huffman : annexe K du standard, luminance et chrominance.
 */
class JpegWriter(private val out: OutputStream, private val w: Int, private val h: Int, quality: Int = 92) {
    private val cw = (w + 1) / 2
    private val plane = Array(3) { FloatArray(16 * w) }       // Y, Cb, Cr pleine résolution, 16 lignes
    private val sub = Array(2) { FloatArray(8 * cw) }         // Cb, Cr sous-échantillonnés 2x2
    private var filled = 0
    private var acc = 0
    private var nb = 0
    private val pred = IntArray(3)
    private val q = Array(2) { IntArray(64) }
    private val dcLen = Array(2) { IntArray(256) }; private val dcCode = Array(2) { IntArray(256) }
    private val acLen = Array(2) { IntArray(256) }; private val acCode = Array(2) { IntArray(256) }
    private val tmp = FloatArray(64); private val tmp2 = FloatArray(64)

    init {
        val s = if (quality < 50) 5000 / quality else 200 - quality * 2
        for (i in 0 until 64) { q[0][i] = ((QL[i] * s + 50) / 100).coerceIn(1, 255); q[1][i] = ((QC[i] * s + 50) / 100).coerceIn(1, 255) }
        for (t in 0..1) {
            build(DC_BITS[t], IntArray(12) { it }, dcLen[t], dcCode[t])
            build(AC_BITS[t], hex(AC_VALS[t]), acLen[t], acCode[t])
        }
        header()
    }

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16) }.toIntArray()

    private fun build(bits: IntArray, vals: IntArray, len: IntArray, code: IntArray) {
        var c = 0; var k = 0
        for (l in 1..16) { repeat(bits[l - 1]) { len[vals[k]] = l; code[vals[k]] = c; c++; k++ }; c = c shl 1 }
    }

    private fun u16(v: Int) { out.write(v shr 8); out.write(v and 255) }

    private fun header() {
        u16(0xFFD8)
        u16(0xFFE0); u16(16); out.write(byteArrayOf(0x4A, 0x46, 0x49, 0x46, 0, 1, 1, 0, 0, 1, 0, 1, 0, 0))
        for (t in 0..1) { u16(0xFFDB); u16(67); out.write(t); for (i in 0 until 64) out.write(q[t][ZZ[i]]) }
        u16(0xFFC0); u16(17); out.write(8); u16(h); u16(w); out.write(3)
        out.write(1); out.write(0x22); out.write(0); out.write(2); out.write(0x11); out.write(1); out.write(3); out.write(0x11); out.write(1)
        for (t in 0..1) {
            u16(0xFFC4); u16(2 + 17 + 12); out.write(t); for (b in DC_BITS[t]) out.write(b); for (i in 0 until 12) out.write(i)
            u16(0xFFC4); u16(2 + 17 + 162); out.write(0x10 or t); for (b in AC_BITS[t]) out.write(b)
            for (v in hex(AC_VALS[t])) out.write(v)
        }
        u16(0xFFDA); u16(12); out.write(3); out.write(1); out.write(0x00); out.write(2); out.write(0x11); out.write(3); out.write(0x11)
        out.write(0); out.write(63); out.write(0)
    }

    /** Ajoute [n] lignes RGBA (haut vers bas). */
    fun addRows(rgba: ByteArray, n: Int) {
        for (r in 0 until n) {
            val o = r * w * 4; val b = filled * w
            for (x in 0 until w) {
                val R = (rgba[o + 4 * x].toInt() and 255).toFloat()
                val G = (rgba[o + 4 * x + 1].toInt() and 255).toFloat()
                val B = (rgba[o + 4 * x + 2].toInt() and 255).toFloat()
                plane[0][b + x] = 0.299f * R + 0.587f * G + 0.114f * B - 128f
                plane[1][b + x] = -0.168736f * R - 0.331264f * G + 0.5f * B
                plane[2][b + x] = 0.5f * R - 0.418688f * G - 0.081312f * B
            }
            if (++filled == 16) flush()
        }
    }

    fun finish() {
        if (filled in 1..15) { // dernière rangée incomplète : on répète la dernière ligne
            val last = (filled - 1) * w
            while (filled < 16) { for (p in plane) System.arraycopy(p, last, p, filled * w, w); filled++ }
            flush()
        }
        put(0x7F, 7) // bourrage à 1
        u16(0xFFD9)
        out.flush()
    }

    private fun flush() {
        for (c in 0..1) for (y in 0 until 8) for (x in 0 until cw) {
            val x0 = 2 * x; val x1 = minOf(x0 + 1, w - 1); val p = plane[c + 1]
            sub[c][y * cw + x] = (p[2 * y * w + x0] + p[2 * y * w + x1] + p[(2 * y + 1) * w + x0] + p[(2 * y + 1) * w + x1]) * 0.25f
        }
        for (mx in 0 until (w + 15) / 16) {
            for (by in 0..1) for (bx in 0..1) block(0, 0, plane[0], w, mx * 16 + bx * 8, by * 8)
            block(1, 1, sub[0], cw, mx * 8, 0)
            block(2, 1, sub[1], cw, mx * 8, 0)
        }
        filled = 0
    }

    private fun block(c: Int, t: Int, p: FloatArray, stride: Int, x0: Int, y0: Int) {
        for (y in 0 until 8) for (x in 0 until 8) tmp[y * 8 + x] = p[(y0 + y) * stride + minOf(x0 + x, stride - 1)]
        for (y in 0 until 8) for (u in 0 until 8) { var s = 0f; for (x in 0 until 8) s += COS[u * 8 + x] * tmp[y * 8 + x]; tmp2[y * 8 + u] = s }
        for (u in 0 until 8) for (v in 0 until 8) { var s = 0f; for (y in 0 until 8) s += COS[v * 8 + y] * tmp2[y * 8 + u]; tmp[v * 8 + u] = s }
        val qt = q[t]
        val d = (tmp[0] / qt[0]).roundToInt() - pred[c]
        pred[c] += d
        val cat = cat(d)
        put(dcCode[t][cat], dcLen[t][cat]); bits(d, cat)
        var run = 0
        for (i in 1 until 64) {
            val v = (tmp[ZZ[i]] / qt[ZZ[i]]).roundToInt()
            if (v == 0) { run++; continue }
            while (run > 15) { put(acCode[t][0xF0], acLen[t][0xF0]); run -= 16 }
            val s = cat(v)
            put(acCode[t][run shl 4 or s], acLen[t][run shl 4 or s]); bits(v, s); run = 0
        }
        if (run > 0) put(acCode[t][0], acLen[t][0])
    }

    private fun cat(v: Int) = 32 - Integer.numberOfLeadingZeros(if (v < 0) -v else v)
    private fun bits(v: Int, n: Int) { if (n > 0) put(if (v < 0) v - 1 else v, n) }

    private fun put(code: Int, len: Int) {
        acc = (acc shl len) or (code and ((1 shl len) - 1)); nb += len
        while (nb >= 8) { val b = (acc shr (nb - 8)) and 255; out.write(b); if (b == 255) out.write(0); nb -= 8 }
        acc = acc and ((1 shl nb) - 1)
    }

    private companion object {
        val ZZ = intArrayOf(0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
            35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63)
        val QL = intArrayOf(16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
            18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92, 49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99)
        val QC = intArrayOf(17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99, 24, 26, 56, 99, 99, 99, 99, 99, 47, 66, 99, 99, 99, 99, 99, 99) +
            IntArray(32) { 99 }
        val COS = FloatArray(64) { val u = it / 8; val x = it % 8; ((if (u == 0) 0.70710678 else 1.0) / 2 * cos((2 * x + 1) * u * PI / 16)).toFloat() }
        val DC_BITS = arrayOf(intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0), intArrayOf(0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0))
        val AC_BITS = arrayOf(intArrayOf(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 125), intArrayOf(0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 119))
        val AC_VALS = arrayOf(
            "01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9fa",
            "000102031104052131061241510761711322328108144291a1b1c109233352f0156272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a636465666768696a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9fa")
    }
}
