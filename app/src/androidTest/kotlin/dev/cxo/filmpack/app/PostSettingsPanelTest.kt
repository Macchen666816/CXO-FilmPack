package dev.cxo.filmpack.app

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.Spinner
import dev.cxo.filmpack.app.post.EdgeDefectSettings
import dev.cxo.filmpack.app.post.EdgePosition
import dev.cxo.filmpack.app.post.PostEditingSettings
import dev.cxo.filmpack.app.post.PostSettingsPanel
import dev.cxo.filmpack.app.post.createPostSettingsPanel
import dev.cxo.filmpack.app.ui.CatalogGlassEnvironment
import dev.cxo.filmpack.app.ui.CatalogGlassSlider
import dev.cxo.filmpack.app.ui.EditorPalette
import dev.cxo.filmpack.core.PostProcessSettings

/** UI contracts independent of MainActivity's preview scheduler. */
class PostSettingsPanelTest : ForegroundEditorTest() {
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun find(v: View, tag: String): View? {
        if (v.tag == tag) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), tag)?.let { return it }
        return null
    }
    private class State(var settings: PostEditingSettings) {
        val changes = mutableListOf<Pair<PostEditingSettings, Boolean>>()
    }
    private fun withPanel(initial: PostEditingSettings = PostEditingSettings(), block: (PostSettingsPanel, State) -> Unit) {
        val host = activity
        val state = State(initial)
        lateinit var panel: PostSettingsPanel
        lateinit var scroll: ScrollView
        main {
            panel = createPostSettingsPanel(host, { state.settings }, CatalogGlassEnvironment()) { next, tracking ->
                state.settings = next; state.changes += next to tracking
            }
            scroll = ScrollView(host).apply {
                setBackgroundColor(EditorPalette.background)
                addView(panel, ViewGroup.LayoutParams(-1, -2))
            }
            host.addContentView(scroll, FrameLayout.LayoutParams(-1, -1))
        }
        instrumentation.waitForIdleSync()
        try { block(panel, state) } finally { main { (scroll.parent as? ViewGroup)?.removeView(scroll) } }
    }

    fun testAccordionReadsLatestStateAndPreservesOtherFields() = withPanel { panel, state ->
        main {
            assertEquals(View.GONE, find(panel, "post_light_content")!!.visibility)
            find(panel, "post_light")!!.performClick()
            assertEquals(View.VISIBLE, find(panel, "post_light_content")!!.visibility)
            (find(panel, "post_brightness") as CatalogGlassSlider).progress = 125
            assertEquals(25, state.settings.development.brightness)
            find(panel, "post_color")!!.performClick()
            assertEquals(View.GONE, find(panel, "post_light_content")!!.visibility)
            assertNull(find(panel, "post_brightness"))
            (find(panel, "post_saturation") as CatalogGlassSlider).progress = 140
            assertEquals(40, state.settings.development.saturation)
            assertEquals(25, state.settings.development.brightness)
            state.settings = state.settings.copy(development = state.settings.development.copy(contrast = 37),
                edge = EdgeDefectSettings("fuitelum1", 72, EdgePosition.LEFT, 51))
            find(panel, "post_light")!!.performClick()
            assertEquals(125, (find(panel, "post_brightness") as CatalogGlassSlider).progress)
            (find(panel, "post_exposure") as CatalogGlassSlider).progress = 400
            assertEquals(1f, state.settings.development.exposureEv)
            assertEquals(37, state.settings.development.contrast)
            assertEquals(40, state.settings.development.saturation)
            assertEquals(51L, state.settings.edge.seed)
            find(panel, "post_light")!!.performClick()
            assertEquals(View.GONE, find(panel, "post_light_content")!!.visibility)
            assertNull(find(panel, "post_exposure"))
            assertEquals(1f, state.settings.development.exposureEv)
        }
    }

    fun testGroupResetEdgeChoicesAndExternalRefresh() = withPanel(
        PostEditingSettings(PostProcessSettings(exposureEv = .7f, brightness = 20, contrast = 31, saturation = 40),
            EdgeDefectSettings("fuitelum1", 72, EdgePosition.LEFT, 51)),
    ) { panel, state ->
        main {
            find(panel, "post_light")!!.performClick()
            find(panel, "post_light_reset")!!.performClick()
            assertEquals(0f, state.settings.development.exposureEv)
            assertEquals(0, state.settings.development.brightness)
            assertEquals(31, state.settings.development.contrast)
            assertEquals(40, state.settings.development.saturation)
            assertEquals(51L, state.settings.edge.seed)
            assertEquals(300, (find(panel, "post_exposure") as CatalogGlassSlider).progress)
            find(panel, "post_edge")!!.performClick()
            (find(panel, "post_edge_source") as Spinner).setSelection(2)
            (find(panel, "post_edge_position") as Spinner).setSelection(3)
        }
        instrumentation.waitForIdleSync()
        main {
            assertTrue(state.settings.edge.id.isNotEmpty())
            assertEquals(EdgePosition.RIGHT, state.settings.edge.position)
            (find(panel, "post_edge_intensity") as CatalogGlassSlider).progress = 24
            assertEquals(24, state.settings.edge.intensity)
            assertEquals(31, state.settings.development.contrast)
            find(panel, "post_edge_reset")!!.performClick()
            assertEquals(EdgeDefectSettings(), state.settings.edge)
            assertEquals(31, state.settings.development.contrast)
            state.settings = PostEditingSettings(PostProcessSettings(brightness = -32))
            panel.refresh()
            find(panel, "post_light")!!.performClick()
            assertEquals(68, (find(panel, "post_brightness") as CatalogGlassSlider).progress)
            assertEquals(0, (find(panel, "post_edge_content") as ViewGroup).childCount)
        }
        instrumentation.waitForIdleSync()
        main { assertTrue("Detached spinner callbacks must not restore reset state", state.settings.edge.id.isEmpty()) }
    }

    fun testSliderDragPublishesTrackingThenFinalSnapshot() = withPanel { panel, state ->
        lateinit var slider: CatalogGlassSlider
        main { find(panel, "post_light")!!.performClick() }
        instrumentation.waitForIdleSync()
        val down = SystemClock.uptimeMillis()
        main {
            slider = find(panel, "post_exposure") as CatalogGlassSlider
            val event = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, slider.width / 2f, slider.height / 2f, 0)
            try { slider.dispatchTouchEvent(event) } finally { event.recycle() }
            // The wrapper keeps the tracking lifecycle even for fractional or
            // slow Compose movement; deterministic progress avoids pixel-density assumptions.
            slider.progress = 425
            assertTrue(state.changes.last().second)
            assertEquals(1.25f, state.settings.development.exposureEv)
            val cancel = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, slider.width / 2f, slider.height / 2f, 0)
            try { slider.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
        }
        instrumentation.waitForIdleSync()
        main {
            assertFalse("Ending drag must request precise preview", state.changes.last().second)
            assertEquals(state.settings, state.changes.last().first)
        }
    }
}
