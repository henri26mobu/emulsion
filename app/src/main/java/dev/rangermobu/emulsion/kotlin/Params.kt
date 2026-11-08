package dev.rangermobu.emulsion.kotlin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Un curseur = un uniform du shader. L'ordre de [SLIDERS] est l'ordre des FloatArray de paramètres. */
class Slider(val key: String, val uniform: String, val label: String, val min: Float, val max: Float, val adv: Boolean = false, val hue: Boolean = false, val hidden: Boolean = false)

/** Courbes : 3 points (x = 0,25 / 0,5 / 0,75) par canal, valeur = décalage par rapport à la diagonale. */
val CURVE_CH = listOf("cL", "cR", "cG", "cB")

val SLIDERS = listOf(
    Slider("exposure", "uExposure", "Exposition", -1f, 1f),
    Slider("contrast", "uContrast", "Contraste", -0.5f, 1f),
    Slider("saturation", "uSaturation", "Saturation", -1f, 1f),
    Slider("green", "uGreen", "Teinte verte", 0f, 1.5f),
    Slider("grain", "uGrain", "Grain", 0f, 1f),
    Slider("vignette", "uVignette", "Vignette", 0f, 1f),
    Slider("sharpen", "uSharpen", "Netteté", 0f, 1f),
    Slider("dilute", "uDilute", "Atténuer le look", 0f, 1f), // 0 = look complet ; 1 = image d'origine (défaut 0 pour les presets anciens)
    Slider("temperature", "uTemp", "Température", -1f, 1f, adv = true),
    Slider("tint", "uTint", "Vert ↔ magenta", -1f, 1f, adv = true),
    Slider("shadowHue", "uShHue", "Ombres : teinte", 0f, 1f, adv = true, hue = true),
    Slider("shadowAmt", "uShAmt", "Ombres : force", 0f, 1f, adv = true),
    Slider("highlightHue", "uHiHue", "Lumières : teinte", 0f, 1f, adv = true, hue = true),
    Slider("highlightAmt", "uHiAmt", "Lumières : force", 0f, 1f, adv = true),
    Slider("fade", "uFade", "Noirs relevés", 0f, 1f, adv = true),
    Slider("bleach", "uBleach", "Bleach bypass", 0f, 1f, adv = true),
    Slider("mono", "uMono", "Noir et blanc", 0f, 1f, adv = true),
    Slider("keepHue", "uKeepHue", "Couleur isolée", 0f, 1f, adv = true, hue = true),
    Slider("keepAmt", "uKeepAmt", "Isolée : force", 0f, 1f, adv = true),
    Slider("halation", "uHalation", "Halation", 0f, 1f, adv = true),
    Slider("grainSize", "uGrainSize", "Grain : taille", 0f, 1f, adv = true),
    Slider("grainColor", "uGrainCol", "Grain : couleur", 0f, 1f, adv = true),
    Slider("bars", "uBars", "Bandes cinémascope", 0f, 1f, adv = true),
    Slider("perf", "uPerf", "Perforations (> 50)", 0f, 1f, adv = true),
    Slider("dust", "uDust", "Poussière / rayures", 0f, 1f, adv = true),
    Slider("lut", "uLutAmt", "LUT : force", 0f, 1f, adv = true),
) + CURVE_CH.flatMap { c -> (1..3).map { Slider("$c$it", "u${c.uppercase()}$it", "", -0.5f, 0.5f, hidden = true) } }

fun params(vararg v: Pair<String, Float>): FloatArray {
    val p = FloatArray(SLIDERS.size)
    for ((k, x) in v) p[SLIDERS.indexOfFirst { it.key == k }] = x
    return p
}

fun neutral() = params()

class Preset(val name: String, val p: FloatArray)

// Mêmes valeurs que src/presets.ts (looks « inspirés de » chaque film, réglés à l'œil).
val BUILTIN = listOf(
    Preset("Fight Club", params("exposure" to -0.25f, "contrast" to 0.3f, "saturation" to -0.35f, "green" to 0.6f, "grain" to 0.35f, "vignette" to 0.45f, "sharpen" to 0.3f)),
    Preset("Se7en", params("exposure" to -0.3f, "contrast" to 0.35f, "saturation" to -0.3f, "bleach" to 0.7f, "fade" to 0.1f, "temperature" to 0.05f,
        "shadowHue" to 0.25f, "shadowAmt" to 0.3f, "highlightHue" to 0.1f, "highlightAmt" to 0.25f, "grain" to 0.55f, "vignette" to 0.5f, "sharpen" to 0.35f)),
    Preset("Matrix", params("exposure" to -0.15f, "contrast" to 0.35f, "saturation" to -0.25f, "temperature" to -0.1f, "tint" to -0.4f,
        "shadowHue" to 0.36f, "shadowAmt" to 0.5f, "highlightHue" to 0.3f, "highlightAmt" to 0.35f, "grain" to 0.2f, "vignette" to 0.35f, "sharpen" to 0.3f)),
    Preset("Blade Runner 2049", params("exposure" to -0.1f, "contrast" to 0.15f, "saturation" to -0.2f, "temperature" to 0.15f, "fade" to 0.25f,
        "shadowHue" to 0.5f, "shadowAmt" to 0.45f, "highlightHue" to 0.07f, "highlightAmt" to 0.6f, "grain" to 0.25f, "vignette" to 0.4f, "sharpen" to 0.2f)),
    Preset("Mad Max", params("contrast" to 0.4f, "saturation" to 0.15f, "temperature" to 0.12f,
        "shadowHue" to 0.52f, "shadowAmt" to 0.7f, "highlightHue" to 0.08f, "highlightAmt" to 0.6f, "grain" to 0.15f, "vignette" to 0.3f, "sharpen" to 0.5f)),
    Preset("Grand Budapest", params("exposure" to 0.2f, "contrast" to -0.1f, "saturation" to 0.1f, "temperature" to 0.2f, "tint" to 0.25f, "fade" to 0.45f,
        "shadowHue" to 0.88f, "shadowAmt" to 0.25f, "highlightHue" to 0.93f, "highlightAmt" to 0.35f, "grain" to 0.1f, "vignette" to 0.15f, "sharpen" to 0.2f)),
    Preset("Amélie", params("exposure" to -0.05f, "contrast" to 0.25f, "saturation" to 0.2f, "temperature" to 0.15f, "tint" to -0.1f, "fade" to 0.1f,
        "shadowHue" to 0.3f, "shadowAmt" to 0.5f, "highlightHue" to 0.12f, "highlightAmt" to 0.4f, "grain" to 0.2f, "vignette" to 0.45f, "sharpen" to 0.3f)),
    Preset("Dune", params("exposure" to 0.05f, "contrast" to 0.3f, "temperature" to 0.25f, "fade" to 0.1f, "mono" to 0.8f,
        "shadowHue" to 0.06f, "shadowAmt" to 0.4f, "highlightHue" to 0.08f, "highlightAmt" to 0.6f, "grain" to 0.3f, "vignette" to 0.35f, "sharpen" to 0.3f)),
    Preset("Raging Bull", params("exposure" to -0.05f, "contrast" to 0.45f, "fade" to 0.05f, "mono" to 1f, "grain" to 0.6f, "vignette" to 0.5f, "sharpen" to 0.3f)),
    Preset("Sin City", params("exposure" to 0.05f, "contrast" to 0.8f, "sharpen" to 0.6f, "grain" to 0.12f, "vignette" to 0.2f, "mono" to 1f, "keepHue" to 0f, "keepAmt" to 1f)),
    Preset("John Wick 3", params("exposure" to -0.2f, "contrast" to 0.4f, "saturation" to 0.2f, "temperature" to -0.05f,
        "shadowHue" to 0.58f, "shadowAmt" to 0.5f, "highlightHue" to 0.9f, "highlightAmt" to 0.45f, "grain" to 0.1f, "vignette" to 0.35f, "sharpen" to 0.4f)),
    Preset("Kodak Portra 400", params("exposure" to 0.1f, "contrast" to 0.05f, "saturation" to -0.05f, "temperature" to 0.08f, "fade" to 0.15f,
        "shadowHue" to 0.55f, "shadowAmt" to 0.12f, "highlightHue" to 0.09f, "highlightAmt" to 0.25f, "grain" to 0.2f, "vignette" to 0.1f, "halation" to 0.1f)),
    Preset("Kodak Gold 200", params("exposure" to 0.1f, "contrast" to 0.2f, "saturation" to 0.25f, "temperature" to 0.3f, "fade" to 0.1f,
        "shadowHue" to 0.05f, "shadowAmt" to 0.15f, "highlightHue" to 0.12f, "highlightAmt" to 0.4f, "grain" to 0.35f, "vignette" to 0.2f)),
    Preset("Kodachrome", params("contrast" to 0.35f, "saturation" to 0.35f, "temperature" to 0.15f,
        "shadowHue" to 0.97f, "shadowAmt" to 0.15f, "highlightHue" to 0.1f, "highlightAmt" to 0.3f, "grain" to 0.2f, "vignette" to 0.25f)),
    Preset("Fuji Velvia", params("exposure" to -0.05f, "contrast" to 0.35f, "saturation" to 0.6f, "green" to 0.2f, "grain" to 0.1f, "sharpen" to 0.3f)),
    Preset("Classic Chrome", params("contrast" to 0.2f, "saturation" to -0.25f, "temperature" to -0.05f, "fade" to 0.15f,
        "shadowHue" to 0.58f, "shadowAmt" to 0.3f, "highlightHue" to 0.1f, "highlightAmt" to 0.2f, "grain" to 0.25f)),
    Preset("CineStill 800T", params("contrast" to 0.2f, "temperature" to -0.25f, "tint" to 0.1f,
        "shadowHue" to 0.55f, "shadowAmt" to 0.5f, "highlightHue" to 0.02f, "highlightAmt" to 0.5f, "halation" to 0.9f, "grain" to 0.45f, "grainSize" to 0.3f, "vignette" to 0.25f)),
    Preset("Ilford HP5 (N&B)", params("contrast" to 0.35f, "fade" to 0.05f, "mono" to 1f, "grain" to 0.6f, "grainSize" to 0.2f)),
    Preset("Kodak Tri-X (N&B)", params("contrast" to 0.6f, "mono" to 1f, "grain" to 0.8f, "grainSize" to 0.4f, "vignette" to 0.3f)),
    Preset("Polaroid", params("exposure" to 0.1f, "contrast" to -0.1f, "saturation" to -0.1f, "temperature" to 0.15f, "fade" to 0.5f,
        "shadowHue" to 0.5f, "shadowAmt" to 0.2f, "highlightHue" to 0.12f, "highlightAmt" to 0.3f, "grain" to 0.2f, "vignette" to 0.35f)),
    Preset("Teal & Orange", params("contrast" to 0.25f, "saturation" to 0.1f, "shadowHue" to 0.52f, "shadowAmt" to 0.6f, "highlightHue" to 0.07f, "highlightAmt" to 0.55f)),
    Preset("Cross process", params("contrast" to 0.4f, "saturation" to 0.3f, "tint" to -0.3f, "shadowHue" to 0.35f, "shadowAmt" to 0.5f, "highlightHue" to 0.15f, "highlightAmt" to 0.55f)),
    Preset("Lomo", params("exposure" to -0.05f, "contrast" to 0.5f, "saturation" to 0.35f, "green" to 0.3f, "grain" to 0.3f, "vignette" to 0.9f)),
    Preset("Vintage 70s", params("saturation" to -0.15f, "temperature" to 0.3f, "fade" to 0.4f, "shadowHue" to 0.08f, "shadowAmt" to 0.3f,
        "highlightHue" to 0.12f, "highlightAmt" to 0.4f, "grain" to 0.4f, "vignette" to 0.3f, "dust" to 0.3f)),
    Preset("Sépia", params("contrast" to 0.1f, "fade" to 0.25f, "mono" to 1f, "shadowHue" to 0.08f, "shadowAmt" to 0.7f, "highlightHue" to 0.1f, "highlightAmt" to 0.6f, "vignette" to 0.3f)),
    Preset("Néon cyberpunk", params("contrast" to 0.35f, "saturation" to 0.35f, "shadowHue" to 0.75f, "shadowAmt" to 0.6f, "highlightHue" to 0.5f, "highlightAmt" to 0.5f, "halation" to 0.5f)),
    Preset("Mat / Faded", params("exposure" to 0.05f, "contrast" to -0.15f, "saturation" to -0.2f, "fade" to 0.6f)),
    Preset("Golden hour", params("exposure" to 0.1f, "contrast" to 0.2f, "saturation" to 0.15f, "temperature" to 0.5f, "highlightHue" to 0.1f, "highlightAmt" to 0.5f, "vignette" to 0.2f)),
    Preset("Super 8", params("saturation" to -0.1f, "temperature" to 0.25f, "contrast" to 0.2f, "fade" to 0.35f, "grain" to 0.8f, "grainSize" to 0.8f, "grainColor" to 0.5f, "dust" to 0.6f, "vignette" to 0.6f, "halation" to 0.3f)),
    Preset("Grain 16 mm", params("contrast" to 0.1f, "saturation" to -0.1f, "grain" to 0.6f, "grainSize" to 0.4f, "grainColor" to 0.3f)),
    Preset("Film 35 mm (bords)", params("grain" to 0.4f, "grainSize" to 0.1f, "perf" to 1f)),
    Preset("Cinémascope", params("contrast" to 0.15f, "saturation" to -0.05f, "halation" to 0.2f, "bars" to 1f)),
    Preset("Neutre", neutral()),
)

/** Presets perso : un JSON dans les SharedPreferences, clés absentes = 0 (comme le web). */
object Custom {
    private fun sp(c: Context) = c.getSharedPreferences("emulsion", Context.MODE_PRIVATE)

    fun toJson(x: Preset): JSONObject = JSONObject().put("name", x.name).put("params", JSONObject().also { o -> SLIDERS.forEachIndexed { i, s -> o.put(s.key, x.p[i].toDouble()) } })

    /** Lève une exception si le texte n'est pas un préréglage (import / partage). */
    fun fromJson(o: JSONObject): Preset {
        val p = o.getJSONObject("params")
        return Preset(o.getString("name").take(24), FloatArray(SLIDERS.size) { p.optDouble(SLIDERS[it].key, 0.0).toFloat().coerceIn(SLIDERS[it].min, SLIDERS[it].max) })
    }

    fun load(c: Context): MutableList<Preset> = try {
        val a = JSONArray(sp(c).getString("presets", "[]"))
        MutableList(a.length()) { fromJson(a.getJSONObject(it)) }
    } catch (e: Exception) { mutableListOf() }

    fun save(c: Context, l: List<Preset>) {
        sp(c).edit().putString("presets", JSONArray().also { a -> l.forEach { a.put(toJson(it)) } }.toString()).apply()
    }
}
