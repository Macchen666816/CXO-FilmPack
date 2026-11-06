package dev.cxo.filmpack.core

import kotlin.math.*
import java.util.Random

/** Properties and independent analytic checks of CXO edits; no native parity claim. */
fun main() {
    val random=Random(20261105L)
    val source=IntArray(12000) {random.nextInt()}
    val unchanged=source.copyOf()
    PostProcessTransform(PostProcessSettings()).applyArgbInPlace(unchanged)
    check(source.contentEquals(unchanged)) {"Default settings must be bit exact"}
    fun one(settings:PostProcessSettings,pixel:Int):Int = intArrayOf(pixel).also {PostProcessTransform(settings).applyArgbInPlace(it)}[0]
    fun red(pixel:Int)=pixel ushr 16 and 255
    fun green(pixel:Int)=pixel ushr 8 and 255
    fun blue(pixel:Int)=pixel and 255
    fun referenceExposure(code:Int,ev:Double):Int {
        val srgb=code/255.0
        val linear=if(srgb<=.04045) srgb/12.92 else ((srgb+.055)/1.055).pow(2.4)
        val value=(linear*2.0.pow(ev)).coerceIn(0.0,1.0)
        return ((if(value<=.0031308) value*12.92 else 1.055*value.pow(1.0/2.4)-.055)*255.0).roundToInt()
    }
    for(ev in listOf(-3f,-1f,.5f,1f,3f)) {
        val transform=PostProcessTransform(PostProcessSettings(exposureEv=ev))
        val grey=IntArray(256) {0xff000000.toInt() or (it*0x010101)}
        transform.applyArgbInPlace(grey)
        for(code in 0..255) {
            // Baked-LUT interpolation, especially at highlight clipping, is
            // deliberately bounded rather than called a direct formula match.
            check(abs(red(grey[code])-referenceExposure(code,ev.toDouble()))<=5)
            check(red(grey[code])==green(grey[code]) && green(grey[code])==blue(grey[code]))
            if(code>0) check(red(grey[code])>=red(grey[code-1]))
        }
    }
    check(one(PostProcessSettings(hue=120),0xffff0000.toInt())==0xff00ff00.toInt())
    check(one(PostProcessSettings(hue=-120),0xffff0000.toInt())==0xff0000ff.toInt())
    val grey=one(PostProcessSettings(saturation=-100),0xffff0000.toInt())
    check(abs(red(grey)-128)<=1 && red(grey)==green(grey) && green(grey)==blue(grey))
    val warm=one(PostProcessSettings(temperature=100),0xff808080.toInt())
    check(red(warm)>blue(warm))
    check(red(one(PostProcessSettings(brightness=80),0xff808080.toInt()))>128)
    check(red(one(PostProcessSettings(brightness=-80),0xff808080.toInt()))<128)
    val positiveContrast=PostProcessSettings(contrast=80)
    check(red(one(positiveContrast,0xff404040.toInt()))<64)
    check(red(one(positiveContrast,0xffc0c0c0.toInt()))>192)
    for(settings in listOf(PostProcessSettings(contrast=100),PostProcessSettings(contrast=-100)) +
        ToneCurvePreset.entries.map { PostProcessSettings(curve=it) }) {
        val ramp=IntArray(256) {0xff000000.toInt() or it*0x010101}
        PostProcessTransform(settings).applyArgbInPlace(ramp)
        for(i in 1..255) check(red(ramp[i])>=red(ramp[i-1])) {"Contrast/curve reversed the neutral ramp"}
    }
    val variants=listOf(
        PostProcessSettings(exposureEv=3f,brightness=100,contrast=100,temperature=-100,tint=100,hue=180,saturation=100,vibrance=100,highlights=100,shadows=-100,whites=-100,blacks=100,curve=ToneCurvePreset.MATTE),
        PostProcessSettings(exposureEv=-3f,brightness=-100,contrast=-100,temperature=100,tint=-100,hue=-180,saturation=-100,vibrance=-100,highlights=-100,shadows=100,whites=100,blacks=-100,curve=ToneCurvePreset.STRONG_CONTRAST),
        PostProcessSettings(curve=ToneCurvePreset.SOFT_CONTRAST),
    )
    for(settings in variants) {
        val transform=PostProcessTransform(settings);val whole=source.copyOf()
        transform.applyArgbInPlace(whole)
        for(i in source.indices) check((whole[i] ushr 24)==(source[i] ushr 24))
        val split=source.copyOfRange(0,147);transform.applyArgbInPlace(split)
        check(split.contentEquals(whole.copyOfRange(0,147))) {"Row/chunk processing changed the result"}
        val partial=source.copyOf();transform.applyArgbInPlace(partial,147)
        check(partial.copyOfRange(147,partial.size).contentEquals(source.copyOfRange(147,source.size)))
        check(partial.copyOfRange(0,147).contentEquals(split))
    }
    check(runCatching {PostProcessSettings(exposureEv=Float.NaN)}.isFailure)
    check(runCatching {PostProcessSettings(saturation=101)}.isFailure)
    check(runCatching {PostProcessTransform(PostProcessSettings()).applyArgbInPlace(IntArray(1),2)}.isFailure)
    println("CXO postprocess verification passed: identity, independent exposure, hue, saturation, direction, alpha, chunk consistency, bounds")
}
