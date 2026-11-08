package dev.rangermobu.emulsion.kotlin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.opengl.GLES30 as G
import android.opengl.GLUtils
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/**
 * Export pleine résolution par bandes de [BAND] lignes, sur un thread de travail avec son propre contexte EGL
 * (l'UI et l'aperçu ne se figent pas). Par bande : décodage de la région (BitmapRegionDecoder) -> texture -> FBO ->
 * glReadPixels -> JpegWriter. Pic mémoire ~ quelques tampons de (largeur x BAND x 4 octets).
 * Formats que BitmapRegionDecoder ne sait pas ouvrir : repli sur un décodage complet sous-échantillonné (≤ ~16 Mpx).
 * Si une dimension dépasse GL_MAX_TEXTURE_SIZE, on sous-échantillonne (puissance de 2) comme l'app web.
 * Renvoie (largeur, hauteur) écrites.
 */
object Exporter {
    const val BAND = 256

    fun export(src: File, rot: Int, p: FloatArray, out: OutputStream, quality: Int = 92, progress: (Int) -> Unit): Pair<Int, Int> {
        val egl = Egl()
        try {
            val f = FilterGl(false)
            val mt = IntArray(1); G.glGetIntegerv(G.GL_MAX_TEXTURE_SIZE, mt, 0)
            val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(src.path, bo)
            require(bo.outWidth > 0 && bo.outHeight > 0) { "format d'image non supporté" }
            val dec = try { BitmapRegionDecoder.newInstance(src.path, false) } catch (e: Exception) { null }
            var s = 1
            while (maxOf(bo.outWidth, bo.outHeight) / s > mt[0]) s *= 2
            if (dec == null) while (bo.outWidth.toLong() * bo.outHeight / s / s > 16_000_000L) s *= 2
            val rw = bo.outWidth / s; val rh = bo.outHeight / s
            val swap = rot % 180 != 0
            val ow = if (swap) rh else rw; val oh = if (swap) rw else rh
            val halo = ceil(minOf(ow, oh) / 60f).toInt() + 2 // couvre le rayon de l'halation (min/70)

            val ids = IntArray(2)
            G.glGenTextures(2, ids, 0) // [0] = bande de sortie, [1] = source
            val fbo = IntArray(1); G.glGenFramebuffers(1, fbo, 0)
            G.glBindTexture(G.GL_TEXTURE_2D, ids[0])
            G.glTexImage2D(G.GL_TEXTURE_2D, 0, G.GL_RGBA8, ow, BAND, 0, G.GL_RGBA, G.GL_UNSIGNED_BYTE, null)
            G.glBindFramebuffer(G.GL_FRAMEBUFFER, fbo[0])
            G.glFramebufferTexture2D(G.GL_FRAMEBUFFER, G.GL_COLOR_ATTACHMENT0, G.GL_TEXTURE_2D, ids[0], 0)
            check(G.glCheckFramebufferStatus(G.GL_FRAMEBUFFER) == G.GL_FRAMEBUFFER_COMPLETE) { "FBO incomplet" }
            G.glBindTexture(G.GL_TEXTURE_2D, ids[1])
            for ((k, v) in listOf(G.GL_TEXTURE_WRAP_S to G.GL_CLAMP_TO_EDGE, G.GL_TEXTURE_WRAP_T to G.GL_CLAMP_TO_EDGE, G.GL_TEXTURE_MIN_FILTER to G.GL_LINEAR, G.GL_TEXTURE_MAG_FILTER to G.GL_LINEAR))
                G.glTexParameteri(G.GL_TEXTURE_2D, k, v)

            val opt = BitmapFactory.Options().apply { inSampleSize = s }
            var whole: Bitmap? = null; var wholeMap: TexMap? = null
            if (dec == null) {
                whole = BitmapFactory.decodeFile(src.path, opt) ?: throw IllegalArgumentException("image illisible (CMYK ou format non géré)")
                GLUtils.texImage2D(G.GL_TEXTURE_2D, 0, whole, 0)
                wholeMap = texMap(rot, rw, rh, 0, 0, whole.width, whole.height)
                whole.recycle()
            }
            val buf = ByteBuffer.allocateDirect(ow * BAND * 4).order(ByteOrder.nativeOrder())
            val rows = ByteArray(ow * BAND * 4)
            val jpg = JpegWriter(out, ow, oh, quality)
            try {
                var y0 = 0
                while (y0 < oh) {
                    val n = minOf(BAND, oh - y0)
                    val m: TexMap
                    if (dec != null) {
                        val ya = maxOf(0, y0 - halo); val yb = minOf(oh, y0 + n + halo)
                        val rect = when (rot) {
                            90 -> Rect(ya, 0, yb, rh)
                            180 -> Rect(0, rh - yb, rw, rh - ya)
                            270 -> Rect(rw - yb, 0, rw - ya, rh)
                            else -> Rect(0, ya, rw, yb)
                        }
                        val bm = dec.decodeRegion(Rect(rect.left * s, rect.top * s, rect.right * s, rect.bottom * s), opt)
                            ?: throw IllegalArgumentException("décodage impossible")
                        m = texMap(rot, rw, rh, rect.left, rect.top, bm.width, bm.height)
                        G.glBindTexture(G.GL_TEXTURE_2D, ids[1])
                        GLUtils.texImage2D(G.GL_TEXTURE_2D, 0, bm, 0); bm.recycle()
                    } else m = wholeMap!!
                    G.glBindFramebuffer(G.GL_FRAMEBUFFER, fbo[0])
                    G.glViewport(0, 0, ow, n)
                    G.glActiveTexture(G.GL_TEXTURE0); G.glBindTexture(G.GL_TEXTURE_2D, ids[1])
                    f.draw(p, m, ow, oh, 0f, y0.toFloat() / oh, 1f, n.toFloat() / oh, -1f)
                    buf.clear()
                    G.glReadPixels(0, 0, ow, n, G.GL_RGBA, G.GL_UNSIGNED_BYTE, buf)
                    buf.get(rows, 0, ow * n * 4)
                    jpg.addRows(rows, n)
                    y0 += n
                    progress(y0 * 100 / oh)
                }
                jpg.finish()
            } finally { dec?.recycle() }
            return ow to oh
        } finally { egl.release() }
    }
}
