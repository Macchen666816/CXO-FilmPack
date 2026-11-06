package dev.cxo.filmpack.core

import java.io.DataInputStream
import java.io.File
import kotlin.math.abs

private fun readMathDouble(input: DataInputStream) =
    java.lang.Double.longBitsToDouble(java.lang.Long.reverseBytes(input.readLong()))

/** Cross-language primitive checks; not a comparison against native FilmPack execution. */
fun main(args: Array<String>) {
    require(args.size==1) { "Expected Python-generated JPEG primitive fixture" }
    val input=DataInputStream(File(args[0]).inputStream().buffered())
    val magic=ByteArray(8); input.readFully(magic)
    check(magic.contentEquals("FPMATH02".toByteArray(Charsets.US_ASCII)))
    val n=Integer.reverseBytes(input.readInt()); val nm=Integer.reverseBytes(input.readInt()); val nq=Integer.reverseBytes(input.readInt())
    check(n in 4096..20000 && nm in 16..256)
    val table=FloatArray(32768) { java.lang.Float.intBitsToFloat(Integer.reverseBytes(input.readInt())) }
    val q15=Q15Table(table); table[0]=Float.NaN // ownership is copied
    val out=DoubleArray(5) { -7.0 }; val names=arrayOf("forward","inverse","blend","monochrome","q15","matrix_transform","matrix_composition","matrix_inverse")
    val maxima=DoubleArray(names.size)
    fun compare(metric: Int, actual: Double, expected: Double) {
        check(actual.isFinite() && expected.isFinite())
        maxima[metric]=maxOf(maxima[metric],abs(actual-expected))
        check(abs(actual-expected)<1E-10) { names[metric]+" mismatch: "+actual+" vs "+expected }
    }
    repeat(n) {
        val r=DoubleArray(20) { readMathDouble(input) }
        JpegTone.forward(r[0],r[1],r[2],out,1)
        for (c in 0..2) compare(0,out[c+1],r[c+3])
        JpegTone.inverse(r[0],r[1],r[2],out,1)
        for (c in 0..2) compare(1,out[c+1],r[c+6])
        JpegTone.blendAndClamp(r[0],r[1],r[2],r[9],r[10],r[11],r[12],out,1)
        for (c in 0..2) compare(2,out[c+1],r[c+13])
        JpegTone.monochrome(r[0],r[1],r[2],out,1)
        for (c in 0..2) compare(3,out[c+1],r[c+16])
        compare(4,q15.sample(r[0]),r[19])
        check(out[0]==-7.0 && out[4]==-7.0)
    }
    repeat(nm) {
        val r=DoubleArray(42) { readMathDouble(input) }
        val a=Matrix3(r.copyOfRange(0,9)); val b=Matrix3(r.copyOfRange(9,18))
        a.transform(r[18],r[19],r[20],out,1)
        for (c in 0..2) compare(5,out[c+1],r[c+21])
        val ab=(a*b).values(); val inverse=a.inverse().values()
        for (c in 0..8) { compare(6,ab[c],r[c+24]); compare(7,inverse[c],r[c+33]) }
        val identity=(a*a.inverse()).values()
        for (c in 0..8) check(abs(identity[c]-(if (c%4==0) 1.0 else 0.0))<1E-10)
        val copy=a.values(); copy[0]=Double.NaN; check(a.values()[0].isFinite())
    }
    check(nq in 6..512)
    repeat(nq) {
        val values=FloatArray(18) { java.lang.Float.intBitsToFloat(Integer.reverseBytes(input.readInt())) }
        val source=values.copyOfRange(0,9); val output=FloatArray(9)
        NativeMatrixQuantization.forceRowSumsToOne(source,output)
        for (i in 0..8) check(output[i].toRawBits()==values[i+9].toRawBits())
        NativeMatrixQuantization.forceRowSumsToOne(source,source)
        for (i in 0..8) check(source[i].toRawBits()==output[i].toRawBits())
    }
    val badMatrices=listOf(
        FloatArray(9),
        FloatArray(9) { Float.NaN },
        floatArrayOf(1f,-1f,0f,0f,1f,0f,0f,0f,1f),
        floatArrayOf(Float.MIN_VALUE,0f,0f,0f,1f,0f,0f,0f,1f),
        floatArrayOf(Float.MAX_VALUE,Float.MAX_VALUE,0f,0f,1f,0f,0f,0f,1f),
        floatArrayOf(1f,-1f,1E-10f,0f,1f,0f,0f,0f,1f),
    )
    for (bad in badMatrices) {
        val sentinel=FloatArray(9) { -7f }
        check(runCatching { NativeMatrixQuantization.forceRowSumsToOne(bad,sentinel) }.isFailure)
        check(sentinel.all { it==-7f })
    }
    check(input.read()==-1); input.close()
    for (x in doubleArrayOf(Double.NaN,Double.POSITIVE_INFINITY,-0.001,1.001)) {
        check(runCatching { JpegTone.forward(x,0.0,0.0,out) }.isFailure)
        check(runCatching { JpegTone.inverse(0.0,x,0.0,out) }.isFailure)
        check(runCatching { JpegTone.monochrome(0.0,0.0,x,out) }.isFailure)
        check(runCatching { q15.sample(x) }.isFailure)
    }
    check(runCatching { JpegTone.forward(0.0,0.0,0.0,out,Int.MAX_VALUE) }.isFailure)
    check(runCatching { Q15Table(FloatArray(2)) }.isFailure)
    check(runCatching { Q15Table(FloatArray(32768) { Float.NaN }) }.isFailure)
    check(runCatching { Matrix3(DoubleArray(9)).inverse() }.isFailure)
    check(runCatching { Matrix3(doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1E-13)).inverse() }.isFailure)
    check(runCatching { Matrix3(DoubleArray(9) { Double.POSITIVE_INFINITY }) }.isFailure)
    check(runCatching { Matrix3(DoubleArray(8)) }.isFailure)
    check(runCatching { JpegTone.blendAndClamp(0.0,0.0,0.0,1.0,1.0,1.0,Double.NaN,out) }.isFailure)
    val quote=34.toChar()
    val metrics=names.indices.joinToString(",") { quote.toString()+names[it]+quote+":"+maxima[it] }
    println("{"+quote+"samples"+quote+":"+n+","+quote+"matrices"+quote+":"+nm+","+quote+"max_abs_errors"+quote+":{"+metrics+"},"+quote+"quantization_matrices"+quote+":"+nq+","+quote+"quantization_float_bits"+quote+":"+quote+"all equal"+quote+","+quote+"domain_and_buffer_checks"+quote+":"+quote+"passed"+quote+","+quote+"boundary"+quote+":"+quote+"Kotlin/Python scalar reference only; not native or Android execution"+quote+"}")
}
