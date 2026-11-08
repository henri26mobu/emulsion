package dev.rangermobu.emulsion.kotlin

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES30 as G
import android.view.Surface
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Décode (MediaCodec -> SurfaceTexture) -> shader du filtre (OpenGL) -> encode H.264 (surface d'entrée) -> MP4.
 * Le son est recopié tel quel (pas de ré-encodage). À appeler sur un thread de travail ; [progress] reçoit 0..100.
 * Sans Media3 : ~300 Ko de dépendances en moins.
 */
object VideoExporter {
    fun export(ctx: Context, uri: Uri, p: FloatArray, out: FileDescriptor, progress: (Int) -> Unit) {
        val ex = MediaExtractor(); ex.setDataSource(ctx, uri, null)
        val ax = MediaExtractor(); ax.setDataSource(ctx, uri, null)
        var vi = -1; var ai = -1
        for (i in 0 until ex.trackCount) {
            val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (vi < 0 && m.startsWith("video/")) vi = i else if (ai < 0 && m.startsWith("audio/")) ai = i
        }
        require(vi >= 0) { "aucune piste vidéo" }
        ex.selectTrack(vi)
        val vf = ex.getTrackFormat(vi)
        val rot = if (vf.containsKey(MediaFormat.KEY_ROTATION)) vf.getInteger(MediaFormat.KEY_ROTATION) else 0
        var w = vf.getInteger(MediaFormat.KEY_WIDTH); var h = vf.getInteger(MediaFormat.KEY_HEIGHT)
        if (rot % 180 != 0) { val t = w; w = h; h = t } // le décodeur applique la rotation (KEY_ROTATION) : frames déjà droites
        w = w and 1.inv(); h = h and 1.inv()
        val dur = if (vf.containsKey(MediaFormat.KEY_DURATION)) vf.getLong(MediaFormat.KEY_DURATION) else 0L
        val fps = if (vf.containsKey(MediaFormat.KEY_FRAME_RATE)) vf.getInteger(MediaFormat.KEY_FRAME_RATE) else 30
        val bitrate = (if (vf.containsKey(MediaFormat.KEY_BIT_RATE)) vf.getInteger(MediaFormat.KEY_BIT_RATE) else (w * h * 4)).coerceIn(1_000_000, 50_000_000)

        val enc = MediaCodec.createEncoderByType("video/avc")
        var dec: MediaCodec? = null; var egl: Egl? = null; var st: SurfaceTexture? = null; var surf: Surface? = null
        var encSurf: Surface? = null; var muxer: MediaMuxer? = null; var started = false
        try {
            enc.configure(MediaFormat.createVideoFormat("video/avc", w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate); setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encSurf = enc.createInputSurface(); enc.start()
            egl = Egl(encSurf)
            val f = FilterGl(true)
            val ids = IntArray(1); G.glGenTextures(1, ids, 0)
            G.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
            for ((k, v) in listOf(G.GL_TEXTURE_WRAP_S to G.GL_CLAMP_TO_EDGE, G.GL_TEXTURE_WRAP_T to G.GL_CLAMP_TO_EDGE, G.GL_TEXTURE_MIN_FILTER to G.GL_LINEAR, G.GL_TEXTURE_MAG_FILTER to G.GL_LINEAR))
                G.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, k, v)
            val frame = Object(); var avail = false
            st = SurfaceTexture(ids[0]); surf = Surface(st)
            st.setOnFrameAvailableListener { synchronized(frame) { avail = true; frame.notifyAll() } }
            dec = MediaCodec.createDecoderByType(vf.getString(MediaFormat.KEY_MIME)!!)
            dec.configure(vf, surf, null, 0); dec.start()
            muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val di = MediaCodec.BufferInfo(); val ei = MediaCodec.BufferInfo()
            var inDone = false; var decDone = false; var encDone = false; var vTrack = -1; var aTrack = -1
            val tm = FloatArray(16); var n = 0
            G.glViewport(0, 0, w, h)
            while (!encDone) {
                if (!inDone) {
                    val i = dec.dequeueInputBuffer(5000)
                    if (i >= 0) {
                        val sz = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                        if (sz < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { dec.queueInputBuffer(i, 0, sz, ex.sampleTime, 0); ex.advance() }
                    }
                }
                if (!decDone) {
                    val o = dec.dequeueOutputBuffer(di, 5000)
                    if (o >= 0) {
                        val draw = di.size > 0
                        dec.releaseOutputBuffer(o, draw)
                        if (draw) {
                            synchronized(frame) { // l'image arrive de façon asynchrone dans la SurfaceTexture
                                val t0 = System.currentTimeMillis()
                                while (!avail && System.currentTimeMillis() - t0 < 3000) frame.wait(100)
                                check(avail) { "décodeur bloqué" }; avail = false
                            }
                            st.updateTexImage(); st.getTransformMatrix(tm)
                            G.glActiveTexture(G.GL_TEXTURE0); G.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
                            f.draw(p, videoMap(tm), w, h, 0f, 0f, 1f, 1f, 1f, (++n).toFloat())
                            egl.swap(di.presentationTimeUs * 1000)
                            if (dur > 0) progress((di.presentationTimeUs * 100 / dur).toInt().coerceIn(0, 99))
                        }
                        if (di.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { decDone = true; enc.signalEndOfInputStream() }
                    }
                }
                val o = enc.dequeueOutputBuffer(ei, 5000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    vTrack = muxer.addTrack(enc.outputFormat)
                    if (ai >= 0) try { ax.selectTrack(ai); aTrack = muxer.addTrack(ax.getTrackFormat(ai)) } catch (e: Exception) { aTrack = -1 }
                    muxer.start(); started = true
                } else if (o >= 0) {
                    val b = enc.getOutputBuffer(o)!!
                    if (ei.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && ei.size > 0 && started) muxer.writeSampleData(vTrack, b, ei)
                    enc.releaseOutputBuffer(o, false)
                    if (ei.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encDone = true
                }
            }
            if (aTrack >= 0) {
                val fmt = ax.getTrackFormat(ai)
                val buf = ByteBuffer.allocate(if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 1 shl 18)
                val bi = MediaCodec.BufferInfo()
                while (true) {
                    val sz = ax.readSampleData(buf, 0)
                    if (sz < 0) break
                    bi.set(0, sz, ax.sampleTime, ax.sampleFlags)
                    muxer.writeSampleData(aTrack, buf, bi); ax.advance()
                }
            }
            progress(100)
        } finally {
            try { if (started) muxer?.stop() } catch (_: Exception) {}
            muxer?.release()
            try { dec?.stop() } catch (_: Exception) {}
            dec?.release()
            try { enc.stop() } catch (_: Exception) {}
            enc.release()
            surf?.release(); st?.release(); encSurf?.release(); egl?.release()
            ex.release(); ax.release()
        }
    }
}
