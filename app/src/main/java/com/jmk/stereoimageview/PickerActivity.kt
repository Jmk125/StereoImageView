package com.jmk.stereoimageview

import android.app.Activity
import android.content.ClipData
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Photo grid (newest first) with checkboxes for up to two photos.
 * Reopens scrolled to the last photo that was picked.
 * Returns the chosen photos, in the order they were ticked, as ClipData.
 */
class PickerActivity : Activity() {

    private class Item(val id: Long, val ts: Long)

    private val items = ArrayList<Item>()
    private val selected = ArrayList<Long>() // ids in tick order
    private lateinit var grid: GridView
    private lateinit var doneButton: Button
    private val adapter = PhotoAdapter()
    private val pool = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }

    private var firstName = "Left"
    private var secondName = "Right"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val firstSide = intent.getIntExtra(EXTRA_FIRST_SIDE, 0)
        firstName = if (firstSide == 0) "Left" else "Right"
        secondName = if (firstSide == 0) "Right" else "Left"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(110)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(2)
            verticalSpacing = dp(2)
            adapter = this@PickerActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> toggle(items[pos].id) }
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))

        doneButton = Button(this).apply {
            setOnClickListener { finishWithSelection() }
        }
        root.addView(doneButton, LinearLayout.LayoutParams(-1, -2))
        updateButton()

        // Targeting SDK 35 draws edge to edge; keep the grid clear of the system bars.
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
            }
            insets
        }
        setContentView(root)
        loadPhotos()
    }

    override fun onDestroy() {
        super.onDestroy()
        pool.shutdownNow()
    }

    private fun loadPhotos() {
        thread {
            val list = ArrayList<Item>()
            try {
                val proj = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.DATE_ADDED,
                )
                contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val taken = c.getLong(1)
                        val added = c.getLong(2) * 1000
                        list.add(Item(c.getLong(0), max(taken, added)))
                    }
                }
            } catch (e: Throwable) {
                // handled below as an empty list
            }
            list.sortByDescending { it.ts }
            runOnUiThread {
                items.clear()
                items.addAll(list)
                adapter.notifyDataSetChanged()
                if (items.isEmpty()) {
                    Toast.makeText(this, "No photos found", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val lastId = getSharedPreferences("prefs", MODE_PRIVATE).getLong("lastMediaId", -1L)
                val idx = items.indexOfFirst { it.id == lastId }
                if (idx > 0) {
                    // Land on the last photo you picked, with one row of context above it.
                    grid.post { grid.setSelection(max(0, idx - max(1, grid.numColumns))) }
                }
            }
        }
    }

    private fun toggle(id: Long) {
        if (!selected.remove(id)) {
            if (selected.size >= 2) selected.removeAt(0)
            selected.add(id)
        }
        adapter.notifyDataSetChanged()
        updateButton()
    }

    private fun updateButton() {
        when (selected.size) {
            0 -> {
                doneButton.text = "Tap up to 2 photos"
                doneButton.isEnabled = false
            }
            1 -> {
                doneButton.text = "Use 1 photo  →  $firstName"
                doneButton.isEnabled = true
            }
            else -> {
                doneButton.text = "Done:  1st → $firstName,  2nd → $secondName"
                doneButton.isEnabled = true
            }
        }
    }

    private fun uriOf(id: Long) = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    private fun finishWithSelection() {
        if (selected.isEmpty()) return
        val uris = selected.map { uriOf(it) }
        val clip = ClipData.newRawUri("photos", uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        val result = Intent().apply {
            clipData = clip
            data = uris[0]
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        getSharedPreferences("prefs", MODE_PRIVATE).edit().putLong("lastMediaId", selected.last()).apply()
        setResult(RESULT_OK, result)
        finish()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- grid

    private inner class Cell : FrameLayout(this@PickerActivity) {
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.DKGRAY)
        }
        val badge = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF1E88E5.toInt())
                setStroke(dp(2), Color.WHITE)
            }
        }

        init {
            addView(image, LayoutParams(-1, -1))
            addView(badge, LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(6), dp(6), 0)
            })
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec) // square cells
        }

        fun bind(item: Item) {
            image.tag = item.id
            val cached = cache.get(item.id)
            if (cached != null) {
                image.setImageBitmap(cached)
            } else {
                image.setImageBitmap(null)
                pool.execute {
                    val bmp = try {
                        contentResolver.loadThumbnail(uriOf(item.id), Size(256, 256), null)
                    } catch (e: Throwable) {
                        null
                    }
                    if (bmp != null) {
                        cache.put(item.id, bmp)
                        runOnUiThread { if (image.tag == item.id) image.setImageBitmap(bmp) }
                    }
                }
            }
            val order = selected.indexOf(item.id)
            if (order >= 0) {
                badge.visibility = View.VISIBLE
                badge.text = (order + 1).toString()
                image.alpha = 0.55f
            } else {
                badge.visibility = View.GONE
                image.alpha = 1f
            }
        }
    }

    private inner class PhotoAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = (convertView as? Cell) ?: Cell()
            cell.bind(items[position])
            return cell
        }
    }

    companion object {
        const val EXTRA_FIRST_SIDE = "firstSide"
    }
}
