package dev.cxo.filmpack.app

import android.test.InstrumentationTestCase
import dev.cxo.filmpack.core.FiniteFloatFma
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

@Suppress("DEPRECATION")
class ParallelFilmTransformTest : InstrumentationTestCase() {
    fun testWholeRowsAndFourPixelLanesMatchSerialAcrossShapes() {
        val apply: (ShortArray,FloatArray,Int,Int) -> Unit = { src,dst,width,x ->
            for (i in src.indices) dst[i] = (src[i].toInt() and 65535).toFloat() + ((x+i/3%width) and 3)*.125f
        }
        for (workers in listOf(1,2,4)) ParallelFilmTransform(apply,{ false },workers=workers).use { parallel ->
            for (width in listOf(1,3,4,5,17)) for (height in listOf(1,2,3,7,8,9)) for (x in listOf(0,1,3)) {
                val input = ShortArray(width*height*3) { (it*701).toShort() }
                val original = input.copyOf(); val expected = FloatArray(input.size); val actual = FloatArray(input.size)
                apply(input,expected,width,x); parallel.render(input,actual,width,x)
                assertTrue("workers=$workers width=$width height=$height x=$x",expected.contentEquals(actual))
                assertTrue(input.contentEquals(original))
            }
        }
    }

    fun testFailureAndCancellationDrainTasksBeforeReturnAndCanRecover() {
        val active = AtomicInteger()
        var shouldFail = true
        var cancel = false
        ParallelFilmTransform({ src,dst,_,_ ->
            active.incrementAndGet()
            try {
                if (shouldFail && src[0].toInt() == 0) throw IllegalStateException("expected failure")
                Thread.sleep(15)
                for (i in dst.indices) dst[i] = src[i].toFloat()
            } finally { active.decrementAndGet() }
        },{ cancel },workers=4).use { parallel ->
            val source = ShortArray(4*8*3) { it.toShort() }; val destination = FloatArray(source.size)
            try { parallel.render(source,destination,4,0); fail("failure must propagate") }
            catch (expected: IllegalStateException) { assertEquals("expected failure",expected.message) }
            assertEquals(0,active.get())
            shouldFail = false; cancel = true
            try { parallel.render(source,destination,4,0); fail("cancellation must propagate") }
            catch (_: CancellationException) { }
            assertEquals(0,active.get())
            cancel = false; parallel.render(source,destination,4,0)
            assertTrue(source.indices.all { destination[it] == source[it].toFloat() })
        }
    }

    fun testAllProductFilmsMatchSerialBitsAtFourIntensities() {
        val engine = PhotoProcessor(instrumentation.targetContext)
        for (film in engine.films) for (intensity in listOf(0,50,100,200)) {
            val apply = engine.transform(film,intensity)
            ParallelFilmTransform(apply,{ false },workers=4).use { parallel ->
                for (height in listOf(3,8,9)) {
                    val width = 5
                    val input = ShortArray(width*height*3) { (it*701).toShort() }
                    val expected = FloatArray(input.size); val actual = FloatArray(input.size)
                    apply(input,expected,width,3); parallel.render(input,actual,width,3)
                    assertTrue("${film.token}/$intensity/$height serial vs parallel",expected.indices.all {
                        expected[it].toRawBits() == actual[it].toRawBits()
                    })
                }
            }
        }
    }

    fun testAfterStageReceivesGlobalRowCoordinates() {
        ParallelFilmTransform({ src,dst,_,_ -> for (i in src.indices) dst[i] = src[i].toFloat() },
            { false },after = { dst,width,x,y ->
                for (i in dst.indices) dst[i] += (y+i/3/width)*.25f+x*.125f
            },workers=4).use { parallel ->
            val input = ShortArray(5*9*3) { it.toShort() }; val output = FloatArray(input.size)
            parallel.render(input,output,5,3,17)
            for (i in input.indices) assertEquals(input[i].toFloat()+(17+i/3/5)*.25f+3*.125f,output[i])
        }
    }

    fun testOwnerInterruptionJoinsWorkersBeforeUnwind() {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val owner = Thread {
            try {
                ParallelFilmTransform({ _,_,_,_ ->
                    started.countDown()
                    while (true) {
                        try { release.await(); break } catch (_: InterruptedException) { }
                    }
                },{ false },workers=4).use { it.render(ShortArray(96),FloatArray(96),4,0) }
            } catch (expected: Throwable) { failure.set(expected) }
            finally { finished.countDown() }
        }
        owner.start()
        try {
            assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS))
            owner.interrupt()
            assertFalse("owner must join interrupt-ignoring workers",finished.await(100,java.util.concurrent.TimeUnit.MILLISECONDS))
        } finally { release.countDown() }
        assertTrue(finished.await(5,java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(failure.get() is InterruptedException)
    }

    fun testFmaSubnormalBoundaryRetainsResidualFallback() {
        val a = Float.fromBits(0x1a000001); val b = Float.fromBits(0x19fffffe)
        val c = Float.fromBits(0x007fffff)
        assertEquals(0x007fffff,FiniteFloatFma.fma(a,b,c).toRawBits())
        assertEquals(0x807fffff.toInt(),FiniteFloatFma.fma(-a,b,-c).toRawBits())
    }
}
