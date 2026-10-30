// Moteur de filtre WebGL2 : un seul shader sert à l'aperçu caméra, à l'aperçu photo et à l'export.
// Tout est calculé sur le GPU du téléphone, rien ne quitte l'appareil.

export interface Params {
  exposure: number // -1..1 (diaphragmes)
  contrast: number // -0.5..1
  saturation: number // -1..1
  green: number // 0..1.5 : réglage historique Fight Club (vert/cyan dans les ombres, jaune dans les lumières)
  grain: number // 0..1
  vignette: number // 0..1
  sharpen: number // 0..1
  temperature: number // -1..1 : froid ↔ chaud
  tint: number // -1..1 : vert ↔ magenta
  shadowHue: number // 0..1 : teinte des ombres (0 rouge, ⅓ vert, ⅔ bleu)
  shadowAmt: number // 0..1
  highlightHue: number // 0..1 : teinte des hautes lumières
  highlightAmt: number // 0..1
  fade: number // 0..1 : noirs relevés
  bleach: number // 0..1 : bleach bypass (contraste sale, couleurs lavées)
  mono: number // 0..1 : noir et blanc
  keepHue: number // 0..1 : couleur conservée quand le reste passe en noir et blanc (style Sin City)
  keepAmt: number // 0..1
}

export const NEUTRAL: Params = {
  exposure: 0, contrast: 0, saturation: 0, green: 0, grain: 0, vignette: 0, sharpen: 0,
  temperature: 0, tint: 0, shadowHue: 0, shadowAmt: 0, highlightHue: 0, highlightAmt: 0, fade: 0, bleach: 0, mono: 0, keepHue: 0, keepAmt: 0,
}

export const FIGHT_CLUB: Params = {
  ...NEUTRAL,
  exposure: -0.25,
  contrast: 0.3,
  saturation: -0.35,
  green: 0.6,
  grain: 0.35,
  vignette: 0.45,
  sharpen: 0.3,
}

export interface SliderDef {
  key: keyof Params
  label: string
  min: number
  max: number
  group: 'base' | 'adv'
  hue?: boolean // curseur de teinte : arc-en-ciel, affiché en degrés
}

export const SLIDERS: SliderDef[] = [
  { key: 'exposure', label: 'Exposition', min: -1, max: 1, group: 'base' },
  { key: 'contrast', label: 'Contraste', min: -0.5, max: 1, group: 'base' },
  { key: 'saturation', label: 'Saturation', min: -1, max: 1, group: 'base' },
  { key: 'green', label: 'Teinte verte', min: 0, max: 1.5, group: 'base' },
  { key: 'grain', label: 'Grain', min: 0, max: 1, group: 'base' },
  { key: 'vignette', label: 'Vignette', min: 0, max: 1, group: 'base' },
  { key: 'sharpen', label: 'Netteté', min: 0, max: 1, group: 'base' },
  { key: 'temperature', label: 'Température', min: -1, max: 1, group: 'adv' },
  { key: 'tint', label: 'Vert ↔ magenta', min: -1, max: 1, group: 'adv' },
  { key: 'shadowHue', label: 'Ombres : teinte', min: 0, max: 1, group: 'adv', hue: true },
  { key: 'shadowAmt', label: 'Ombres : force', min: 0, max: 1, group: 'adv' },
  { key: 'highlightHue', label: 'Lumières : teinte', min: 0, max: 1, group: 'adv', hue: true },
  { key: 'highlightAmt', label: 'Lumières : force', min: 0, max: 1, group: 'adv' },
  { key: 'fade', label: 'Noirs relevés', min: 0, max: 1, group: 'adv' },
  { key: 'bleach', label: 'Bleach bypass', min: 0, max: 1, group: 'adv' },
  { key: 'mono', label: 'Noir et blanc', min: 0, max: 1, group: 'adv' },
  { key: 'keepHue', label: 'Couleur isolée', min: 0, max: 1, group: 'adv', hue: true },
  { key: 'keepAmt', label: 'Isolée : force', min: 0, max: 1, group: 'adv' },
]

// uniforme du shader → paramètre ; une seule table pour déclarer et envoyer les valeurs
const UNIFORMS = {
  uExposure: 'exposure', uContrast: 'contrast', uSaturation: 'saturation', uGreen: 'green', uGrain: 'grain',
  uVignette: 'vignette', uSharpen: 'sharpen', uTemp: 'temperature', uTint: 'tint', uShHue: 'shadowHue',
  uShAmt: 'shadowAmt', uHiHue: 'highlightHue', uHiAmt: 'highlightAmt', uFade: 'fade', uBleach: 'bleach', uMono: 'mono', uKeepHue: 'keepHue', uKeepAmt: 'keepAmt',
} as const satisfies Record<string, keyof Params>

const VERT = `#version 300 es
in vec2 p;
out vec2 uv;
void main() {
  // texImage2D range la 1re ligne de l'image à t=0 : on retourne en Y ici, car WebGL
  // ignore UNPACK_FLIP_Y_WEBGL pour un ImageBitmap (photo à l'envers sinon)
  uv = vec2(p.x * 0.5 + 0.5, 0.5 - p.y * 0.5);
  gl_Position = vec4(p, 0.0, 1.0);
}`

const FRAG = `#version 300 es
precision highp float;
precision highp int;
in vec2 uv;
out vec4 o;
uniform sampler2D tex;
uniform vec2 uSize;      // taille de la source en pixels
uniform float uSeed;
uniform float uExposure, uContrast, uSaturation, uGreen, uGrain, uVignette, uSharpen;
uniform float uTemp, uTint, uShHue, uShAmt, uHiHue, uHiAmt, uFade, uBleach, uMono, uKeepHue, uKeepAmt;

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

// teinte (0..1) → couleur pure, puis on retire sa luminance : un virage de couleur ne change pas la clarté
vec3 hueTone(float h) {
  vec3 k = clamp(abs(fract(h + vec3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
  return k - luma(k);
}

// hash entier (PCG) : pas de motif visible, stable en mediump comme en highp
float hash(uvec2 v) {
  uint s = v.x * 1664525u + v.y * 1013904223u + uint(uSeed);
  s ^= s >> 16; s *= 2246822519u; s ^= s >> 13; s *= 3266489917u; s ^= s >> 16;
  return float(s) / 4294967295.0;
}

// 1 si la couleur est proche de la teinte à conserver (et assez saturée), 0 sinon
float keepMask(vec3 col) {
  float mx = max(col.r, max(col.g, col.b));
  float d = mx - min(col.r, min(col.g, col.b));
  if (d < 0.001) return 0.0;
  float h = col.r == mx ? mod((col.g - col.b) / d, 6.0) : (col.g == mx ? (col.b - col.r) / d + 2.0 : (col.r - col.g) / d + 4.0);
  float dh = abs(fract(h / 6.0 - uKeepHue + 0.5) - 0.5);
  return (1.0 - smoothstep(0.02, 0.07, dh)) * smoothstep(0.35, 0.6, d / max(mx, 0.001));
}

void main() {
  vec3 c = texture(tex, uv).rgb;

  // Netteté (unsharp mask) : rayon proportionnel à la taille de l'image,
  // donc l'aperçu et l'export pleine résolution ont le même rendu.
  if (uSharpen > 0.0) {
    float short_ = min(uSize.x, uSize.y);
    vec2 st = vec2(short_ / uSize.x, short_ / uSize.y) / 1400.0;
    vec3 b = (texture(tex, uv + vec2(st.x, 0.0)).rgb + texture(tex, uv - vec2(st.x, 0.0)).rgb
            + texture(tex, uv + vec2(0.0, st.y)).rgb + texture(tex, uv - vec2(0.0, st.y)).rgb) * 0.25;
    c += (c - b) * uSharpen * 2.0;
  }

  c *= exp2(uExposure);

  // Balance des blancs : température (froid ↔ chaud) et teinte (vert ↔ magenta)
  c.r += uTemp * 0.07;
  c.b -= uTemp * 0.07;
  c.g -= uTint * 0.05;
  c.rb += uTint * 0.025;

  // Contraste fort autour du gris moyen, puis noirs écrasés
  c = (c - 0.5) * (1.0 + uContrast) + 0.5;
  float black = 0.07 * clamp(uContrast + 0.3, 0.0, 1.3);
  c = max(c - black, 0.0) / (1.0 - black);

  // Noirs relevés
  c = c * (1.0 - uFade * 0.12) + uFade * 0.12;

  // Bleach bypass : overlay de la luminance sur l'image, comme un film dont on a sauté le blanchiment
  float lb = luma(c);
  c = mix(c, mix(2.0 * c * lb, 1.0 - 2.0 * (1.0 - c) * (1.0 - lb), step(0.5, c)), uBleach);

  float l = luma(c);
  c = mix(vec3(l), c, 1.0 + uSaturation);
  vec3 colored = c;
  c = mix(c, vec3(luma(c)), uMono);
  c = mix(c, colored, keepMask(colored) * uKeepAmt); // couleur isolée

  // Split-toning : ombres vert/cyan, hautes lumières jaunâtres
  l = luma(c);
  float sh = 1.0 - smoothstep(0.0, 0.6, l);
  float hi = smoothstep(0.4, 1.0, l);
  c += uGreen * (sh * vec3(-0.06, 0.045, 0.035) + hi * vec3(0.05, 0.035, -0.05));
  c.g += uGreen * 0.012; // léger voile vert global

  // Virage des ombres et des lumières, avec les teintes choisies
  c += (hueTone(uShHue) * uShAmt * sh + hueTone(uHiHue) * uHiAmt * hi) * 0.25;

  // Vignette
  vec2 d = (uv - 0.5) * vec2(uSize.x / min(uSize.x, uSize.y), uSize.y / min(uSize.x, uSize.y));
  float v = smoothstep(0.25, 0.95, length(d));
  c *= 1.0 - uVignette * v * 0.85;

  // Grain : taille liée au petit côté de l'image (≈700 grains), plus présent dans les tons moyens
  if (uGrain > 0.0) {
    float cell = min(uSize.x, uSize.y) / 700.0;
    uvec2 g = uvec2(floor(uv * uSize / max(cell, 1.0)));
    float n = hash(g) + hash(g + 7919u) - 1.0; // bruit triangulaire
    float lum = luma(c);
    c += n * uGrain * 0.16 * (1.0 - abs(lum - 0.45) * 1.3);
  }

  o = vec4(clamp(c, 0.0, 1.0), 1.0);
}`

export class Renderer {
  readonly gl: WebGL2RenderingContext
  private prog: WebGLProgram
  private tex: WebGLTexture
  private u: Record<string, WebGLUniformLocation | null> = {}
  readonly maxTex: number

  constructor(public canvas: HTMLCanvasElement) {
    const gl = canvas.getContext('webgl2', { alpha: false, antialias: false, preserveDrawingBuffer: true })
    if (!gl) throw new Error('WebGL2 indisponible sur cet appareil')
    this.gl = gl
    this.maxTex = gl.getParameter(gl.MAX_TEXTURE_SIZE)
    this.prog = this.program(VERT, FRAG)
    gl.useProgram(this.prog)
    const buf = gl.createBuffer()
    gl.bindBuffer(gl.ARRAY_BUFFER, buf)
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW)
    const loc = gl.getAttribLocation(this.prog, 'p')
    gl.enableVertexAttribArray(loc)
    gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0)
    this.tex = gl.createTexture()!
    gl.bindTexture(gl.TEXTURE_2D, this.tex)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)
    for (const n of ['tex', 'uSize', 'uSeed', ...Object.keys(UNIFORMS)])
      this.u[n] = gl.getUniformLocation(this.prog, n)
  }

  private program(vs: string, fs: string) {
    const gl = this.gl
    const make = (type: number, src: string) => {
      const s = gl.createShader(type)!
      gl.shaderSource(s, src)
      gl.compileShader(s)
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s) ?? 'shader')
      return s
    }
    const p = gl.createProgram()!
    gl.attachShader(p, make(gl.VERTEX_SHADER, vs))
    gl.attachShader(p, make(gl.FRAGMENT_SHADER, fs))
    gl.linkProgram(p)
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p) ?? 'link')
    return p
  }

  /** Dessine la photo ou l'image vidéo filtrée sur le canvas (w×h pixels). */
  render(src: ImageBitmap | HTMLVideoElement, w: number, h: number, p: Params, o: { seed?: number; mipmap?: boolean } = {}) {
    const gl = this.gl
    const sw = src instanceof HTMLVideoElement ? src.videoWidth : src.width
    const sh = src instanceof HTMLVideoElement ? src.videoHeight : src.height
    if (!sw || !sh) return
    if (this.canvas.width !== w || this.canvas.height !== h) {
      this.canvas.width = w
      this.canvas.height = h
    }
    gl.viewport(0, 0, w, h)
    gl.bindTexture(gl.TEXTURE_2D, this.tex)
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, src)
    if (o.mipmap) {
      gl.generateMipmap(gl.TEXTURE_2D)
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR)
    } else gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR)
    const u = this.u
    gl.uniform1i(u.tex, 0)
    gl.uniform2f(u.uSize, sw, sh)
    gl.uniform1f(u.uSeed, o.seed ?? 1)
    for (const [n, k] of Object.entries(UNIFORMS)) gl.uniform1f(u[n], p[k])
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4)
  }

  dispose() {
    this.gl.getExtension('WEBGL_lose_context')?.loseContext()
  }
}

function maxTextureSize(): number {
  const c = document.createElement('canvas')
  const gl = c.getContext('webgl2')
  const n = gl ? (gl.getParameter(gl.MAX_TEXTURE_SIZE) as number) : 4096
  gl?.getExtension('WEBGL_lose_context')?.loseContext()
  return n
}

/** Ouvre un fichier/blob en respectant l'orientation EXIF, réduit si la GPU ne le supporte pas. */
export async function loadBitmap(blob: Blob): Promise<ImageBitmap> {
  const maxTex = maxTextureSize()
  const full = await createImageBitmap(blob, { imageOrientation: 'from-image' })
  const m = Math.max(full.width, full.height)
  if (m <= maxTex) return full
  const k = maxTex / m
  const small = await createImageBitmap(full, {
    resizeWidth: Math.floor(full.width * k),
    resizeHeight: Math.floor(full.height * k),
    resizeQuality: 'high',
  })
  full.close()
  return small
}

/** Export JPEG pleine résolution (résolution de la source, plafonnée par la GPU). */
export async function exportJpeg(src: ImageBitmap, p: Params, quality = 0.92): Promise<Blob> {
  const c = document.createElement('canvas')
  const r = new Renderer(c)
  try {
    r.render(src, src.width, src.height, p, { seed: 1, mipmap: false })
    return await new Promise((res, rej) => c.toBlob(b => (b ? res(b) : rej(new Error('export JPEG impossible'))), 'image/jpeg', quality))
  } finally {
    r.dispose()
  }
}
