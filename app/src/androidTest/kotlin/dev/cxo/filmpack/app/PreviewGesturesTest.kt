package dev.cxo.filmpack.app

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.cxo.filmpack.app.ui.ZoomablePreviewImageView
import kotlin.math.abs

/** Product gesture contracts, without RAW fixtures or photo-rendering work. */
@Suppress("DEPRECATION")
class PreviewGesturesTest : ForegroundEditorTest() {
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun withPhoto(block: (ZoomablePreviewImageView, MutableList<Boolean>) -> Unit) {
        val host = activity
        val comparisons = mutableListOf<Boolean>()
        val bitmap = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888).apply {
            density = Bitmap.DENSITY_NONE
            eraseColor(0xFF778899.toInt())
        }
        lateinit var photo: ZoomablePreviewImageView
        main {
            photo = ZoomablePreviewImageView(host).apply {
                onCompareChanged = { comparisons.add(it) }
                setImageBitmap(bitmap)
            }
            host.addContentView(photo, FrameLayout.LayoutParams(600, 600))
        }
        instrumentation.waitForIdleSync()
        try {
            assertEquals(600, photo.width)
            block(photo, comparisons)
        } finally {
            main {
                photo.cancelComparison()
                (photo.parent as? ViewGroup)?.removeView(photo)
                photo.setImageDrawable(null)
                bitmap.recycle()
            }
        }
    }

    private fun touch(photo: ZoomablePreviewImageView, down: Long, action: Int,
                      vararg positions: Pair<Float, Float>) {
        val properties = Array(positions.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coordinates = Array(positions.size) { index ->
            MotionEvent.PointerCoords().apply {
                x = positions[index].first; y = positions[index].second
                pressure = 1f; size = 1f
            }
        }
        main {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                positions.size, properties, coordinates, 0, 0, 1f, 1f,
                0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(photo.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
    }

    private fun values(photo: ZoomablePreviewImageView): FloatArray = FloatArray(9).also { values ->
        main { photo.imageMatrix.getValues(values) }
    }

    private data class PinchStream(val down: Long, val left: Pair<Float, Float>, val right: Pair<Float, Float>)

    private fun pinch(photo: ZoomablePreviewImageView, requestedFinalSpan: Float = 480f): PinchStream =
        pinchBetween(photo, 160f, requestedFinalSpan).also {
            assertTrue("Pinch did not magnify: zoom=${photo.zoomScale}", photo.zoomScale > 1.2f)
        }

    private fun pinchBetween(photo: ZoomablePreviewImageView, initialSpan: Float, finalSpan: Float): PinchStream {
        val centerX = photo.width * 0.5f
        val centerY = photo.height * 0.5f
        val down = SystemClock.uptimeMillis()
        touch(photo, down, MotionEvent.ACTION_DOWN, (centerX - initialSpan / 2) to centerY)
        touch(photo, down, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            (centerX - initialSpan / 2) to centerY, (centerX + initialSpan / 2) to centerY)
        moveSpan(photo, down, initialSpan, finalSpan)
        return PinchStream(down, (centerX - finalSpan / 2) to centerY, (centerX + finalSpan / 2) to centerY)
    }

    private fun moveSpan(photo: ZoomablePreviewImageView, down: Long, initialSpan: Float, finalSpan: Float) {
        val centerX = photo.width * 0.5f
        val centerY = photo.height * 0.5f
        for (frame in 1..12) {
            SystemClock.sleep(16)
            val span = initialSpan + (finalSpan - initialSpan) * frame / 12f
            touch(photo, down, MotionEvent.ACTION_MOVE,
                (centerX - span / 2) to centerY, (centerX + span / 2) to centerY)
        }
    }

    private fun assertFits(photo: ZoomablePreviewImageView) {
        assertEquals("Photo did not return to fit", 1f, photo.zoomScale, 0.001f)
        val rect = RectF(0f, 0f, 800f, 400f)
        main { photo.imageMatrix.mapRect(rect) }
        assertEquals(0f, rect.left, 0.001f)
        assertEquals(150f, rect.top, 0.001f)
        assertEquals(600f, rect.width(), 0.001f)
        assertEquals(300f, rect.height(), 0.001f)
    }

    fun testResolutionSwapsPreserveZoomAndViewportCenter() = withPhoto { photo, _ ->
        val stream=pinch(photo)
        touch(photo,stream.down,MotionEvent.ACTION_CANCEL,stream.left,stream.right)
        val before=values(photo)
        val originalZoom=photo.zoomScale
        val smaller=Bitmap.createBitmap(400,200,Bitmap.Config.ARGB_8888).apply {density=Bitmap.DENSITY_NONE}
        try {
            main {photo.setImageBitmap(smaller)}
            assertEquals(originalZoom,photo.zoomScale,.001f)
            val after=values(photo)
            assertEquals(before[Matrix.MSCALE_X]*2,after[Matrix.MSCALE_X],.001f)
            assertEquals(before[Matrix.MTRANS_X],after[Matrix.MTRANS_X],.001f)
            assertEquals(before[Matrix.MTRANS_Y],after[Matrix.MTRANS_Y],.001f)
        } finally {main {photo.setImageDrawable(null)};smaller.recycle()}
    }

    fun testInwardPinchReachesFitWithinSameAndNewPointerStreams() = withPhoto { photo, comparisons ->
        // All coordinates stay inside the 600px viewport. Shrinking continues
        // through the platform's minimum-span range, which used to end scaling.
        val outward = pinch(photo)
        moveSpan(photo, outward.down, 480f, 60f)
        touch(photo, outward.down, MotionEvent.ACTION_CANCEL, 270f to 300f, 330f to 300f)
        assertFits(photo)

        val zoomed = pinch(photo)
        touch(photo, zoomed.down, MotionEvent.ACTION_CANCEL, zoomed.left, zoomed.right)
        assertTrue(photo.zoomScale > 1.2f)
        val inward = pinchBetween(photo, 480f, 60f)
        touch(photo, inward.down, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), inward.left, inward.right)
        touch(photo, inward.down, MotionEvent.ACTION_UP, inward.left)
        assertFits(photo)
        SystemClock.sleep(550)
        instrumentation.waitForIdleSync()
        assertTrue("Pinch scheduled original comparison", comparisons.isEmpty())
    }

    fun testDoubleTapRestoresFitWithoutComparingAndNextHoldStillWorks() = withPhoto { photo, comparisons ->
        val zoomed = pinch(photo)
        touch(photo, zoomed.down, MotionEvent.ACTION_CANCEL, zoomed.left, zoomed.right)
        val first = SystemClock.uptimeMillis()
        touch(photo, first, MotionEvent.ACTION_DOWN, 300f to 300f)
        SystemClock.sleep(45)
        touch(photo, first, MotionEvent.ACTION_UP, 300f to 300f)
        assertTrue("A single tap reset the photo", photo.zoomScale > 1.2f)
        SystemClock.sleep(80)
        val second = SystemClock.uptimeMillis()
        touch(photo, second, MotionEvent.ACTION_DOWN, 300f to 300f)
        SystemClock.sleep(45)
        touch(photo, second, MotionEvent.ACTION_UP, 300f to 300f)
        assertFits(photo)
        SystemClock.sleep(550)
        instrumentation.waitForIdleSync()
        assertTrue("Double tap scheduled original comparison", comparisons.isEmpty())

        val held = SystemClock.uptimeMillis()
        touch(photo, held, MotionEvent.ACTION_DOWN, 300f to 300f)
        SystemClock.sleep(550)
        instrumentation.waitForIdleSync()
        assertEquals(listOf(true), comparisons)
        touch(photo, held, MotionEvent.ACTION_UP, 300f to 300f)
        assertEquals(listOf(true, false), comparisons)
    }

    fun testShortTapAndStationaryHalfSecondHold() = withPhoto { photo, comparisons ->
        val down = SystemClock.uptimeMillis()
        touch(photo, down, MotionEvent.ACTION_DOWN, 300f to 300f)
        SystemClock.sleep(100)
        touch(photo, down, MotionEvent.ACTION_UP, 300f to 300f)
        SystemClock.sleep(550)
        instrumentation.waitForIdleSync()
        assertTrue("Short tap scheduled a comparison", comparisons.isEmpty())

        val held = SystemClock.uptimeMillis()
        touch(photo, held, MotionEvent.ACTION_DOWN, 300f to 300f)
        SystemClock.sleep(350)
        instrumentation.waitForIdleSync()
        assertTrue("Comparison began before half a second", comparisons.isEmpty())
        SystemClock.sleep(220)
        instrumentation.waitForIdleSync()
        assertEquals(listOf(true), comparisons)
        touch(photo, held, MotionEvent.ACTION_UP, 300f to 300f)
        assertEquals(listOf(true, false), comparisons)
    }

    fun testMovementSecondFingerAndCancelNeverLeaveComparisonPending() = withPhoto { photo, comparisons ->
        var down = SystemClock.uptimeMillis()
        touch(photo, down, MotionEvent.ACTION_DOWN, 300f to 300f)
        touch(photo, down, MotionEvent.ACTION_MOVE, 450f to 300f)
        SystemClock.sleep(550)
        instrumentation.waitForIdleSync()
        assertTrue(comparisons.isEmpty())
        touch(photo, down, MotionEvent.ACTION_UP, 450f to 300f)

        down = SystemClock.uptimeMillis()
        touch(photo, down, MotionEvent.ACTION_DOWN, 250f to 300f)
        touch(photo, down, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            250f to 300f, 350f to 300f)
        SystemClock.sleep(550)
        instrumentation.waitForIdleSync()
        assertTrue("Second finger did not cancel the hold", comparisons.isEmpty())
        touch(photo, down, MotionEvent.ACTION_CANCEL, 250f to 300f, 350f to 300f)

        down = SystemClock.uptimeMillis()
        touch(photo, down, MotionEvent.ACTION_DOWN, 300f to 300f)
        SystemClock.sleep(550)
        instrumentation.waitForIdleSync()
        assertEquals(listOf(true), comparisons)
        touch(photo, down, MotionEvent.ACTION_CANCEL, 300f to 300f)
        assertEquals(listOf(true, false), comparisons)
    }

    fun testPinchPanBoundsSameSizeComparisonAndNewPhotoReset() = withPhoto { photo, comparisons ->
        val stream = pinch(photo)
        val down = stream.down
        assertTrue(photo.zoomScale <= 8f)
        touch(photo, down, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            stream.left, stream.right)
        val previous = values(photo)
        touch(photo, down, MotionEvent.ACTION_MOVE, (stream.left.first + 100f) to stream.left.second)
        val moved = values(photo)
        assertTrue("Magnified image did not pan", abs(previous[Matrix.MTRANS_X] - moved[Matrix.MTRANS_X]) > 1f)
        touch(photo, down, MotionEvent.ACTION_MOVE, 9000f to 9000f)
        val rect = RectF(0f, 0f, 800f, 400f)
        main { photo.imageMatrix.mapRect(rect) }
        assertTrue("Pan exposed empty space left of the image", rect.left <= 0.1f)
        assertTrue("Pan exposed empty space right of the image", rect.right >= photo.width - 0.1f)
        touch(photo, down, MotionEvent.ACTION_UP, 9000f to 9000f)
        assertTrue("Pinch unexpectedly compared originals", comparisons.isEmpty())

        val sameSize = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888).apply { density = Bitmap.DENSITY_NONE }
        val newSize = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888).apply { density = Bitmap.DENSITY_NONE }
        try {
            val before = values(photo)
            val zoom = photo.zoomScale
            main { photo.setImageBitmap(sameSize) }
            val after = values(photo)
            for (i in 0..8) assertEquals("Compare bitmap changed transform[$i]", before[i], after[i], 0.001f)
            assertEquals(zoom, photo.zoomScale, 0.001f)
            main { photo.setImageBitmap(newSize) }
            assertEquals("A different-sized photo retained zoom", 1f, photo.zoomScale, 0.001f)
            val newRect = RectF(0f, 0f, 400f, 800f)
            main { photo.imageMatrix.mapRect(newRect) }
            assertEquals(600f, newRect.height(), 0.001f)
            assertEquals(150f, newRect.left, 0.001f)
        } finally {
            main { photo.setImageDrawable(null); sameSize.recycle(); newSize.recycle() }
        }
    }

    fun testViewportResizePreservesFiniteZoomAndResetFitsAgain() = withPhoto { photo, _ ->
        val stream = pinch(photo, requestedFinalSpan = 1600f)
        touch(photo, stream.down, MotionEvent.ACTION_CANCEL, stream.left, stream.right)
        val oldZoom = photo.zoomScale
        assertTrue("Resize contract must start from a genuinely magnified image", oldZoom > 1.2f)
        main { photo.layoutParams = FrameLayout.LayoutParams(600, 400) }
        instrumentation.waitForIdleSync()
        assertEquals(400, photo.height)
        assertEquals(oldZoom, photo.zoomScale, 0.001f)
        assertTrue(values(photo).all { it.isFinite() })
        main { photo.resetZoom() }
        assertEquals(1f, photo.zoomScale, 0.001f)
        val rect = RectF(0f, 0f, 800f, 400f)
        main { photo.imageMatrix.mapRect(rect) }
        assertEquals(0f, rect.left, 0.001f)
        assertEquals(50f, rect.top, 0.001f)
        assertEquals(600f, rect.width(), 0.001f)
        assertEquals(300f, rect.height(), 0.001f)
    }
}
