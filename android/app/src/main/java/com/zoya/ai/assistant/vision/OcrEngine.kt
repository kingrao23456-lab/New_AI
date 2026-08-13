package com.zoya.ai.assistant.vision

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.zoya.ai.assistant.core.model.AutomationResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-device OCR built on Google ML Kit's bundled text recognizer
 * (fully offline, no Google Play services dependency). Extracts text lines
 * with bounding boxes so results can be used for interaction when
 * accessibility data is unavailable.
 */
object OcrEngine {

    private const val TAG = "ZoyaOcr"

    @Volatile
    var captureSingleFrameRequested: Boolean = false
        private set

    private var captureDeferred: CompletableDeferred<AutomationResult>? = null

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun requestSingleFrame(): CompletableDeferred<AutomationResult> {
        val deferred = CompletableDeferred<AutomationResult>()
        captureSingleFrameRequested = true
        captureDeferred = deferred
        return deferred
    }

    /** Called by the capture service once a frame is available. */
    fun processCaptureFrame(bitmap: Bitmap) {
        captureSingleFrameRequested = false
        try {
            val result = runBlocking { recognize(bitmap) }
            captureDeferred?.complete(result)
            captureDeferred = null
        } catch (e: Exception) {
            Log.e(TAG, "OCR failed", e)
            captureDeferred?.complete(AutomationResult.failure("OCR_FAILED", "OCR failed: ${e.message}"))
            captureDeferred = null
        } finally {
            // Release the frame buffer promptly — never retain captured bitmaps.
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /**
     * Runs OCR on a bitmap. Returns structured lines with text + bounds.
     */
    suspend fun recognize(bitmap: Bitmap): AutomationResult = withContext(Dispatchers.Default) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val text = recognizer.process(image)
            val result = text.await()

            val linesJson = JSONArray()
            val allText = StringBuilder()

            for (block in result.textBlocks) {
                for (line in block.lines) {
                    val rect = line.boundingBox
                    val lineJson = JSONObject()
                    lineJson.put("text", line.text)
                    lineJson.put("confidence", 1.0)
                    if (rect != null) {
                        lineJson.put("left", rect.left)
                        lineJson.put("top", rect.top)
                        lineJson.put("right", rect.right)
                        lineJson.put("bottom", rect.bottom)
                        lineJson.put("centerX", rect.exactCenterX())
                        lineJson.put("centerY", rect.exactCenterY())
                    }
                    linesJson.put(lineJson)
                    allText.append(line.text).append('\n')
                }
            }

            val data = JSONObject()
            data.put("text", allText.toString().trim())
            data.put("lines", linesJson)
            data.put("lineCount", result.textBlocks.flatMap { it.lines }.size)
            data.put("engine", "mlkit-text-recognition-v2")
            AutomationResult.success(data)
        } catch (e: Exception) {
            Log.e(TAG, "OCR error", e)
            AutomationResult.failure("OCR_FAILED", "OCR failed: ${e.message}")
        }
    }

    /**
     * Blocks synchronously (used by the engine when OCR is needed inside a
     * workflow step). The capture service completes the deferred on the
     * capture thread.
     */
    fun awaitSingleFrame(timeoutMs: Long = 15_000): AutomationResult {
        val deferred = requestSingleFrame()
        return try {
            runBlocking {
                withContext(Dispatchers.IO) {
                    kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                        deferred.await()
                    }
                }
            } ?: AutomationResult.timeout("Screen capture timed out.")
        } catch (e: Exception) {
            AutomationResult.failure("CAPTURE_FAILED", "Screen capture failed: ${e.message}")
        } finally {
            captureSingleFrameRequested = false
        }
    }

    /** Finds whether a line contains the requested text. */
    fun findText(result: AutomationResult, query: String, partial: Boolean = true): JSONObject? {
        if (!result.ok) return null
        val data = result.data ?: return null
        val lines = data.optJSONArray("lines") ?: return null
        for (i in 0 until lines.length()) {
            val line = lines.getJSONObject(i)
            val text = line.optString("text", "")
            val match = if (partial) text.contains(query, ignoreCase = true) else text.equals(query, ignoreCase = true)
            if (match) return line
        }
        return null
    }
}
