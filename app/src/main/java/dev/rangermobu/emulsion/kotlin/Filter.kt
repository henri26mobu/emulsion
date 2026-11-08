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
uniform float uDilute, uHalation, uGrainSize, uGrainCol, uBars, uPerf, uDust, uLutAmt, uLutN, uSplit;
uniform float uCL1, uCL2, uCL3, uCR1, uCR2, uCR3, uCG1, uCG2, uCG3, uCB1, uCB2, uCB3;
uniform highp sampler3D lutTex;
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

float bump(float x, float c) { float s = clamp(1.0 - abs(x - c) * 4.0, 0.0, 1.0); return s * s * (3.0 - 2.0 * s); }
float curve(float x, float a, float b, float c) { return x + a * bump(x, 0.25) + b * bump(x, 0.5) + c * bump(x, 0.75); }

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
  vec3 c0 = c;
  float short_ = min(uSize.x, uSize.y);

  if (uHalation > 0.0) {
    vec2 hs = vec2(short_ / uSize.x, short_ / uSize.y) / 70.0;
    vec3 acc = vec3(0.0);
    for (int i = 0; i < 12; i++) {
      float a = float(i) * 0.5236;
      vec2 o = uM * (vec2(cos(a), sin(a)) * hs * ((i % 2 == 0) ? 1.0 : 0.5));
      acc += max(texture(tex, tuv + o).rgb - 0.6, 0.0);
    }
    c += acc / 12.0 * vec3(1.0, 0.35, 0.15) * uHalation * 2.5;
  }

  if (uSharpen > 0.0) {
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

  c = clamp(c, 0.0, 1.0);
  c = vec3(curve(c.r, uCR1, uCR2, uCR3), curve(c.g, uCG1, uCG2, uCG3), curve(c.b, uCB1, uCB2, uCB3));
  c = vec3(curve(c.r, uCL1, uCL2, uCL3), curve(c.g, uCL1, uCL2, uCL3), curve(c.b, uCL1, uCL2, uCL3));
  c = clamp(c, 0.0, 1.0);

  if (uLutAmt > 0.0 && uLutN > 0.0) c = mix(c, texture(lutTex, (c * (uLutN - 1.0) + 0.5) / uLutN).rgb, uLutAmt);

  vec2 d = (uv - 0.5) * vec2(uSize.x / min(uSize.x, uSize.y), uSize.y / min(uSize.x, uSize.y));
  float v = smoothstep(0.25, 0.95, length(d));
  c *= 1.0 - uVignette * v * 0.85;

  if (uGrain > 0.0) {
    float cell = short_ / 700.0 * (1.0 + uGrainSize * 3.0);
    uvec2 g = uvec2(floor(uv * uSize / max(cell, 1.0)));
    float n = hash(g) + hash(g + 7919u) - 1.0;
    float lum = luma(c);
    vec3 nn = vec3(hash(g + 3u) + hash(g + 5u), hash(g + 11u) + hash(g + 13u), hash(g + 17u) + hash(g + 19u)) - 1.0;
    c += mix(vec3(n), nn, uGrainCol) * uGrain * 0.16 * (1.0 - abs(lum - 0.45) * 1.3);
  }

  c = mix(clamp(c, 0.0, 1.0), c0, uDilute);

  if (uDust > 0.0) {
    vec2 cp = uv * uSize / (short_ * 0.01);
    if (hash(uvec2(floor(cp)) + 31337u) > 1.0 - 0.004 * uDust)
      c = mix(c, vec3(0.9), (1.0 - smoothstep(0.1, 0.45, length(fract(cp) - 0.5))) * 0.7);
    float col = floor(uv.x * uSize.x / (short_ * 0.002));
    if (hash(uvec2(col, 77u)) > 1.0 - 0.01 * uDust) c = mix(c, vec3(0.85), 0.25 * step(hash(uvec2(col, uint(uSeed))), 0.5));
  }

  if (uPerf > 0.5) { // shortcut: les perforations recouvrent l'image (pas de bords ajoutés)
    vec2 px = uv * uSize;
    float sw = short_ * 0.075, edge = min(px.x, uSize.x - px.x), period = short_ * 0.06;
    if (edge < sw) {
      c = vec3(0.05, 0.035, 0.03);
      if (abs(edge - sw * 0.5) < sw * 0.22 && abs(mod(px.y, period) - period * 0.5) < period * 0.28) c = vec3(0.9, 0.85, 0.75);
    }
  }

  if (uBars > 0.0) { // shortcut: ratio 2,39:1 borné à 25 % de hauteur minimum (portraits)
    float r = mix(1.0, clamp(uSize.x / uSize.y / 2.39, 0.25, 1.0), uBars);
    if (abs(uv.y - 0.5) > r * 0.5) c = vec3(0.0);
  }

  if (uSplit >= 0.0) { // avant/après : à droite du trait = image d'origine
    if (uv.x > uSplit) c = c0;
    if (abs(uv.x - uSplit) * uSize.x < max(1.0, short_ * 0.002)) c = vec3(1.0);
  }

  o = vec4(clamp(c, 0.0, 1.0), 1.0);
}"""

/** LUT 3D .cube (taille n, données RGB, R varie le plus vite). shortcut: DOMAIN_MIN/MAX et LUT_1D ignorés/refusés. */
class Lut(val n: Int, val buf: java.nio.FloatBuffer)

object LutStore {
    @Volatile var cur: Lut? = null

    fun parse(text: String): Lut {
        var n = 0; val v = ArrayList<Float>()
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            if (l.isEmpty() || l[0] == '#') continue
            val t = l.split(Regex("\\s+"))
            when {
                t[0] == "LUT_3D_SIZE" -> n = t[1].toInt()
                t[0] == "LUT_1D_SIZE" -> throw IllegalArgumentException("LUT 1D non supportée")
                t[0][0].isDigit() || t[0][0] == '-' || t[0][0] == '.' -> { require(t.size >= 3) { "ligne invalide" }; for (i in 0..2) v += t[i].toFloat() }
            }
        }
        require(n in 2..65 && v.size == n * n * n * 3) { "LUT .cube 3D invalide" }
        val b = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (x in v) b.put(x)
        return Lut(n, b.also { it.position(0) })
    }
}

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
    private var lutTex = 0
    private var lutUp: Lut? = null
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
    fun draw(p: FloatArray, m: TexMap, sw: Int, sh: Int, ox: Float, oy: Float, sx: Float, sy: Float, flip: Float, seed: Float = 1f, split: Float = -1f) {
        G.glUseProgram(prog)
        G.glEnableVertexAttribArray(0); G.glVertexAttribPointer(0, 2, G.GL_FLOAT, false, 0, quad)
        G.glUniform1i(u("tex"), 0)
        val lut = LutStore.cur
        if (lut != null) {
            if (lutTex == 0) { val t = IntArray(1); G.glGenTextures(1, t, 0); lutTex = t[0] }
            G.glActiveTexture(G.GL_TEXTURE1); G.glBindTexture(G.GL_TEXTURE_3D, lutTex)
            if (lut !== lutUp) {
                for ((k, v) in listOf(G.GL_TEXTURE_WRAP_S to G.GL_CLAMP_TO_EDGE, G.GL_TEXTURE_WRAP_T to G.GL_CLAMP_TO_EDGE, G.GL_TEXTURE_WRAP_R to G.GL_CLAMP_TO_EDGE, G.GL_TEXTURE_MIN_FILTER to G.GL_LINEAR, G.GL_TEXTURE_MAG_FILTER to G.GL_LINEAR))
                    G.glTexParameteri(G.GL_TEXTURE_3D, k, v)
                G.glTexImage3D(G.GL_TEXTURE_3D, 0, G.GL_RGB16F, lut.n, lut.n, lut.n, 0, G.GL_RGB, G.GL_FLOAT, lut.buf.duplicate())
                lutUp = lut
            }
            G.glActiveTexture(G.GL_TEXTURE0)
        }
        G.glUniform1i(u("lutTex"), 1); G.glUniform1f(u("uLutN"), lut?.n?.toFloat() ?: 0f); G.glUniform1f(u("uSplit"), split)
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
