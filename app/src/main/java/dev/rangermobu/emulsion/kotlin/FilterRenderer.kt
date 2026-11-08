package dev.rangermobu.emulsion.kotlin

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30 as G
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.view.Surface
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Aperçu : une photo (texture 2D) ou une vidéo en lecture (texture externe), filtrées par le même shader. */
class FilterRenderer(private val redraw: () -> Unit) : GLSurfaceView.Renderer {
    @Volatile var params = FloatArray(SLIDERS.size)
    @Volatile var original = false // « Maintenir : original »
    private var f2d: FilterGl? = null
    private var fext: FilterGl? = null
    private var tex = 0
    private var map: TexMap? = null
    private var dw = 0; private var dh = 0
    private var vw = 1; private var vh = 1
    private var pending: Bitmap? = null
    private var pendingRot = 0
    // vidéo
    private var vidW = 0; private var vidH = 0
    private var st: SurfaceTexture? = null
    private var onSurface: ((Surface) -> Unit)? = null
    @Volatile private var newFrame = false
    private var frames = 0
    private val tm = FloatArray(16)
    private var extId = 0
    var error: ((String) -> Unit)? = null

    @Synchronized fun setPreview(b: Bitmap, rot: Int) { pending?.recycle(); pending = b; pendingRot = rot; vidW = 0 }

    /** (w,h) = taille d'affichage de la vidéo (rotation comprise). [cb] reçoit la Surface où le lecteur doit dessiner. */
    @Synchronized fun setVideo(w: Int, h: Int, cb: (Surface) -> Unit) { pending?.recycle(); pending = null; map = null; vidW = w; vidH = h; onSurface = cb }

    fun clear(gl: GLSurfaceView) = gl.queueEvent { st?.release(); st = null }

    override fun onSurfaceCreated(g: GL10?, c: EGLConfig?) {
        val t = IntArray(1); G.glGenTextures(1, t, 0); tex = t[0]
        f2d = FilterGl(false); fext = null; st = null; map = null
        G.glBindTexture(G.GL_TEXTURE_2D, tex)
        for ((k, v) in listOf(G.GL_TEXTURE_WRAP_S to G.GL_CLAMP_TO_EDGE, G.GL_TEXTURE_WRAP_T to G.GL_CLAMP_TO_EDGE, G.GL_TEXTURE_MIN_FILTER to G.GL_LINEAR, G.GL_TEXTURE_MAG_FILTER to G.GL_LINEAR))
            G.glTexParameteri(G.GL_TEXTURE_2D, k, v)
    }

    override fun onSurfaceChanged(g: GL10?, w: Int, h: Int) { vw = w; vh = h }

    override fun onDrawFrame(g: GL10?) {
        val b: Bitmap?; val rot: Int; val cb: ((Surface) -> Unit)?
        synchronized(this) { b = pending; rot = pendingRot; pending = null; cb = onSurface; onSurface = null }
        if (b != null) {
            G.glBindTexture(G.GL_TEXTURE_2D, tex)
            GLUtils.texImage2D(G.GL_TEXTURE_2D, 0, b, 0)
            map = texMap(rot, b.width, b.height, 0, 0, b.width, b.height)
            dw = if (rot % 180 == 0) b.width else b.height
            dh = if (rot % 180 == 0) b.height else b.width
            b.recycle()
        }
        if (cb != null) try {
            val ids = IntArray(1); G.glGenTextures(1, ids, 0); extId = ids[0]
            G.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
            for ((k, v) in listOf(G.GL_TEXTURE_WRAP_S to G.GL_CLAMP_TO_EDGE, G.GL_TEXTURE_WRAP_T to G.GL_CLAMP_TO_EDGE, G.GL_TEXTURE_MIN_FILTER to G.GL_LINEAR, G.GL_TEXTURE_MAG_FILTER to G.GL_LINEAR))
                G.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, k, v)
            if (fext == null) fext = FilterGl(true)
            st?.release()
            st = SurfaceTexture(ids[0]).also { s -> s.setOnFrameAvailableListener { newFrame = true; redraw() } }
            dw = vidW; dh = vidH
            cb(Surface(st))
        } catch (e: Exception) { error?.invoke("Aperçu vidéo impossible : ${e.message}") }
        G.glBindFramebuffer(G.GL_FRAMEBUFFER, 0)
        G.glClearColor(0.02f, 0.02f, 0.02f, 1f); G.glClear(G.GL_COLOR_BUFFER_BIT)
        val p = if (original) FloatArray(SLIDERS.size) else params
        val k = minOf(vw.toFloat() / maxOf(dw, 1), vh.toFloat() / maxOf(dh, 1))
        val w = (dw * k).toInt(); val h = (dh * k).toInt()
        if (vidW > 0) {
            val s = st ?: return
            if (newFrame) { newFrame = false; s.updateTexImage(); s.getTransformMatrix(tm); map = videoMap(tm.copyOf()); if (frames++ == 3) android.util.Log.i("emu", "tm=${tm.joinToString()} dw=$dw dh=$dh vw=$vw vh=$vh") }
            val m = map ?: return
            G.glViewport((vw - w) / 2, (vh - h) / 2, w, h)
            G.glActiveTexture(G.GL_TEXTURE0); G.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, extId)
            fext!!.draw(p, m, dw, dh, 0f, 0f, 1f, 1f, 1f, frames.toFloat())
        } else {
            val m = map ?: return
            G.glViewport((vw - w) / 2, (vh - h) / 2, w, h)
            G.glActiveTexture(G.GL_TEXTURE0); G.glBindTexture(G.GL_TEXTURE_2D, tex)
            f2d!!.draw(p, m, dw, dh, 0f, 0f, 1f, 1f, 1f)
        }
    }

}
