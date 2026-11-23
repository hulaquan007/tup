package com.example.texteraser

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var btnErase: Button
    private lateinit var btnSave: Button

    private var original: Bitmap? = null
    private var erased: Bitmap? = null

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) loadImage(uri)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.imageView)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        btnErase = findViewById(R.id.btnErase)
        btnSave = findViewById(R.id.btnSave)

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        btnErase.setOnClickListener { runErase() }
        btnSave.setOnClickListener { saveResult() }

        // 按住显示原图，松开显示结果
        imageView.setOnTouchListener { _, ev ->
            val o = original
            val e = erased
            if (o != null && e != null) {
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> imageView.setImageBitmap(o)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> imageView.setImageBitmap(e)
                }
            }
            true
        }
    }

    private fun loadImage(uri: Uri) {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                val src = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    // 限制最长边，避免超大图 OOM
                    val maxSide = 3000
                    val s = maxOf(info.size.width, info.size.height)
                    if (s > maxSide) {
                        val f = maxSide.toFloat() / s
                        dec.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt())
                    }
                }
            }
            original = bmp
            erased = null
            imageView.setImageBitmap(bmp)
            btnErase.isEnabled = true
            btnSave.isEnabled = false
            status.text = "已载入，点击“一键去除文字”"
        }
    }

    private fun runErase() {
        val src = original ?: return
        progress.visibility = View.VISIBLE
        btnErase.isEnabled = false
        lifecycleScope.launch {
            try {
                val (out, n) = TextEraser.erase(src)
                if (n == 0) {
                    status.text = "没有识别到文字"
                } else {
                    erased = out
                    imageView.setImageBitmap(out)
                    btnSave.isEnabled = true
                    status.text = "已处理 $n 处文字，长按图片对比原图"
                }
            } catch (t: Throwable) {
                status.text = "处理失败：${t.message}"
            } finally {
                progress.visibility = View.GONE
                btnErase.isEnabled = true
            }
        }
    }

    private fun saveResult() {
        val bmp = erased ?: return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "erased_${System.currentTimeMillis()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TextEraser")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext false
                contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                true
            }
            Toast.makeText(this@MainActivity, if (ok) "已保存到 Pictures/TextEraser" else "保存失败", Toast.LENGTH_SHORT).show()
        }
    }
}
