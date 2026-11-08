package dev.rangermobu.emulsion.kotlin

import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES30 as G
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Vertex : écart avec la version web = uOff/uScl (la tuile exportée ne couvre qu'une bande de l'image),
// uFlip (readPixels est bas-en-haut) et uM/uB (uv global -> coordonnée dans la texture décodée,
// ce qui applique l'orientation EXIF et le halo de la bande sans copier de Bitmap).
private const val VERT = """#version 300 es
in vec2 p;
out vec2 uv;
out vec2 tuv;
uniform vec2 uOff, uScl;
uniform float uFlip;
uniform mat2 uM;
uniform vec2 uB;
void main() {
  uv = uOff + vec2(p.x * 0.5 + 0.5, 0.5 - uFlip * p.y * 0.5) * uScl;
  tuv = uM * uv + uB;
  gl_Position = vec4(p, 0.0, 1.0);
}"""

// Fragment : identique au shader web (mêmes constantes, PCG, grain, vignette...) sauf :
// texture(tex, uv) -> texture(tex, tuv) et le pas de netteté passe par uM (même rayon en pixels source).
private const val FRAG = """#version 300 es
precision highp float;
precision highp int;
in vec2 uv;
in vec2 tuv;
out vec4 o;
uniform sampler2D tex;
uniform mat2 uM;
uniform vec2 uSize;      // taille de l'image complète (affichage) en pixels
uniform float uSeed;
uniform float uExposure, uContrast, uSaturation, uGreen, uGrain, uVignette, uSharpen;
uniform float uTemp, uTint, uShHue, uShAmt, uHiHue, uHiAmt, uFade, uBleach, uMono, uKeepHue, uKeepAmt;

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

vec3 hueTone(float h) {
  vec3 k = clamp(abs(fract(h + vec3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
  return k - luma(k);
}

float hash(uvec2 v) {
  uint s = v.x * 1664525u + v.y * 1013904223u + uint(uSeed);
  s ^= s >> 16; s *= 2246822519u; s ^= s >> 13; s *= 3266489917u; s ^= s >> 16;
  return float(s) / 4294967295.0;
}

float keepMask(vec3 col) {
  float mx = max(col.r, max(col.g, col.b));
  float d = mx - min(col.r, min(col.g, col.b));
  if (d < 0.001) return 0.0;
  float h = col.r == mx ? mod((col.g - col.b) / d, 6.0) : (col.g == mx ? (col.b - col.r) / d + 2.0 : (col.r - col.g) / d + 4.0);
  float dh = abs(fract(h / 6.0 - uKeepHue + 0.5) - 0.5);
  return (1.0 - smoothstep(0.02, 0.07, dh)) * smoothstep(0.35, 0.6, d / max(mx, 0.001));
}

void main() {
  vec3 c = texture(tex, tuv).rgb;

  if (uSharpen > 0.0) {
    float short_ = min(uSize.x, uSize.y);
    vec2 st = vec2(short_ / uSize.x, short_ / uSize.y) / 1400.0;
    vec2 sx = uM * vec2(st.x, 0.0);
    vec2 sy = uM * vec2(0.0, st.y);
    vec3 b = (texture(tex, tuv + sx).rgb + texture(tex, tuv - sx).rgb
            + texture(tex, tuv + sy).rgb + texture(tex, tuv - sy).rgb) * 0.25;
    c += (c - b) * uSharpen * 2.0;
  }

  c *= exp2(uExposure);

  c.r += uTemp * 0.07;
  c.b -= uTemp * 0.07;
  c.g -= uTint * 0.05;
  c.rb += uTint * 0.025;

  c = (c - 0.5) * (1.0 + uContrast) + 0.5;
  float black = 0.07 * clamp(uContrast + 0.3, 0.0, 1.3);
  c = max(c - black, 0.0) / (1.0 - black);

  c = c * (1.0 - uFade * 0.12) + uFade * 0.12;

  float lb = luma(c);
  c = mix(c, mix(2.0 * c * lb, 1.0 - 2.0 * (1.0 - c) * (1.0 - lb), step(0.5, c)), uBleach);

  float l = luma(c);
  c = mix(vec3(l), c, 1.0 + uSaturation);
  vec3 colored = c;
  c = mix(c, vec3(luma(c)), uMono);
  c = mix(c, colored, keepMask(colored) * uKeepAmt);

  l = luma(c);
  float sh = 1.0 - smoothstep(0.0, 0.6, l);
  float hi = smoothstep(0.4, 1.0, l);
  c += uGreen * (sh * vec3(-0.06, 0.045, 0.035) + hi * vec3(0.05, 0.035, -0.05));
  c.g += uGreen * 0.012;

  c += (hueTone(uShHue) * uShAmt * sh + hueTone(uHiHue) * uHiAmt * hi) * 0.25;

  vec2 d = (uv - 0.5) * vec2(uSize.x / min(uSize.x, uSize.y), uSize.y / min(uSize.x, uSize.y));
  float v = smoothstep(0.25, 0.95, length(d));
  c *= 1.0 - uVignette * v * 0.85;

  if (uGrain > 0.0) {
    float cell = min(uSize.x, uSize.y) / 700.0;
    uvec2 g = uvec2(floor(uv * uSize / max(cell, 1.0)));
    float n = hash(g) + hash(g + 7919u) - 1.0;
    float lum = luma(c);
    c += n * uGrain * 0.16 * (1.0 - abs(lum - 0.45) * 1.3);
  }

  o = vec4(clamp(c, 0.0, 1.0), 1.0);
}"""

/** Fait correspondre le repère d'affichage (uv global 0..1, y vers le bas) à une texture décodée. */
class TexMap(val m: FloatArray, val b: FloatArray)

/**
 * rot = rotation EXIF en degrés (0/90/180/270). (rw,rh) = taille brute (avant rotation) à l'échelle de sortie,
 * (L,T) = coin de la région décodée dans ce repère, (bw,bh) = taille du Bitmap décodé.
 * shortcut: pas de variantes miroir EXIF (flip), rares sur les photos d'appareil.
 */
fun texMap(rot: Int, rw: Int, rh: Int, L: Int, T: Int, bw: Int, bh: Int): TexMap {
    val dw = if (rot % 180 == 0) rw else rh
    val dh = if (rot % 180 == 0) rh else rw
    // rx = a*X + b*Y + c ; ry = d*X + e*Y + f  (X,Y pixels d'affichage)
    val k = when (rot) {
        90 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, rh.toFloat())
        180 -> floatArrayOf(-1f, 0f, rw.toFloat(), 0f, -1f, rh.toFloat())
        270 -> floatArrayOf(0f, -1f, rw.toFloat(), 1f, 0f, 0f)
        else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
    }
    val (a, b, c, d, e, f) = k
    // mat2 GLSL en colonnes : [col0.x, col0.y, col1.x, col1.y]
    return TexMap(
        floatArrayOf(a * dw / bw, d * dw / bh, b * dh / bw, e * dh / bh),
        floatArrayOf((c - L) / bw, (f - T) / bh),
    )
}
private operator fun FloatArray.component6() = this[5]


/** Programme du filtre. [external] = variante samplerExternalOES (image vidéo décodée). Contexte GL courant requis. */
class FilterGl(external: Boolean) {
    private val prog = G.glCreateProgram()
    private val loc = HashMap<String, Int>()
    private val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0) }

    init {
        val frag = if (external) FRAG.replace("#version 300 es", "#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require")
            .replace("uniform sampler2D tex;", "uniform samplerExternalOES tex;") else FRAG
        for ((t, s) in listOf(G.GL_VERTEX_SHADER to VERT, G.GL_FRAGMENT_SHADER to frag)) {
            val sh = G.glCreateShader(t); G.glShaderSource(sh, s); G.glCompileShader(sh)
            val ok = IntArray(1); G.glGetShaderiv(sh, G.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { G.glGetShaderInfoLog(sh) }
            G.glAttachShader(prog, sh)
        }
        G.glBindAttribLocation(prog, 0, "p")
        G.glLinkProgram(prog)
    }

    private fun u(n: String) = loc.getOrPut(n) { G.glGetUniformLocation(prog, n) }

    /** Dessine la région [off, off+scl] (uv globaux) de l'image d'affichage (sw×sh) ; la texture source est liée à l'unité 0. */
    fun draw(p: FloatArray, m: TexMap, sw: Int, sh: Int, ox: Float, oy: Float, sx: Float, sy: Float, flip: Float, seed: Float = 1f) {
        G.glUseProgram(prog)
        G.glEnableVertexAttribArray(0); G.glVertexAttribPointer(0, 2, G.GL_FLOAT, false, 0, quad)
        G.glUniform1i(u("tex"), 0)
        G.glUniform2f(u("uSize"), sw.toFloat(), sh.toFloat())
        G.glUniform1f(u("uSeed"), seed)
        G.glUniform2f(u("uOff"), ox, oy); G.glUniform2f(u("uScl"), sx, sy); G.glUniform1f(u("uFlip"), flip)
        G.glUniformMatrix2fv(u("uM"), 1, false, m.m, 0); G.glUniform2f(u("uB"), m.b[0], m.b[1])
        for ((i, s) in SLIDERS.withIndex()) G.glUniform1f(u(s.uniform), p[i])
        G.glDrawArrays(G.GL_TRIANGLE_STRIP, 0, 4)
    }
}

/** Contexte EGL ES3 hors écran (pbuffer) ou lié à une Surface (entrée de l'encodeur vidéo) : pour exporter hors du thread GL de l'UI. */
class Egl(surface: Surface? = null) {
    private val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val ctx: android.opengl.EGLContext
    private val surf: android.opengl.EGLSurface

    init {
        val v = IntArray(2); EGL14.eglInitialize(dpy, v, 0, v, 1)
        val attr = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, 0x40 /* ES3 */, EGL14.EGL_SURFACE_TYPE, if (surface != null) EGL14.EGL_WINDOW_BIT else EGL14.EGL_PBUFFER_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, if (surface != null) 1 else 0, EGL14.EGL_NONE)
        val cfg = arrayOfNulls<android.opengl.EGLConfig>(1); val n = IntArray(1)
        check(EGL14.eglChooseConfig(dpy, attr, 0, cfg, 0, 1, n, 0) && n[0] > 0) { "EGL : aucune config" }
        ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        surf = if (surface != null) EGL14.eglCreateWindowSurface(dpy, cfg[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        else EGL14.eglCreatePbufferSurface(dpy, cfg[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) { "EGL : makeCurrent" }
    }

    fun swap(ptsNs: Long) { EGLExt.eglPresentationTimeANDROID(dpy, surf, ptsNs); EGL14.eglSwapBuffers(dpy, surf) }

    fun release() {
        EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(dpy, surf); EGL14.eglDestroyContext(dpy, ctx)
    }
}

/** Matrice de SurfaceTexture (y vers le haut) -> TexMap pour notre uv (y vers le bas). */
fun videoMap(t: FloatArray) = TexMap(floatArrayOf(t[0], t[1], -t[4], -t[5]), floatArrayOf(t[4] + t[12], t[5] + t[13]))
