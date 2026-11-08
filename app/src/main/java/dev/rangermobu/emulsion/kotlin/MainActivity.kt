package dev.rangermobu.emulsion.kotlin

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputFilter
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

// UI en Views construites en code : aucun layout XML, aucune dépendance AndroidX/Compose.
class MainActivity : Activity() {
    private val ACCENT = 0xFFD6C45A.toInt(); private val DIM = 0xFF7D8681.toInt(); private val LINE = 0xFF1F2422.toInt(); private val CHIP = 0xFF121514.toInt()
    private lateinit var gl: GLSurfaceView
    private lateinit var renderer: FilterRenderer
    private lateinit var home: View
    private lateinit var editor: View
    private lateinit var presets: LinearLayout
    private lateinit var saveBtn: TextView
    private lateinit var shareBtn: TextView
    private lateinit var backBtn: TextView
    private lateinit var msg: TextView
    private lateinit var progress: ProgressBar
    private val bars = ArrayList<SeekBar>()
    private val vals = ArrayList<TextView>()
    private lateinit var src: File
    private lateinit var custom: MutableList<Preset>
    private var rot = 0
    private var video: Uri? = null
    private var player: MediaPlayer? = null
    private var wasPlaying = false
    private var busy = false

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun ui(f: () -> Unit) = runOnUiThread(f)
    private fun say(t: String) = ui { msg.text = t }

    private fun bg(f: GradientDrawable.() -> Unit = {}, fill: Int, r: Int = 999) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(r).toFloat(); f() }

    private fun chip(t: String, primary: Boolean = false, f: () -> Unit) = TextView(this).apply {
        text = t; textSize = 13f; setPadding(dp(14), dp(9), dp(14), dp(9)); gravity = Gravity.CENTER
        setTextColor(if (primary) 0xFF111111.toInt() else 0xFFE8ECE9.toInt())
        background = bg({ if (!primary) setStroke(1, LINE) }, if (primary) ACCENT else CHIP)
        setOnClickListener { f() }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        src = File(cacheDir, "source")
        custom = Custom.load(this)
        gl = GLSurfaceView(this)
        renderer = FilterRenderer { gl.requestRender() }
        renderer.params = BUILTIN[0].p.copyOf()
        renderer.error = { say(it) }
        gl.apply { setEGLContextClientVersion(3); setPreserveEGLContextOnPause(true); setRenderer(renderer); renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY }
        gl.setOnClickListener { player?.let { if (it.isPlaying) it.pause() else it.start() } } // vidéo : touche = pause

        // accueil
        home = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(TextView(this@MainActivity).apply { text = "ÉMULSION"; letterSpacing = 0.3f; textSize = 14f; setTextColor(DIM); setPadding(0, 0, 0, dp(28)) }, LinearLayout.LayoutParams(-2, -2))
            addView(chip("Choisir une photo", true) { pick("image/*", 1) }.apply { textSize = 16f; setPadding(dp(28), dp(16), dp(28), dp(16)) }, LinearLayout.LayoutParams(-2, -2))
            addView(chip("Choisir une vidéo") { pick("video/*", 2) }.apply { textSize = 16f; setPadding(dp(28), dp(16), dp(28), dp(16)) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        }

        // éditeur
        backBtn = chip("← Autre photo") { goHome() }
        val orig = chip("Maintenir : original") {}.apply {
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { renderer.original = true; gl.requestRender() }
                    MotionEvent.ACTION_UP -> { renderer.original = false; gl.requestRender(); v.performClick() }
                    MotionEvent.ACTION_CANCEL -> { renderer.original = false; gl.requestRender() }
                }
                true
            }
        }
        val top = LinearLayout(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8)); gravity = Gravity.CENTER_VERTICAL
            addView(backBtn); addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f)); addView(orig)
        }
        presets = LinearLayout(this)
        val adv = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(16)) }
        panel.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(presets) })
        for ((i, d) in SLIDERS.withIndex()) {
            val v = TextView(this).apply { textSize = 12f; setTextColor(DIM); gravity = Gravity.END }
            val bar = SeekBar(this).apply {
                max = 1000
                if (d.hue) progressDrawable = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED)).apply { cornerRadius = dp(5).toFloat(); setSize(-1, dp(10)) }
                else { progressTintList = android.content.res.ColorStateList.valueOf(ACCENT); thumbTintList = progressTintList }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(b: SeekBar, p: Int, user: Boolean) {
                        val x = d.min + (d.max - d.min) * p / 1000f
                        v.text = if (d.hue) "${Math.round(x * 360)}°" else "${Math.round(x * 100)}"
                        if (!user) return
                        renderer.params[i] = x; gl.requestRender()
                    }
                    override fun onStartTrackingTouch(b: SeekBar) {}
                    override fun onStopTrackingTouch(b: SeekBar) {}
                })
            }
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply { text = d.label; textSize = 13f; setTextColor(DIM) }, LinearLayout.LayoutParams(dp(108), -2))
                addView(bar, LinearLayout.LayoutParams(0, dp(40), 1f)); addView(v, LinearLayout.LayoutParams(dp(40), -2))
            }
            bars += bar; vals += v
            (if (d.adv) adv else panel).addView(row)
        }
        panel.addView(TextView(this).apply {
            text = "Couleurs avancées ▸"; textSize = 13f; setTextColor(DIM); setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { val o = adv.visibility != View.VISIBLE; adv.visibility = if (o) View.VISIBLE else View.GONE; text = if (o) "Couleurs avancées ▾" else "Couleurs avancées ▸" }
        })
        panel.addView(adv)
        saveBtn = chip("Enregistrer JPEG") { export(false) }
        shareBtn = chip("Partager", true) { export(true) }
        panel.addView(LinearLayout(this).apply {
            addView(saveBtn, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(10) }); addView(shareBtn, LinearLayout.LayoutParams(0, dp(48), 1f))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE; progressTintList = android.content.res.ColorStateList.valueOf(ACCENT) }
        msg = TextView(this).apply { textSize = 13f; setTextColor(DIM); gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
        panel.addView(progress); panel.addView(msg)
        editor = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            addView(top); addView(gl, LinearLayout.LayoutParams(-1, 0, 5f))
            addView(ScrollView(this@MainActivity).apply { addView(panel) }, LinearLayout.LayoutParams(-1, 0, 4f))
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(home, ViewGroup.LayoutParams(-1, -1)); addView(editor, ViewGroup.LayoutParams(-1, -1))
            @Suppress("DEPRECATION")
            setOnApplyWindowInsetsListener { v, i -> v.setPadding(i.systemWindowInsetLeft, i.systemWindowInsetTop, i.systemWindowInsetRight, i.systemWindowInsetBottom); i }
        }
        setContentView(root)
        applyParams(BUILTIN[0].p); refreshPresets()
        handle(intent)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handle(i) }

    @Suppress("DEPRECATION")
    private fun handle(i: Intent) {
        val uri = when (i.action) {
            Intent.ACTION_SEND -> i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> i.data
            else -> null
        } ?: return
        open(uri, i.type ?: contentResolver.getType(uri))
    }

    private fun pick(type: String, code: Int) =
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE), code)

    @Deprecated("pas d'AndroidX : API Activity classique")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        val uri = data?.data ?: return
        if (res == RESULT_OK) open(uri, if (req == 2) "video/*" else "image/*")
    }

    private fun open(uri: Uri, type: String?) {
        if (type?.startsWith("video") == true) openVideo(uri) else openPhoto(uri)
    }

    private fun applyParams(p: FloatArray) {
        System.arraycopy(p, 0, renderer.params, 0, p.size)
        for ((i, d) in SLIDERS.withIndex()) bars[i].progress = ((p[i] - d.min) / (d.max - d.min) * 1000).toInt()
        gl.requestRender()
    }

    private fun refreshPresets() {
        presets.removeAllViews()
        fun add(v: View) = presets.addView(v, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        for (p in BUILTIN) add(chip(p.name) { applyParams(p.p) })
        for (p in custom) add(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = bg({ setStroke(1, LINE) }, CHIP)
            addView(TextView(this@MainActivity).apply { text = p.name; textSize = 13f; setTextColor(0xFFE8ECE9.toInt()); setPadding(dp(14), dp(9), dp(8), dp(9)); setOnClickListener { applyParams(p.p) } })
            addView(TextView(this@MainActivity).apply {
                text = "×"; textSize = 16f; setTextColor(DIM); setPadding(dp(8), dp(5), dp(12), dp(5)); contentDescription = "Supprimer ${p.name}"
                setOnClickListener { custom.remove(p); Custom.save(this@MainActivity, custom); refreshPresets() }
            })
        })
        add(chip("+ Enregistrer") {
            val e = EditText(this).apply { hint = "Nom"; filters = arrayOf(InputFilter.LengthFilter(24)); setSingleLine() }
            AlertDialog.Builder(this).setTitle("Nom du préréglage").setView(e).setNegativeButton("Annuler", null)
                .setPositiveButton("OK") { _, _ ->
                    val n = e.text.toString().trim()
                    if (n.isNotEmpty()) { custom.removeAll { it.name == n }; custom.add(Preset(n, renderer.params.copyOf())); Custom.save(this, custom); refreshPresets() }
                }.show()
        })
    }

    private fun show(editorOn: Boolean, photo: Boolean = true) {
        home.visibility = if (editorOn) View.GONE else View.VISIBLE
        editor.visibility = if (editorOn) View.VISIBLE else View.GONE
        backBtn.text = if (photo) "← Autre photo" else "← Autre vidéo"
        saveBtn.text = if (photo) "Enregistrer JPEG" else "Enregistrer la vidéo"
        msg.text = if (photo) "" else "Aperçu sans son · touche l'image pour mettre en pause · le son est conservé à l'export"
    }

    private fun stopVideo() { player?.release(); player = null; video = null; renderer.clear(gl) }

    private fun goHome() { if (!busy) { stopVideo(); show(false) } }

    @Deprecated("pas d'AndroidX")
    override fun onBackPressed() { if (editor.visibility == View.VISIBLE && !busy) goHome() else super.onBackPressed() }

    private fun fail(what: String, e: Throwable) = say("$what : ${e.message ?: e.javaClass.simpleName}")

    private fun openPhoto(uri: Uri) {
        thread {
            try {
                // copie du flux dans le cache : BitmapRegionDecoder et ExifInterface veulent un fichier seekable
                contentResolver.openInputStream(uri)!!.use { i -> src.outputStream().use { i.copyTo(it, 1 shl 16) } }
                val r = when (ExifInterface(src.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(src.path, o)
                var s = 1
                while (maxOf(o.outWidth, o.outHeight) / s > 2048) s *= 2 // aperçu <= 2048 px
                val b = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = s })
                    ?: throw IllegalArgumentException("image illisible ou format non supporté")
                renderer.setPreview(b, r); rot = r
                ui { stopVideo(); show(true); gl.requestRender() }
            } catch (e: Throwable) { fail("Ouverture impossible", e) }
        }
    }

    private fun openVideo(uri: Uri) {
        try {
            val m = MediaMetadataRetriever()
            val w: Int; val h: Int; val r: Int
            try {
                m.setDataSource(this, uri)
                fun k(c: Int) = m.extractMetadata(c)?.toIntOrNull()
                val rw = k(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: throw IllegalArgumentException("pas de piste vidéo")
                val rh = k(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: throw IllegalArgumentException("pas de piste vidéo")
                r = k(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0
                if (r % 180 != 0) { w = rh; h = rw } else { w = rw; h = rh }
            } finally { m.release() }
            stopVideo(); video = uri; show(true, false)
            renderer.setVideo(w, h) { surface ->
                ui {
                    try {
                        player = MediaPlayer().apply {
                            setDataSource(this@MainActivity, uri); setSurface(surface); isLooping = true; setVolume(0f, 0f)
                            setOnPreparedListener { it.start() }
                            setOnErrorListener { _, a, _ -> say("Lecture impossible ($a)"); true }
                            prepareAsync()
                        }
                    } catch (e: Throwable) { fail("Lecture impossible", e) }
                }
            }
            gl.requestRender()
        } catch (e: Throwable) { fail("Vidéo illisible", e) }
    }

    override fun onResume() { super.onResume(); gl.onResume(); if (wasPlaying) player?.start() }
    override fun onPause() { super.onPause(); wasPlaying = player?.isPlaying == true; player?.pause(); gl.onPause() }
    override fun onDestroy() { player?.release(); super.onDestroy() }

    private fun setBusy(b: Boolean) = ui {
        busy = b; saveBtn.alpha = if (b) .5f else 1f; shareBtn.alpha = saveBtn.alpha
        saveBtn.isEnabled = !b; shareBtn.isEnabled = !b; progress.progress = 0; progress.visibility = if (b) View.VISIBLE else View.GONE
    }

    private fun export(share: Boolean) {
        if (busy) return
        val vid = video
        setBusy(true); say(if (vid != null) "Export de la vidéo… 0 %" else "Export de la photo… 0 %")
        val p = renderer.params.copyOf()
        thread {
            val cr = contentResolver
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val coll = if (vid != null) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val mime = if (vid != null) "video/mp4" else "image/jpeg"
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "Emulsion_$stamp." + if (vid != null) "mp4" else "jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (vid != null) "Movies/Emulsion" else "Pictures/Emulsion")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(coll, v)
            try {
                requireNotNull(uri) { "MediaStore a refusé la création du fichier" }
                val prog = { x: Int -> ui { progress.progress = x; msg.text = (if (vid != null) "Export de la vidéo… " else "Export de la photo… ") + x + " %" } }
                var info = ""
                if (vid != null) {
                    cr.openFileDescriptor(uri, "rw")!!.use { VideoExporter.export(this, vid, p, it.fileDescriptor, prog) }
                } else {
                    val (w, h) = cr.openOutputStream(uri)!!.buffered(1 shl 16).use { Exporter.export(src, rot, p, it, 92, prog) }
                    info = " (${w}×$h, ${cr.openAssetFileDescriptor(uri, "r")!!.use { it.length } / 1_000_000} Mo)"
                }
                cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                say("Enregistré dans " + (if (vid != null) "Movies" else "Pictures") + "/Emulsion" + info)
                if (share) ui {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                }
            } catch (e: Throwable) {
                uri?.let { cr.delete(it, null, null) }
                fail("Export impossible", e)
            } finally { setBusy(false) }
        }
    }
}
