package dev.cxo.filmpack.engine

import dev.cxo.filmpack.core.NativeGrain8Geometry
import dev.cxo.filmpack.core.PostProcessSettings

/** Reads asset files (bundle resources on iOS). Paths are relative, e.g. "films.json". */
interface AssetProvider {
    fun read(path: String): ByteArray
}

data class FilmChoice(
    val id: String, val token: String, val name: String, val category: String,
    val experimental: Boolean, val model: String, val asset: String,
    val baseRed: Float, val baseGreen: Float, val baseBlue: Float,
) {
    override fun toString(): String = name + if (experimental) " · 实验" else ""
}

enum class ExportFormat(val mimeType: String, val extension: String) { JPEG("image/jpeg", "jpg"), TIFF("image/tiff", "tif") }

data class ExportSettings(
    val format: ExportFormat = ExportFormat.JPEG,
    val jpegQuality: Int = 98,
    /** Zero keeps the original size; positive values cap the longest stored edge. */
    val maxLongEdge: Int = 0,
) {
    init {
        require(jpegQuality in 1..100) { "JPEG quality must be 1..100" }
        require(maxLongEdge >= 0) { "Maximum long edge must be non-negative" }
    }
    fun dimensions(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        val longest = maxOf(width, height)
        if (maxLongEdge == 0 || longest <= maxLongEdge) return width to height
        fun scaled(edge: Int): Int = ((edge.toLong() * maxLongEdge + longest / 2L) / longest).toInt().coerceAtLeast(1)
        return scaled(width) to scaled(height)
    }
}

data class GrainSettings(
    val enabled: Boolean = false,
    val identifier: String = "",
    val intensity: Int = 100,
    val format: NativeGrain8Geometry.Format = NativeGrain8Geometry.Format.SMALL,
    val manualSize: Float = 1f,
    val seed: Long = 17,
) {
    fun resolve(film: FilmChoice, identifiers: List<String>): String {
        val selected = identifier.ifEmpty { film.token }
        require(selected in identifiers) { "此效果没有跟随颗粒，请在颗粒设置中明确选择一种颗粒" }
        return selected
    }
}

enum class EdgePosition { ALL, LEFT, TOP, RIGHT, BOTTOM }
data class EdgeDefectSettings(val id: String = "", val intensity: Int = 50, val position: EdgePosition = EdgePosition.ALL, val seed: Long = 0L)
data class EdgeDefectChoice(val id: String, val name: String, val isNotEdge: Boolean, val grayscale: Boolean, val asset: String) {
    override fun toString() = name
}

/** Straight-alpha ARGB texture (≤1024 on its longest edge), decoded by the platform. */
class Texture(val pixels: IntArray, val width: Int, val height: Int)

data class PostEditingSettings(
    val development: PostProcessSettings = PostProcessSettings(),
    val edge: EdgeDefectSettings = EdgeDefectSettings(),
) {
    val isIdentity: Boolean get() = development.isIdentity && (edge.id.isEmpty() || edge.intensity == 0)
}
