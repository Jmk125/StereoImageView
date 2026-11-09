package com.jmk.stereoimageview

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Two image panes side by side with no gap (meant for landscape).
 *  - tap a pane: pick an image from the gallery
 *  - drag: pan, pinch: zoom (applied to both images while "linked", otherwise just the touched one)
 *  - long-press: rotate, link/unlink, reset, save aligned image
 */
class MainActivity : Activity() {

    private class Pane(val name: String, val view: ZoomPane, val hint: TextView) {
        var uri: Uri? = null
        var exifMatrix: Matrix = Matrix()
        var base: Bitmap? = null      // downscaled, EXIF applied
        var rotation = 0              // user rotation in degrees: 0, 90, 180, 270
    }

    private lateinit var panes: Array<Pane>
    private var pendingPane = -1
    private var linked = true
    private var sizePercent = 100
    private lateinit var pairView: LinearLayout
    private lateinit var outerView: FrameLayout

    /** Shrinks the image pair toward the centre of the screen so the eyes have less distance to travel. */
    private fun applyPairSize() {
        val w = (outerView.width * sizePercent / 100f).roundToInt()
        val h = (outerView.height * sizePercent / 100f).roundToInt()
        if (w <= 0 || h <= 0) return
        val lp = pairView.layoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            pairView.layoutParams = lp
        }
    }

    private fun chooseSize() {
        val options = intArrayOf(100, 90, 80, 70, 60, 50, 40)
        val labels = options.map { if (it == sizePercent) "$it%  (current)" else "$it%" }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Size of the image pair (smaller = eyes travel less)")
            .setItems(labels) { _, which ->
                val old = sizePercent
                sizePercent = options[which]
                getSharedPreferences("prefs", MODE_PRIVATE).edit().putInt("sizePercent", sizePercent).apply()
                // Keep each image's framing the same relative to its (resized) half.
                val ratio = sizePercent / old.toFloat()
                panes.forEach { it.view.setTransform(it.view.zoom, it.view.panX * ratio, it.view.panY * ratio) }
                applyPairSize()
            }
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashReporter()
        try {
            setUpUi(savedInstanceState)
        } catch (t: Throwable) {
            showCrash("Startup error:\n" + Log.getStackTraceString(t))
        }
        showPreviousCrash()
    }

    // Writes uncaught exceptions to a file so the next launch can show them on screen.
    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                java.io.File(filesDir, "crash.txt").writeText(Log.getStackTraceString(e))
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    private fun showPreviousCrash() {
        val f = java.io.File(filesDir, "crash.txt")
        if (!f.exists()) return
        val text = f.readText()
        f.delete()
        showCrash("The app crashed last time:\n$text")
    }

    private fun showCrash(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
            setPadding(24, 24, 24, 24)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Crash report - please screenshot this")
            .setView(scroll)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun setUpUi(savedInstanceState: Bundle?) {
        sizePercent = getSharedPreferences("prefs", MODE_PRIVATE).getInt("sizePercent", 100)
        val outer = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pairView = root
        outerView = outer
        outer.addView(root, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        outer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyPairSize() }
        panes = arrayOf(buildPane(root, 0, "Left"), buildPane(root, 1, "Right"))
        setContentView(outer)
        goImmersive() // must come after setContentView

        val st = savedInstanceState
        if (st != null) {
            pendingPane = st.getInt("pending", -1)
            linked = st.getBoolean("linked", true)
            for (i in panes.indices) {
                val s = st.getString("uri$i") ?: continue
                loadInto(
                    i, Uri.parse(s), st.getInt("rot$i"), silent = true,
                    transform = floatArrayOf(
                        st.getFloat("zoom$i", 1f), st.getFloat("px$i", 0f), st.getFloat("py$i", 0f)
                    )
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("pending", pendingPane)
        outState.putBoolean("linked", linked)
        panes.forEachIndexed { i, p ->
            p.uri?.let { outState.putString("uri$i", it.toString()) }
            outState.putInt("rot$i", p.rotation)
            outState.putFloat("zoom$i", p.view.zoom)
            outState.putFloat("px$i", p.view.panX)
            outState.putFloat("py$i", p.view.panY)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    private fun goImmersive() = try {
        applyImmersive()
    } catch (e: Exception) {
        // Cosmetic only; never let it take the app down.
    }

    private fun applyImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    // ---------------------------------------------------------------- UI

    private fun buildPane(root: LinearLayout, index: Int, name: String): Pane {
        val frame = FrameLayout(this)
        // No margins: the two halves touch so the eyes can fuse them.
        root.addView(frame, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        val zoomView = ZoomPane(this)
        frame.addView(zoomView, FrameLayout.LayoutParams(-1, -1))

        val hint = TextView(this).apply {
            text = "Tap to load $name image\n(or pick two at once)"
            setTextColor(Color.GRAY)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        frame.addView(hint, FrameLayout.LayoutParams(-1, -1))

        val pane = Pane(name, zoomView, hint)

        fun targets(): List<Pane> = if (linked) panes.toList() else listOf(pane)

        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                targets().forEach { it.view.zoomBy(d.scaleFactor, d.focusX, d.focusY) }
                return true
            }
        })
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                pick(index)
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                if (pane.base == null) pick(index) else showMenu(index)
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (scaleDetector.isInProgress || e2.pointerCount > 1) return false
                targets().forEach { it.view.pan(-dx, -dy) }
                return true
            }
        })
        frame.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            detector.onTouchEvent(ev)
            true
        }
        return pane
    }

    private fun showMenu(index: Int) {
        val pane = panes[index]
        val items = arrayOf(
            "Rotate left (90° counter-clockwise)",
            "Rotate right (90° clockwise)",
            "Swap left and right images",
            "Save aligned image",
            if (linked) "Pan/zoom: moving BOTH (tap to adjust only this one)"
            else "Pan/zoom: moving only this one (tap to move BOTH)",
            "Reset pan/zoom",
            "Image size: $sizePercent% (tap to change)",
            "Choose a different image",
        )
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("${pane.name} image")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> rotate(index, -90)
                    1 -> rotate(index, 90)
                    2 -> swapPanes()
                    3 -> saveAligned(index)
                    4 -> {
                        linked = !linked
                        Toast.makeText(
                            this,
                            if (linked) "Pan/zoom now moves both images"
                            else "Pan/zoom now moves only the image you touch",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    5 -> panes.forEach { it.view.setTransform(1f, 0f, 0f) }
                    6 -> chooseSize()
                    7 -> pick(index)
                }
            }
            .show()
    }

    private fun swapPanes() {
        val a = panes[0]
        val b = panes[1]
        val ta = floatArrayOf(a.view.zoom, a.view.panX, a.view.panY)
        val tb = floatArrayOf(b.view.zoom, b.view.panX, b.view.panY)
        val uri = a.uri; a.uri = b.uri; b.uri = uri
        val exif = a.exifMatrix; a.exifMatrix = b.exifMatrix; b.exifMatrix = exif
        val base = a.base; a.base = b.base; b.base = base
        val rot = a.rotation; a.rotation = b.rotation; b.rotation = rot
        refresh(a)
        refresh(b)
        a.view.setTransform(tb[0], tb[1], tb[2])
        b.view.setTransform(ta[0], ta[1], ta[2])
    }

    private fun rotate(index: Int, delta: Int) {
        val p = panes[index]
        p.rotation = ((p.rotation + delta) % 360 + 360) % 360
        refresh(p)
    }

    private fun rotated(b: Bitmap, degrees: Int): Bitmap =
        if (degrees == 0) b else Bitmap.createBitmap(
            b, 0, 0, b.width, b.height, Matrix().apply { setRotate(degrees.toFloat()) }, true
        )

    private fun refresh(p: Pane) {
        val b = p.base
        if (b == null) {
            p.view.setImage(null)
            p.hint.visibility = View.VISIBLE
            return
        }
        p.view.setImage(rotated(b, p.rotation))
        p.hint.visibility = View.GONE
    }

    // ---------------------------------------------------------------- picking / loading

    private fun pick(index: Int) {
        pendingPane = index
        // OPEN_DOCUMENT honours EXTRA_INITIAL_URI, so the picker can reopen where the last photo came from.
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            try {
                getSharedPreferences("prefs", MODE_PRIVATE).getString("lastUri", null)?.let {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(it))
                }
            } catch (_: Throwable) {
            }
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        if (pendingPane !in panes.indices) return
        val uris = ArrayList<Uri>()
        data?.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) } }
        if (uris.isEmpty()) data?.data?.let { uris.add(it) }
        if (uris.isNotEmpty()) {
            getSharedPreferences("prefs", MODE_PRIVATE).edit().putString("lastUri", uris.last().toString()).apply()
        }
        when {
            uris.isEmpty() -> return
            uris.size == 1 -> loadInto(pendingPane, uris[0], 0, silent = false, transform = null)
            else -> {
                // First pick goes to the side you tapped, second to the other side.
                val fresh = floatArrayOf(1f, 0f, 0f)
                loadInto(pendingPane, uris[0], 0, silent = false, transform = fresh)
                loadInto(1 - pendingPane, uris[1], 0, silent = false, transform = fresh)
                if (uris.size > 2) Toast.makeText(this, "Using the first two images", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** [transform] = (zoom, panX, panY) to restore, or null to line up with the other image. */
    private fun loadInto(index: Int, uri: Uri, rotation: Int, silent: Boolean, transform: FloatArray?) {
        thread {
            val result = try {
                val exif = readExifMatrix(uri)
                exif to decode(uri, exif, DISPLAY_MAX)
            } catch (e: Throwable) {
                null
            }
            runOnUiThread {
                if (result?.second == null) {
                    if (!silent) Toast.makeText(this, "Couldn't load that image", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val p = panes[index]
                p.uri = uri
                p.exifMatrix = result.first
                p.base = result.second
                p.rotation = rotation
                refresh(p)
                if (transform != null) {
                    p.view.setTransform(transform[0], transform[1], transform[2])
                } else {
                    // Start at the same zoom/pan as the other image so the pair stays lined up.
                    val other = panes[1 - index]
                    if (other.base != null) p.view.setTransform(other.view.zoom, other.view.panX, other.view.panY)
                    else p.view.setTransform(1f, 0f, 0f)
                }
            }
        }
    }

    private fun readExifMatrix(uri: Uri): Matrix {
        val orientation = contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { m.setRotate(180f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
        }
        return m
    }

    /** Decode so the longest side is at least [maxDim] where possible (never more than 2x over), applying EXIF. */
    private fun decode(uri: Uri, exif: Matrix, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= maxDim) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        if (exif.isIdentity) return raw
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, exif, true)
    }

    // ---------------------------------------------------------------- saving

    /**
     * Saves exactly what this half currently shows (rotation, zoom and pan applied),
     * rendered from the full-resolution photo.
     */
    private fun saveAligned(index: Int) {
        val p = panes[index]
        val uri = p.uri ?: return
        val base = p.base ?: return
        val vm = p.view.viewMatrix() ?: return
        val vw = p.view.width
        val vh = p.view.height
        val viewScale = p.view.currentScale()
        val rotation = p.rotation
        val exif = p.exifMatrix
        Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show()
        thread {
            val ok = try {
                val full = decode(uri, exif, SAVE_MAX) ?: throw IllegalStateException()
                val fullRot = rotated(full, rotation)
                // full-res pixels per display-bitmap pixel
                val r = full.width / base.width.toFloat()
                // output pixels per view pixel, so the photo is kept at full resolution
                var k = r / viewScale
                val biggest = max(vw, vh) * k
                if (biggest > SAVE_MAX) k *= SAVE_MAX / biggest

                val outW = (vw * k).roundToInt().coerceAtLeast(1)
                val outH = (vh * k).roundToInt().coerceAtLeast(1)
                val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(out)
                canvas.drawColor(Color.BLACK)
                val m = Matrix().apply {
                    setScale(1f / r, 1f / r)   // full-res px -> display px
                    postConcat(vm)             // -> view px
                    postScale(k, k)            // -> output px
                }
                canvas.drawBitmap(fullRot, m, Paint(Paint.FILTER_BITMAP_FLAG))

                val name = "stereo_${p.name.lowercase()}_${System.currentTimeMillis()}.jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StereoImageView")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val dest = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException()
                contentResolver.openOutputStream(dest)?.use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(dest, values, null, null)
                true
            } catch (e: Throwable) {
                false
            }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) "Saved to Pictures/StereoImageView" else "Save failed",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    companion object {
        private const val REQ_PICK = 1
        private const val DISPLAY_MAX = 3072
        private const val SAVE_MAX = 6000
    }
}
