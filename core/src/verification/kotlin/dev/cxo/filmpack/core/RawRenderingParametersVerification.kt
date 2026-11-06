package dev.cxo.filmpack.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** The expected matrices/EV are native dumps, not outputs of another copy of the builder. */
fun main(args: Array<String>) {
    require(args.size == 1)
    val b = ByteBuffer.wrap(File(args[0]).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    val magic = ByteArray(8).also(b::get)
    check(magic.contentEquals("CXORPAR1".toByteArray()) && b.int == 1)
    val count = b.int; check(count == 136)
    val labels = HashSet<String>()
    var maxPre = 0f
    var incorrectOrderGap = 0.0
    repeat(count) {
        val length = b.int; check(length in 1..256)
        val label = ByteArray(length).also(b::get).toString(Charsets.UTF_8)
        check(labels.add(label))
        val bias = b.float; val providerEv = b.float
        fun matrix() = FloatArray(9) { b.float }
        val fidelity = matrix(); val m1 = matrix(); val m2 = matrix()
        val expectedEv = b.float; val expectedPre = matrix(); val expectedPost = matrix()
        val actual = RawRenderingParameters.fromProvider(bias, providerEv, fidelity, m1, m2)
        check(actual.renderingEv.toRawBits() == expectedEv.toRawBits()) { "$label rendering EV mismatch" }
        val actualPre = actual.preMatrix()
        val error = actualPre.indices.maxOf { abs(actualPre[it] - expectedPre[it]) }
        maxPre = maxOf(maxPre, error)
        check(actualPre.indices.all { actualPre[it].toRawBits() == expectedPre[it].toRawBits() }) { "$label pre matrix mismatch $error" }
        check(actual.postMatrix().contentEquals(expectedPost)) { "$label post matrix mismatch" }
        // Reversed multiplication is a plausible former bug; this dataset must detect it.
        val feature = RawRenderingParameters.feature5Matrix(false)
        val inverseContext = (Matrix3(DoubleArray(9) { feature[it].toDouble() }) *
            Matrix3(DoubleArray(9) { fidelity[it].toDouble() })).inverse()
        val wrong = (inverseContext * Matrix3(DoubleArray(9) { m1[it].toDouble() })).values()
        incorrectOrderGap = maxOf(incorrectOrderGap, wrong.indices.maxOf { abs(wrong[it] - expectedPre[it]) })
        // Inputs/returned arrays may be reused by callers without changing this result.
        fidelity.fill(Float.NaN); m1.fill(Float.NaN); m2.fill(Float.NaN)
        actualPre.fill(Float.NaN); actual.postMatrix().fill(Float.NaN)
        check(actual.preMatrix().all(Float::isFinite) && actual.postMatrix().contentEquals(expectedPost))
    }
    check(!b.hasRemaining())
    check(labels.map { it.substringBefore('/') }.toSet().size == 68)
    check(incorrectOrderGap > .1)
    fun rejects(block: () -> Unit) { check(runCatching(block).isFailure) }
    val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    rejects { RawRenderingParameters.fromProvider(0f, 0f, FloatArray(9), identity, identity) }
    rejects { RawRenderingParameters.fromProvider(Float.NaN, 0f, identity, identity, identity) }
    rejects { RawRenderingParameters.fromProvider(0f, Float.POSITIVE_INFINITY, identity, identity, identity) }
    rejects { RawRenderingParameters.fromProvider(0f, 0f, FloatArray(8), identity, identity) }
    rejects { RawRenderingParameters.fromProvider(0f, 0f, identity, identity.copyOf().also { it[0] = Float.NaN }, identity) }
    val default = RawRenderingParameters.fromProvider(.6f, .3f, identity, identity, identity)
    val alternate = RawRenderingParameters.fromProvider(.6f, .3f, identity, identity, identity, true)
    check(!default.preMatrix().contentEquals(alternate.preMatrix()))
    println("RAW provider parameter builder: $count native contexts; rendering EV and post exact; pre max=$maxPre; ownership/singular/nonfinite checks passed. Camera/provider selection remains caller-owned.")
}
