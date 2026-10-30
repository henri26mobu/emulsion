package dev.rangermobu.emulsion;

import android.content.Context;
import android.opengl.GLES20;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;

/**
 * Même filtre que src/filter.ts, porté en GLSL ES 1.00 (compatible avec tous les contextes GL de Media3).
 * p = les 18 valeurs de Params, dans l'ordre de NAMES (le même que les clés de PhotoSaverPlugin.exportVideo).
 * Le grain utilise un hash flottant plutôt que le hash entier du web : même rendu, graines différentes.
 */
final class FilterEffect implements GlEffect {
    private static final String VERT =
        "attribute vec4 aFramePosition;\n"
            + "uniform mat4 uTransformationMatrix;\n"
            + "uniform mat4 uTexTransformationMatrix;\n"
            + "varying vec2 uv;\n"
            + "void main() {\n"
            + "  gl_Position = uTransformationMatrix * aFramePosition;\n"
            + "  vec4 t = vec4(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);\n"
            + "  uv = (uTexTransformationMatrix * t).xy;\n"
            + "}\n";

    private static final String FRAG =
        "precision highp float;\n"
            + "varying vec2 uv;\n"
            + "uniform sampler2D uTexSampler;\n"
            + "uniform vec2 uSize;\n"
            + "uniform float uSeed, uExposure, uContrast, uSaturation, uGreen, uGrain, uVignette, uSharpen;\n"
            + "uniform float uTemp, uTint, uShHue, uShAmt, uHiHue, uHiAmt, uFade, uBleach, uMono, uKeepHue, uKeepAmt;\n"
            + "float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }\n"
            + "vec3 hueTone(float h) {\n"
            + "  vec3 k = clamp(abs(fract(h + vec3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);\n"
            + "  return k - luma(k);\n"
            + "}\n"
            + "float hash(vec2 p) {\n"
            + "  p = fract(p * vec2(0.1031, 0.1030));\n"
            + "  p += dot(p, p.yx + 33.33);\n"
            + "  return fract((p.x + p.y) * p.x);\n"
            + "}\n"
            + "float keepMask(vec3 col) {\n"
            + "  float mx = max(col.r, max(col.g, col.b));\n"
            + "  float d = mx - min(col.r, min(col.g, col.b));\n"
            + "  if (d < 0.001) return 0.0;\n"
            + "  float h = col.r == mx ? mod((col.g - col.b) / d, 6.0) : (col.g == mx ? (col.b - col.r) / d + 2.0 : (col.r - col.g) / d + 4.0);\n"
            + "  float dh = abs(fract(h / 6.0 - uKeepHue + 0.5) - 0.5);\n"
            + "  return (1.0 - smoothstep(0.02, 0.07, dh)) * smoothstep(0.35, 0.6, d / max(mx, 0.001));\n"
            + "}\n"
            + "void main() {\n"
            + "  vec3 c = texture2D(uTexSampler, uv).rgb;\n"
            + "  if (uSharpen > 0.0) {\n"
            + "    float s = min(uSize.x, uSize.y);\n"
            + "    vec2 st = vec2(s / uSize.x, s / uSize.y) / 1400.0;\n"
            + "    vec3 b = (texture2D(uTexSampler, uv + vec2(st.x, 0.0)).rgb + texture2D(uTexSampler, uv - vec2(st.x, 0.0)).rgb\n"
            + "            + texture2D(uTexSampler, uv + vec2(0.0, st.y)).rgb + texture2D(uTexSampler, uv - vec2(0.0, st.y)).rgb) * 0.25;\n"
            + "    c += (c - b) * uSharpen * 2.0;\n"
            + "  }\n"
            + "  c *= exp2(uExposure);\n"
            + "  c.r += uTemp * 0.07;\n"
            + "  c.b -= uTemp * 0.07;\n"
            + "  c.g -= uTint * 0.05;\n"
            + "  c.rb += uTint * 0.025;\n"
            + "  c = (c - 0.5) * (1.0 + uContrast) + 0.5;\n"
            + "  float black = 0.07 * clamp(uContrast + 0.3, 0.0, 1.3);\n"
            + "  c = max(c - black, 0.0) / (1.0 - black);\n"
            + "  c = c * (1.0 - uFade * 0.12) + uFade * 0.12;\n"
            + "  float lb = luma(c);\n"
            + "  c = mix(c, mix(2.0 * c * lb, 1.0 - 2.0 * (1.0 - c) * (1.0 - lb), step(0.5, c)), uBleach);\n"
            + "  float l = luma(c);\n"
            + "  c = mix(vec3(l), c, 1.0 + uSaturation);\n"
            + "  vec3 colored = c;\n"
            + "  c = mix(c, vec3(luma(c)), uMono);\n"
            + "  c = mix(c, colored, keepMask(colored) * uKeepAmt);\n"
            + "  l = luma(c);\n"
            + "  float sh = 1.0 - smoothstep(0.0, 0.6, l);\n"
            + "  float hi = smoothstep(0.4, 1.0, l);\n"
            + "  c += uGreen * (sh * vec3(-0.06, 0.045, 0.035) + hi * vec3(0.05, 0.035, -0.05));\n"
            + "  c.g += uGreen * 0.012;\n"
            + "  c += (hueTone(uShHue) * uShAmt * sh + hueTone(uHiHue) * uHiAmt * hi) * 0.25;\n"
            + "  float m = min(uSize.x, uSize.y);\n"
            + "  vec2 d = (uv - 0.5) * uSize / m;\n"
            + "  c *= 1.0 - uVignette * smoothstep(0.25, 0.95, length(d)) * 0.85;\n"
            + "  if (uGrain > 0.0) {\n"
            + "    vec2 g = floor(uv * uSize / max(m / 700.0, 1.0));\n"
            + "    float n = hash(g + uSeed) + hash(g + uSeed + 91.7) - 1.0;\n"
            + "    c += n * uGrain * 0.16 * (1.0 - abs(luma(c) - 0.45) * 1.3);\n"
            + "  }\n"
            + "  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);\n"
            + "}\n";

    // ordre des valeurs de p : exposure, contrast, saturation, green, grain, vignette, sharpen, temperature, tint,
    // shadowHue, shadowAmt, highlightHue, highlightAmt, fade, bleach, mono, keepHue, keepAmt
    static final String[] NAMES = {
        "uExposure", "uContrast", "uSaturation", "uGreen", "uGrain", "uVignette", "uSharpen", "uTemp", "uTint",
        "uShHue", "uShAmt", "uHiHue", "uHiAmt", "uFade", "uBleach", "uMono", "uKeepHue", "uKeepAmt"
    };

    private final float[] p;

    FilterEffect(float[] params) {
        this.p = params;
    }

    @Override
    public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr) throws VideoFrameProcessingException {
        return new Program(p, useHdr);
    }

    private static final class Program extends BaseGlShaderProgram {
        private final GlProgram gl;
        private final float[] p;
        private Size size = new Size(1, 1);

        Program(float[] p, boolean useHdr) throws VideoFrameProcessingException {
            super(useHdr, 1);
            this.p = p;
            try {
                gl = new GlProgram(VERT, FRAG);
            } catch (GlUtil.GlException e) {
                throw new VideoFrameProcessingException(e);
            }
            gl.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
            float[] id = GlUtil.create4x4IdentityMatrix();
            gl.setFloatsUniform("uTransformationMatrix", id);
            gl.setFloatsUniform("uTexTransformationMatrix", id);
        }

        @Override
        public Size configure(int w, int h) {
            size = new Size(w, h);
            return size;
        }

        @Override
        public void drawFrame(int inputTexId, long presentationTimeUs) throws VideoFrameProcessingException {
            try {
                gl.use();
                gl.setSamplerTexIdUniform("uTexSampler", inputTexId, 0);
                gl.setFloatsUniform("uSize", new float[] {size.getWidth(), size.getHeight()});
                gl.setFloatUniform("uSeed", (presentationTimeUs / 1000) % 997); // nouveau grain à chaque image
                for (int i = 0; i < NAMES.length; i++) gl.setFloatUniform(NAMES[i], p[i]);
                gl.bindAttributesAndUniforms();
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            } catch (GlUtil.GlException e) {
                throw new VideoFrameProcessingException(e);
            }
        }

        @Override
        public void release() throws VideoFrameProcessingException {
            super.release();
            try {
                gl.delete();
            } catch (GlUtil.GlException e) {
                throw new VideoFrameProcessingException(e);
            }
        }
    }
}
