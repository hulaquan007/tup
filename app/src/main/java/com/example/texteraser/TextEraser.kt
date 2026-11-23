package com.example.texteraser

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.max
import kotlin.math.min

/**
 * 流程：ML Kit 端侧识别文字 -> 在每个文字框内按笔画生成精细蒙版 -> OpenCV inpaint 修复背景。
 * 全程离线，图片不会上传。
 */
object TextEraser {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** 返回 Pair(结果图, 识别到的文字块数量)。 */
    suspend fun erase(src: Bitmap): Pair<Bitmap, Int> = withContext(Dispatchers.Default) {
        check(OpenCVLoader.initLocal()) { "OpenCV 初始化失败" }

        val bmp = src.copy(Bitmap.Config.ARGB_8888, false)
        val result = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()

        val boxes = result.textBlocks
            .flatMap { it.lines }
            .flatMap { line ->
                line.elements.mapNotNull { e -> e.boundingBox }
                    .ifEmpty { listOfNotNull(line.boundingBox) }
            }
        if (boxes.isEmpty()) return@withContext bmp to 0

        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)

        val mask = Mat.zeros(rgb.size(), org.opencv.core.CvType.CV_8UC1)
        var avgH = 0.0

        for (b in boxes) {
            val pad = max(2, (b.height() * 0.15).toInt())
            val r = Rect(
                max(0, b.left - pad), max(0, b.top - pad),
                min(bmp.width, b.right + pad), min(bmp.height, b.bottom + pad)
            )
            if (r.width() < 3 || r.height() < 3) continue
            avgH += r.height()

            val roi = gray.submat(r.top, r.bottom, r.left, r.right)
            val blur = Mat()
            Imgproc.GaussianBlur(roi, blur, Size(3.0, 3.0), 0.0)
            val bin = Mat()
            Imgproc.threshold(blur, bin, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            // 文字像素通常是少数：若白色占多数则反相
            if (org.opencv.core.Core.countNonZero(bin) > bin.total() / 2) {
                org.opencv.core.Core.bitwise_not(bin, bin)
            }
            // 膨胀以盖住抗锯齿/描边/阴影
            val k = max(3, (r.height() * 0.12).toInt() or 1)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble()))
            Imgproc.dilate(bin, bin, kernel)
            bin.copyTo(mask.submat(r.top, r.bottom, r.left, r.right))

            roi.release(); blur.release(); bin.release(); kernel.release()
        }

        val radius = max(3.0, avgH / boxes.size * 0.25)
        val out = Mat()
        Photo.inpaint(rgb, mask, out, radius, Photo.INPAINT_TELEA)

        val outRgba = Mat()
        Imgproc.cvtColor(out, outRgba, Imgproc.COLOR_RGB2RGBA)
        val outBmp = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(outRgba, outBmp)

        listOf(rgba, rgb, gray, mask, out, outRgba).forEach { it.release() }
        outBmp to boxes.size
    }
}
