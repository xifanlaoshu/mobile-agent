package com.xhs.agent.collector

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.xhs.agent.model.OcrTextBlock
import com.xhs.agent.model.Rect

/**
 * OCR 文字识别处理器
 *
 * 方案选项：
 * 1. Tesseract (tess-two) — 离线可用，无需 Google 服务，适配 EMUI
 * 2. ML Kit — 准确率高，但依赖 Google Play Services（荣耀 V20 可能没有）
 *
 * 推荐：tess-two — 全离线，兼容性好
 */
class OcrProcessor(private val context: Context) {

    companion object {
        private const val TAG = "OcrProcessor"
        private const val TESS_LANGUAGE = "chi_sim"  // 中文简体
    }

    // Tesseract API 初始化（需在子线程）
    // private lateinit var tesseract: TessBaseAPI

    /**
     * 初始化 OCR 引擎
     */
    suspend fun init(): Boolean {
        return try {
            // Tesseract 初始化：
            // 1. 将训练数据文件复制到 ${filesDir}/tessdata/${LANG}.traineddata
            // 2. TessBaseAPI().apply {
            //        init(DATA_PATH, TESS_LANGUAGE)
            //        setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            //    }
            Log.d(TAG, "OCR initialized (placeholder)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "OCR init failed", e)
            false
        }
    }

    /**
     * 识别图片中的文字
     *
     * @param bitmap 截图 Bitmap
     * @return 识别到的文字块列表
     */
    suspend fun recognize(bitmap: Bitmap): List<OcrTextBlock> {
        if (bitmap.isRecycled) return emptyList()

        return try {
            // 1. 可选：降采样以减少处理时间
            // 2. 调用 Tesseract
            // 3. 解析结果框

            // tesseract.setImage(bitmap)
            // val text = tesseract.utf8Text
            // val boxes = tesseract.boundingBoxes

            // 结果转换为 OcrTextBlock 列表
            Log.d(TAG, "OCR processing ${bitmap.width}x${bitmap.height} image (placeholder)")
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "OCR recognition failed", e)
            emptyList()
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        // tesseract?.recycle()
        Log.d(TAG, "OCR released")
    }
}
