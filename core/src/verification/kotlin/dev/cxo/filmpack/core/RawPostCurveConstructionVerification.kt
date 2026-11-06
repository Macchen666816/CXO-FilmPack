package dev.cxo.filmpack.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Compares the Kotlin parameter builder with the independent Python static reference. */
fun main(args: Array<String>) {
    require(args.size == 1) { "Expected raw-fidelity evidence directory" }
    val root = File(args[0])
    val native = File(root, "native")
    val generated = File(root, "reference/raw-post-generated")
    val contexts = listOf(
        "raw-provider-s1rii-p1040758-elite-v1",
        "raw-provider-s1rii-p1176257-elite-v1",
        "raw-provider-s9-p1035087-elite-v1",
    )

    fun floats(file: File): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size % 4 == 0) { "Unaligned float file: ${file.name}" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.float }
    }

    fun renderingEv(file: File): Float {
        val bytes = file.readBytes()
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getFloat(0x28)
    }

    fun maxAbs(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size)
        var max = 0.0
        for (i in a.indices) max = maxOf(max, abs(a[i].toDouble() - b[i].toDouble()))
        return max
    }

    var postSamples = 0
    var inverseSamples = 0
    val rows = contexts.map { context ->
        val prefix = File(native, context)
        val film = floats(File(prefix.path + ".prepared.rendering-curve-48.f32le.bin"))
        val ev = renderingEv(File(prefix.path + ".prepared.rendering-data.bin"))
        val actualPost = NativePostCurves.buildForwardPostCurve(film, ev)
        val actualInverse = NativePostCurves.buildComposedInverse(film, ev)
        val expectedPost = floats(File(generated, "$context.generated-post.f32le.bin"))
        val expectedInverse = floats(File(generated, "$context.generated-inverse.f32le.bin"))
        val postDelta = maxAbs(actualPost, expectedPost)
        val inverseDelta = maxAbs(actualInverse, expectedInverse)
        // JVM Float.pow and NumPy's vectorized exp2 differ below a few hundredths
        // of a Q15 sample; composed inverse quantization is checked separately.
        check(postDelta <= 0.05) { "$context Kotlin/Python postcurve drift=$postDelta" }
        check(inverseDelta <= 16.0) { "$context Kotlin/Python inverse drift=$inverseDelta" }
        postSamples += actualPost.size
        inverseSamples += actualInverse.size
        val nativePost = floats(File(prefix.path + ".prepared.raw-one-or-three-6144-curve-0.f32le.bin"))
        mapOf(
            "context" to context,
            "rendering_ev" to ev,
            "postcurve_python_max_abs" to postDelta,
            "composed_inverse_python_max_abs" to inverseDelta,
            "postcurve_native_max_abs" to maxAbs(actualPost, nativePost),
        )
    }
    println("{\"contexts\":${rows.size},\"postcurve_float_samples\":$postSamples," +
        "\"inverse_float_samples\":$inverseSamples,\"python_reference_tolerances\":true," +
        "\"rows\":$rows,\"native_execution\":false,\"android_execution\":false}")
}
