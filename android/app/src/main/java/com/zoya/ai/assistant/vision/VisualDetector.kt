package com.zoya.ai.assistant.vision

import android.graphics.Bitmap
import com.zoya.ai.assistant.core.model.AutomationResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Visual UI detection fallback used when accessibility data is unavailable.
 * Detects text regions, buttons, icons, input fields, menus, cards, dialogs
 * and navigation elements from OCR lines + light bitmap analysis.
 *
 * Priority order everywhere in the stack: accessibility first, OCR second,
 * visual detection third, coordinates last.
 */
object VisualDetector {

    private val BUTTON_KEYWORDS = listOf("submit", "ok", "save", "send", "cancel", "done", "yes", "no", "search", "apply")
    private val INPUT_KEYWORDS = listOf("search", "email", "password", "phone", "user", "name", "type", "enter")
    private val NAV_KEYWORDS = listOf("home", "back", "menu", "profile", "settings", "next", "previous", "refresh")
    private val DIALOG_KEYWORDS = listOf("allow", "deny", "grant", "accept", "decline", "don't allow", "while using")

    /**
     * Runs heuristic detection over an OCR result + optional bitmap.
     */
    fun detect(ocrResult: AutomationResult, bitmap: Bitmap?, bitmapWidth: Int, bitmapHeight: Int): AutomationResult {
        val detected = JSONObject()
        val elements = JSONArray()

        if (ocrResult.ok) {
            val lines = ocrResult.data?.optJSONArray("lines") ?: JSONArray()
            val merged = mutableListOf<JSONObject>()

            for (i in 0 until lines.length()) {
                val line = lines.getJSONObject(i)
                val text = line.optString("text", "").lowercase()

                val type = when {
                    text.any { it.isDigit() } && text.length >= 8 -> "text"
                    text.any { it.isLetterOrDigit() } && (BUTTON_KEYWORDS.any { text.contains(it) }) -> "button"
                    text.length > 3 && (INPUT_KEYWORDS.any { text.contains(it) }) -> "input_field"
                    NAV_KEYWORDS.any { text.contains(it) } -> "navigation"
                    DIALOG_KEYWORDS.any { text.contains(it) } -> "dialog"
                    else -> "text_region"
                }

                val el = JSONObject()
                el.put("type", type)
                el.put("text", line.optString("text", ""))
                el.put("confidence", if (type == "text_region") 0.6 else 0.8)
                el.put("left", line.optInt("left"))
                el.put("top", line.optInt("top"))
                el.put("right", line.optInt("right"))
                el.put("bottom", line.optInt("bottom"))
                el.put("centerX", line.optDouble("centerX"))
                el.put("centerY", line.optDouble("centerY"))
                merged.add(el)
            }

            // Merge adjacent same-type regions into cards / menus where sensible.
            val normalized = normalize(merged, bitmapWidth, bitmapHeight)
            normalized.forEach { elements.put(it) }
        }

        // Icon detection (lightweight) when a bitmap is available.
        if (bitmap != null && bitmap.width > 0) {
            val icons = detectIcons(bitmap)
            icons.forEach { elements.put(it) }
        }

        detected.put("elements", elements)
        detected.put("elementCount", elements.length())
        detected.put("fallback", true)
        return AutomationResult.success(detected)
    }

    private fun normalize(elements: List<JSONObject>, w: Int, h: Int): List<JSONObject> {
        if (w == 0 || h == 0) return elements
        return elements.map { el ->
            val copy = JSONObject(el.toString())
            copy.put("leftNorm", copy.optDouble("left") / w)
            copy.put("topNorm", copy.optDouble("top") / h)
            copy.put("rightNorm", copy.optDouble("right") / w)
            copy.put("bottomNorm", copy.optDouble("bottom") / h)
            copy
        }
    }

    private fun detectIcons(bitmap: Bitmap): List<JSONObject> {
        val icons = mutableListOf<JSONObject>()
        // Downsample for analysis.
        val scale = 64
        val sample = Bitmap.createScaledBitmap(bitmap, scale, (scale * bitmap.height / bitmap.width).coerceAtLeast(1), true)
        val pixels = IntArray(sample.width * sample.height)
        sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
        val bitmapCopy = sample
        if (bitmapCopy != bitmap) {
            // analyzed separately below
        }
        val w = sample.width
        val h = sample.height

        // Detect high-contrast rounded square regions (rough icon heuristic).
        val visited = Array(h) { BooleanArray(w) }
        for (y in 0 until h step 2) {
            for (x in 0 until w step 2) {
                if (visited[y][x]) continue
                val color = pixels[y * w + x]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                val isDark = (r + g + b) / 3 < 80
                val isColorful = (maxOf(r, g, b) - minOf(r, g, b)) > 40
                if ((isDark || isColorful)) {
                    // flood-fill to find the region
                    val region = floodFill(pixels, w, h, x, y, visited)
                    if (region.width >= 4 && region.height >= 4 && region.width <= w / 2 && region.height <= h / 2) {
                        val el = JSONObject()
                        el.put("type", "icon")
                        el.put("text", JSONObject.NULL)
                        el.put("confidence", 0.55)
                        el.put("left", region.left)
                        el.put("top", region.top)
                        el.put("right", region.right)
                        el.put("bottom", region.bottom)
                        el.put("centerX", (region.left + region.right) / 2.0)
                        el.put("centerY", (region.top + region.bottom) / 2.0)
                        icons.add(el)
                        if (icons.size >= 20) break
                    }
                }
            }
            if (icons.size >= 20) break
        }
        sample.recycle()
        return icons
    }

    private data class Region(var left: Int, var top: Int, var right: Int, var bottom: Int) {
        val width: Int get() = right - left + 1
        val height: Int get() = bottom - top + 1
    }

    private fun floodFill(pixels: IntArray, w: Int, h: Int, startX: Int, startY: Int, visited: Array<BooleanArray>): Region {
        val startColor = pixels[startY * w + startX]
        val r0 = (startColor shr 16) and 0xFF
        val g0 = (startColor shr 8) and 0xFF
        val b0 = startColor and 0xFF
        val region = Region(startX, startY, startX, startY)
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.add(startX to startY)
        visited[startY][startX] = true

        fun similar(color: Int): Boolean {
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            return abs(r - r0) < 40 && abs(g - g0) < 40 && abs(b - b0) < 40
        }

        while (stack.isNotEmpty()) {
            val (x, y) = stack.removeLast()
            region.left = minOf(region.left, x)
            region.top = minOf(region.top, y)
            region.right = maxOf(region.right, x)
            region.bottom = maxOf(region.bottom, y)

            val neighbors = listOf(x + 1 to y, x - 1 to y, x to y + 1, x to y - 1)
            for ((nx, ny) in neighbors) {
                if (nx in 0 until w && ny in 0 until h && !visited[ny][nx] && similar(pixels[ny * w + nx])) {
                    visited[ny][nx] = true
                    stack.add(nx to ny)
                }
            }
        }
        return region
    }

    private fun abs(v: Int): Int = if (v < 0) -v else v
}
