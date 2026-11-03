package com.livestudio

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.input.gl.render.filters.CartoonFilterRender
import com.pedro.encoder.input.gl.render.filters.EdgeDetectionFilterRender
import com.pedro.encoder.input.gl.render.filters.GreyScaleFilterRender
import com.pedro.encoder.input.gl.render.filters.NegativeFilterRender
import com.pedro.encoder.input.gl.render.filters.PixelatedFilterRender
import com.pedro.encoder.input.gl.render.filters.SepiaFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.TextObjectFilterRender
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView
import java.io.File

class MainActivity : AppCompatActivity(), ConnectChecker {

    private lateinit var cam: RtmpCamera2
    private lateinit var gl: OpenGlView
    private lateinit var info: TextView
    private lateinit var urlBox: EditText
    private lateinit var web: WebView
    private val ui = Handler(Looper.getMainLooper())

    private var surfaceReady = false
    private var granted = false
    private var wantLive = false
    private var retries = 0
    private var micOn = true
    private var colorFilter: BaseFilterRender? = null
    private var textFilter: TextObjectFilterRender? = null

    // ---- scorecard overlay (web page -> bitmap -> image filter)
    private var webImg: ImageObjectFilterRender? = null
    private var webOn = false
    private var webCropH = 450
    private val WEB_W = 800
    private val webTick = object : Runnable {
        override fun run() {
            if (!webOn) return
            try {
                val bmp = Bitmap.createBitmap(WEB_W, webCropH, Bitmap.Config.ARGB_8888)
                web.draw(Canvas(bmp))
                webImg?.setImage(bmp)
            } catch (e: Exception) { }
            ui.postDelayed(this, 1000)
        }
    }

    // ---- replay (rolling segments)
    private var segFile: File? = null
    private var replaying = false
    private val segDir by lazy { File(cacheDir, "seg").apply { mkdirs() } }
    private val roll = Runnable {
        if (cam.isStreaming && !replaying) {
            if (cam.isRecording) cam.stopRecord()
            cleanSegs(); startSeg()
        }
    }

    private val qualities = listOf(
        arrayOf("480p", 854, 480, 30, 1_500_000),
        arrayOf("720p", 1280, 720, 30, 3_500_000),
        arrayOf("1080p", 1920, 1080, 30, 6_000_000)
    )
    private var q = 1

    private val filters = listOf<Pair<String, (() -> BaseFilterRender)?>>(
        "None" to null,
        "Mono" to { GreyScaleFilterRender() },
        "Cartoon" to { CartoonFilterRender() },
        "Sepia" to { SepiaFilterRender() },
        "Negative" to { NegativeFilterRender() },
        "Emboss / Edge" to { EdgeDetectionFilterRender() },
        "Mosaic" to { PixelatedFilterRender() }
    )

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        gl = findViewById(R.id.glView)
        info = findViewById(R.id.info)
        urlBox = findViewById(R.id.url)
        urlBox.setText(getPreferences(MODE_PRIVATE).getString("url", ""))
        cam = RtmpCamera2(gl, this)

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.setBackgroundColor(Color.TRANSPARENT)
        web.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        web.webViewClient = WebViewClient()
        findViewById<FrameLayout>(R.id.root).addView(web, 0, FrameLayout.LayoutParams(WEB_W, 450))

        findViewById<Button>(R.id.bLive).setOnClickListener { toggleLive(it as Button) }
        findViewById<Button>(R.id.bRec).setOnClickListener { askReplay() }
        findViewById<Button>(R.id.bFlip).setOnClickListener { if (cam.isOnPreview) cam.switchCamera() }
        findViewById<Button>(R.id.bFilter).setOnClickListener { pickFilter() }
        findViewById<Button>(R.id.bText).setOnClickListener { askText() }
        findViewById<Button>(R.id.bScore).setOnClickListener { askScore() }
        findViewById<Button>(R.id.bMic).setOnClickListener {
            micOn = !micOn
            if (micOn) cam.enableAudio() else cam.disableAudio()
            (it as Button).text = if (micOn) "Mic on" else "Mic off"
        }
        findViewById<Button>(R.id.bQ).setOnClickListener {
            if (cam.isStreaming) { toast("Live cholar somoy quality change hobe na"); return@setOnClickListener }
            AlertDialog.Builder(this).setItems(qualities.map { it[0] as String }.toTypedArray()) { _, i ->
                q = i; (it as Button).text = qualities[i][0] as String
                if (cam.isOnPreview) cam.stopPreview()
                startPreviewIfReady()
            }.show()
        }

        gl.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {}
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) { surfaceReady = true; startPreviewIfReady() }
            override fun surfaceDestroyed(h: SurfaceHolder) {
                surfaceReady = false
                ui.removeCallbacks(roll)
                if (cam.isRecording) cam.stopRecord()
                if (cam.isStreaming) { cam.stopStream(); wantLive = false }
                if (cam.isOnPreview) cam.stopPreview()
            }
        })

        val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        granted = need.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (!granted) ActivityCompat.requestPermissions(this, need, 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        granted = res.isNotEmpty() && res.all { it == PackageManager.PERMISSION_GRANTED }
        if (granted) startPreviewIfReady() else info.text = "Camera ar Mic permission dorkar"
    }

    private fun startPreviewIfReady() {
        if (granted && surfaceReady && !cam.isOnPreview) {
            val a = qualities[q]
            cam.startPreview(CameraHelper.Facing.BACK, a[1] as Int, a[2] as Int)
            info.text = "Ready"
        }
    }

    private fun prepare(): Boolean {
        val a = qualities[q]
        val rot = CameraHelper.getCameraOrientation(this)
        return cam.prepareAudio() && cam.prepareVideo(a[1] as Int, a[2] as Int, a[3] as Int, a[4] as Int, rot)
    }

    private fun toggleLive(b: Button) {
        if (cam.isStreaming) {
            wantLive = false; stopAll(); b.text = "Go live"; info.text = "Stopped"; return
        }
        val url = urlBox.text.toString().trim()
        if (!url.startsWith("rtmp://") && !url.startsWith("rtmps://")) { toast("rtmp:// ba rtmps:// URL din (server URL + stream key)"); return }
        getPreferences(MODE_PRIVATE).edit().putString("url", url).apply()
        wantLive = true; retries = 0
        startLive(b)
    }

    private fun stopAll() {
        ui.removeCallbacks(roll)
        if (cam.isRecording) cam.stopRecord()
        if (cam.isStreaming) cam.stopStream()
    }

    private fun startLive(b: Button) {
        if (!cam.isOnPreview) { toast("Preview chalu hoyni"); wantLive = false; return }
        if (!cam.isStreaming && !prepare()) { toast("Encoder chalu hoyni, quality komiye dekhun"); wantLive = false; return }
        cam.startStream(urlBox.text.toString().trim())
        b.text = "Stop"; info.text = "Connecting..."
    }

    // ---------------- rolling segments + replay ----------------
    private fun startSeg() {
        if (!cam.isStreaming || cam.isRecording) return
        val f = File(segDir, "s_${System.currentTimeMillis()}.mp4")
        try { cam.startRecord(f.absolutePath); segFile = f } catch (e: Exception) { segFile = null }
        ui.postDelayed(roll, 15000)
    }

    private fun cleanSegs() {
        val all = segDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        all.dropLast(2).forEach { it.delete() }
    }

    private fun askReplay() {
        if (replaying) return
        if (!cam.isStreaming) { toast("Replay er jonno age Go live korun"); return }
        AlertDialog.Builder(this).setTitle("Instant replay (last 8 sec)")
            .setItems(arrayOf("Normal speed", "Slow motion")) { _, i -> doReplay(i == 1) }.show()
    }

    private fun doReplay(slow: Boolean) {
        val f = segFile
        if (f == null || !cam.isRecording) { toast("Buffer toiri hoyni, live chalu kore 10 sec opekkha korun"); return }
        replaying = true
        ui.removeCallbacks(roll)
        cam.stopRecord()
        info.text = "Replay toiri hocche..."
        Thread {
            val frames = extractFrames(f)
            runOnUiThread { playFrames(frames, if (slow) 250L else 125L) }
        }.start()
    }

    private fun extractFrames(f: File): List<Bitmap> {
        val out = ArrayList<Bitmap>()
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var t = maxOf(0L, d - 8000L)
            val paint = Paint().apply { color = Color.WHITE; textSize = 34f; isFakeBoldText = true }
            val bg = Paint().apply { color = Color.rgb(200, 50, 43) }
            while (t < d) {
                val b = r.getFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                if (b != null) {
                    val m = Bitmap.createScaledBitmap(b, 960, 540, true).copy(Bitmap.Config.ARGB_8888, true)
                    val c = Canvas(m)
                    c.drawRect(20f, 20f, 230f, 76f, bg)
                    c.drawText("REPLAY", 40f, 60f, paint)
                    out.add(m)
                }
                t += 125L
            }
        } catch (e: Exception) { }
        try { r.release() } catch (e: Exception) { }
        return out
    }

    private fun playFrames(frames: List<Bitmap>, step: Long) {
        if (frames.isEmpty()) { toast("Replay toiri hoyni"); replaying = false; startSeg(); return }
        val gi = cam.getGlInterface()
        val img = ImageObjectFilterRender()
        webImg?.let { gi.removeFilter(it) }
        gi.addFilter(img)
        webImg?.let { gi.addFilter(it) }
        img.setImage(frames[0])
        img.setScale(100f, 100f)
        img.setPosition(TranslateTo.CENTER)
        info.text = "REPLAY"
        var i = 0
        val tick = object : Runnable {
            override fun run() {
                if (i < frames.size) { img.setImage(frames[i]); i++; ui.postDelayed(this, step) }
                else {
                    gi.removeFilter(img)
                    replaying = false
                    if (cam.isStreaming) { info.text = "LIVE"; startSeg() }
                }
            }
        }
        ui.post(tick)
    }

    // ---------------- scorecard from URL ----------------
    private fun askScore() {
        val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL; box.setPadding(32, 16, 32, 0)
        val url = EditText(this); url.hint = "Scorecard page URL (https://...)"
        url.setText(getPreferences(MODE_PRIVATE).getString("score", ""))
        val posN = arrayOf("Top left", "Top right", "Top center", "Bottom left", "Bottom right", "Bottom center")
        val posV = arrayOf(TranslateTo.TOP_LEFT, TranslateTo.TOP_RIGHT, TranslateTo.TOP, TranslateTo.BOTTOM_LEFT, TranslateTo.BOTTOM_RIGHT, TranslateTo.BOTTOM)
        val sizeN = arrayOf("Small", "Medium", "Large"); val sizeV = floatArrayOf(30f, 45f, 65f)
        val cropN = arrayOf("Full page top", "Top half", "Top strip"); val cropV = intArrayOf(450, 225, 120)
        val sp = Spinner(this); sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, posN)
        val ss = Spinner(this); ss.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sizeN); ss.setSelection(1)
        val sc = Spinner(this); sc.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cropN); sc.setSelection(1)
        box.addView(url); box.addView(sp); box.addView(ss); box.addView(sc)
        AlertDialog.Builder(this).setTitle("Scorecard overlay").setView(box)
            .setPositiveButton("Show") { _, _ ->
                val u = url.text.toString().trim()
                if (u.isNotEmpty()) {
                    getPreferences(MODE_PRIVATE).edit().putString("score", u).apply()
                    showScore(if (u.startsWith("http")) u else "https://$u", posV[sp.selectedItemPosition], sizeV[ss.selectedItemPosition], cropV[sc.selectedItemPosition])
                }
            }
            .setNegativeButton("Remove") { _, _ -> removeScore() }
            .show()
    }

    private fun removeScore() {
        webOn = false; ui.removeCallbacks(webTick)
        webImg?.let { cam.getGlInterface().removeFilter(it) }
        webImg = null
    }

    private fun showScore(u: String, pos: TranslateTo, sizePct: Float, cropH: Int) {
        removeScore()
        webCropH = cropH
        val img = ImageObjectFilterRender()
        cam.getGlInterface().addFilter(img)
        img.setImage(Bitmap.createBitmap(WEB_W, cropH, Bitmap.Config.ARGB_8888))
        img.setScale(sizePct, sizePct * (cropH / WEB_W.toFloat()) * (16f / 9f))
        img.setPosition(pos)
        webImg = img
        web.loadUrl(u)
        webOn = true
        ui.postDelayed(webTick, 2000)
    }

    // ---------------- filters / text ----------------
    private fun applyColor(f: BaseFilterRender?) {
        colorFilter?.let { cam.getGlInterface().removeFilter(it) }
        colorFilter = f
        f?.let { cam.getGlInterface().addFilter(it) }
    }

    private fun pickFilter() {
        AlertDialog.Builder(this).setItems(filters.map { it.first }.toTypedArray()) { _, i ->
            applyColor(filters[i].second?.invoke())
        }.show()
    }

    private fun askText() {
        val et = EditText(this)
        AlertDialog.Builder(this).setTitle("Overlay text (khali rakhle muche jabe)").setView(et)
            .setPositiveButton("OK") { _, _ ->
                textFilter?.let { cam.getGlInterface().removeFilter(it) }
                textFilter = null
                val t = et.text.toString()
                if (t.isNotBlank()) {
                    val f = TextObjectFilterRender()
                    cam.getGlInterface().addFilter(f)
                    f.setText(t, 22f, Color.WHITE)
                    f.setPosition(TranslateTo.BOTTOM)
                    textFilter = f
                }
            }.show()
    }

    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }

    // ---------------- ConnectChecker (background thread) ----------------
    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() { retries = 0; runOnUiThread { info.text = "LIVE"; startSeg() } }
    override fun onNewBitrate(bitrate: Long) { runOnUiThread { if (cam.isStreaming && !replaying) info.text = "LIVE  ${bitrate / 1000} kbps" } }
    override fun onConnectionFailed(reason: String) {
        runOnUiThread {
            stopAll(); replaying = false
            val b = findViewById<Button>(R.id.bLive)
            if (wantLive && retries < 3) {
                retries++; info.text = "Reconnecting ($retries/3)..."
                ui.postDelayed({ if (wantLive) startLive(b) }, 3000)
            } else {
                wantLive = false; b.text = "Go live"; info.text = "Failed: $reason"
            }
        }
    }
    override fun onDisconnect() { runOnUiThread { if (!wantLive) info.text = "Disconnected" } }
    override fun onAuthError() { toast("Stream key bhul") }
    override fun onAuthSuccess() {}
}
