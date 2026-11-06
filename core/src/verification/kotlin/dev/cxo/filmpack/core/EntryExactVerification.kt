package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.File

/**
 * Verifies the recovered sampling0 planar signed16 worker against retained
 * native raw Q15 buffers. This intentionally bypasses the old entry golden
 * and does not change the default Pix entry path.
 *
 * Args: raw float32 grid, root signed16 RGB, native entry signed16 RGB, width.
 */
fun main(args: Array<String>) {
    require(args.size == 4) { "Expected raw grid, root Q15, native entry Q15, width" }
    val gridFile = File(args[0]); val rootFile = File(args[1]); val nativeFile = File(args[2])
    val width = args[3].toInt().also { require(it > 0) }
    fun readFloat32Le(file: File): FloatArray = DataInputStream(file.inputStream().buffered()).use { input ->
        FloatArray((file.length() / 4L).toInt()) { Float.fromBits(Integer.reverseBytes(input.readInt())) }
    }
    fun readShortLe(file: File): ShortArray = DataInputStream(file.inputStream().buffered()).use { input ->
        ShortArray((file.length() / 2L).toInt()) { java.lang.Short.reverseBytes(input.readShort()) }
    }
    val grid = readFloat32Le(gridFile)
    require(grid.size == FixedSrgbEntryLut.GRID_FLOATS)
    val source = readShortLe(rootFile); val expected = readShortLe(nativeFile)
    require(source.size == expected.size && source.size == 3_145_728) {
        "Expected the retained 1024x1024 RGB fixture"
    }
    val entry = FixedSrgbEntryLut(
        grid, FixedSrgbEntryLut.SOURCE_ICC_SHA256, FixedSrgbEntryLut.TARGET_ICC_SHA256,
        generationLcmsVersion = 2170, generationFlags = 0x40, renderingIntent = 0,
    )
    // The retained native fixture stores signed internal Q15 codes. The
    // production entry API consumes the even unsigned16 carrier whose >>1
    // recovers that Q15 domain, so construct that carrier explicitly here.
    val carrier = ShortArray(source.size) { (source[it].toInt() shl 1).toShort() }
    val actual = FloatArray(source.size)
    entry.renderRgb16ExactSampling0(carrier, actual, width)
    // Replay the same image through non-aligned horizontal tiles. The xOffset
    // overload must produce the same lane selection as the contiguous image.
    val tiled = FloatArray(source.size)
    val tileWidth = minOf(127, width)
    val tileSource = ShortArray(tileWidth * 3)
    val tileDestination = FloatArray(tileWidth * 3)
    var y = 0
    while (y < source.size / (width * 3)) {
        var x = 0
        while (x < width) {
            val rowWidth = minOf(tileWidth, width - x)
            val rowOffset = (y * width + x) * 3
            System.arraycopy(carrier, rowOffset, tileSource, 0, rowWidth * 3)
            val tileOut = tileDestination.copyOf(rowWidth * 3)
            entry.renderRgb16ExactSampling0(tileSource.copyOf(rowWidth * 3), tileOut, rowWidth, x)
            System.arraycopy(tileOut, 0, tiled, rowOffset, rowWidth * 3)
            x += rowWidth
        }
        y++
    }
    var tiledUnequal = 0
    for (i in actual.indices) if (actual[i].toRawBits() != tiled[i].toRawBits()) tiledUnequal++
    check(tiledUnequal == 0) { "Tiled exact entry mismatch: components=$tiledUnequal" }
    var unequal = 0
    var maxAbs = 0
    for (i in actual.indices) {
        require(actual[i].isFinite())
        val q15 = Math.rint(actual[i].toDouble() * 32767.0).toInt().coerceIn(-32768, 32767)
        val delta = q15 - expected[i].toInt()
        if (delta != 0) {
            if (unequal < 8) println("mismatch[$i] actual=${actual[i]} q15=$q15 expected=${expected[i].toInt()} source=${source[i].toInt() and 65535}")
            unequal++
        }
        maxAbs = maxOf(maxAbs, kotlin.math.abs(delta))
    }
    check(unequal == 0) { "Exact entry mismatch: components=$unequal maxAbs=$maxAbs" }
    println("{\"components\":${actual.size},\"unequal_q15_components\":$unequal,\"max_abs_q15\":$maxAbs,\"tiled_raw_float_unequal\":$tiledUnequal,\"tile_width\":$tileWidth,\"width\":$width,\"default_path_changed\":false}")
}
