package dev.cxo.filmpack.app

import android.app.AlertDialog
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import dev.cxo.filmpack.app.ui.*
import java.io.File

/** Real pointer events reach the upstream controls, including their drag handlers. */
class CatalogInteractionTest : ForegroundEditorTest() {
    private fun find(v: View, tag: String): View? {
        if (v.tag == tag) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), tag)?.let { return it }
        return null
    }
    private fun waitFor(label: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 120_000
        while (SystemClock.uptimeMillis() < end) {
            instrumentation.waitForIdleSync()
            var ok = false
            instrumentation.runOnMainSync { ok = condition() }
            if (ok) return
            SystemClock.sleep(50)
        }
        fail("Timeout: $label")
    }
    private fun event(view: View, down: Long, action: Int, x: Float, y: Float) {
        instrumentation.runOnMainSync {
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).let {
                try { view.dispatchTouchEvent(it) } finally { it.recycle() }
            }
        }
    }
    private fun multiEvent(view:View,down:Long,action:Int,vararg points:Pair<Float,Float>) {
        val properties=Array(points.size) {index->MotionEvent.PointerProperties().apply {id=index;toolType=MotionEvent.TOOL_TYPE_FINGER}}
        val coordinates=Array(points.size) {index->MotionEvent.PointerCoords().apply {
            x=points[index].first;y=points[index].second;pressure=1f;size=1f
        }}
        instrumentation.runOnMainSync {
            val motion=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,points.size,properties,coordinates,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
            try {view.dispatchTouchEvent(motion)} finally {motion.recycle()}
        }
    }
    private fun inject(view:View,down:Long,action:Int,x:Float,y:Float) {
        val location=IntArray(2);instrumentation.runOnMainSync {view.getLocationOnScreen(location)}
        val motion=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,location[0]+x,location[1]+y,0)
        try {instrumentation.uiAutomation.injectInputEvent(motion,true)} finally {motion.recycle()}
    }
    private fun screenshot(name: String, settleMillis: Long = 350) {
        instrumentation.waitForIdleSync()
        if (settleMillis > 0) SystemClock.sleep(settleMillis)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
        try {
            File(instrumentation.targetContext.cacheDir, "ui-components-$name.png").outputStream()
                .use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
    private fun dialog(a: MainActivity, field: String) = MainActivity::class.java.getDeclaredField(field).let {
        it.isAccessible = true; it.get(a) as? AlertDialog
    }
    private fun <T: View> type(root: View, klass: Class<T>): T? {
        if (klass.isInstance(root)) return klass.cast(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) type(root.getChildAt(i),klass)?.let { return it }
        return null
    }
    @Suppress("UNCHECKED_CAST")
    private fun films(a: MainActivity) = MainActivity::class.java.getDeclaredField("films").let {
        it.isAccessible = true; it.get(a) as List<FilmChoice>
    }
    private fun thumbnailCount(a: MainActivity) = MainActivity::class.java.getDeclaredField("thumbnailCache").let {
        it.isAccessible = true; (it.get(a) as Map<*,*>).size
    }
    fun testOriginalControlsAndAllFilmCarouselMenuLinkage() {
        val a = activity
        val root = a.window.decorView
        waitFor("initialized") { find(root,"choose_film")?.isEnabled == true }
        instrumentation.runOnMainSync { a.loadPhoto(Uri.parse("content://dev.cxo.filmpack.app.test.photos/photo-mosaic.png")) }
        waitFor("render") { find(root,"export_photo")?.isEnabled == true }
        SystemClock.sleep(300)
        val photo = find(root,"photo_preview") as ZoomablePreviewImageView
        assertNotNull(photo.drawable as? BitmapDrawable)
        val drawer=find(root,"editor_drawer")!!
        val handle=find(root,"editor_drawer_handle")!!
        assertNotNull("Status must belong to the operation panel",find(drawer,"editor_panel_status"))
        assertEquals("Panel must meet both screen edges",root.width,drawer.width)
        assertEquals("Photo preview should fill the workbench width",root.width,photo.width)
        instrumentation.runOnMainSync {handle.performClick()}
        waitFor("operation drawer expands") {drawer.height>dp(300) && find(root,"editor_drawer_scroll")?.visibility==View.VISIBLE}
        val canvas=find(root,"photo_canvas")!!
        // Height crosses the threshold during the 220 ms animation. Verify the
        // final viewport after the animator and its requested layout finish.
        waitFor("drawer animation and preview layout settle") {
            val animator=MainActivity::class.java.getDeclaredField("drawerAnimator").let {
                it.isAccessible=true;it.get(a) as? android.animation.ValueAnimator
            }
            animator?.isRunning != true && !canvas.isLayoutRequested && !drawer.isLayoutRequested
        }
        assertTrue("Expanded panel must not cover the preview viewport",canvas.bottom<=drawer.top+1)
        screenshot("drawer-expanded")
        assertNull(find(root,"compare_photo"))
        screenshot("editor")

        val favorite = find(root,"favorite_film") as CatalogGlassButton
        val before = favorite.text.toString()
        val buttonDown = SystemClock.uptimeMillis()
        event(favorite,buttonDown,MotionEvent.ACTION_DOWN,favorite.width/2f,favorite.height/2f)
        SystemClock.sleep(180)
        screenshot("button-pressed",0)
        event(favorite,buttonDown,MotionEvent.ACTION_UP,favorite.width/2f,favorite.height/2f)
        waitFor("button click") { favorite.text.toString() != before }
        instrumentation.runOnMainSync { favorite.performClick() }

        val slider = find(root,"intensity_slider") as CatalogGlassSlider
        val initial = slider.progress
        val start = slider.width/2f
        val finish = slider.width*.76f
        val sliderDown = SystemClock.uptimeMillis()
        event(slider,sliderDown,MotionEvent.ACTION_DOWN,start,slider.height/2f)
        for (step in 1..8) {
            event(slider,sliderDown,MotionEvent.ACTION_MOVE,start+(finish-start)*step/8,slider.height/2f)
            SystemClock.sleep(24)
        }
        SystemClock.sleep(120)
        screenshot("slider-dragging",0)
        event(slider,sliderDown,MotionEvent.ACTION_UP,finish,slider.height/2f)
        waitFor("slider render") { slider.progress > initial+10 && find(root,"export_photo")?.isEnabled == true }

        fun sliderEndpoint(value: Int, name: String) {
            val gutter=36f*slider.resources.displayMetrics.density
            val from=gutter+(slider.width-2*gutter)*slider.progress/slider.max
            val to=if(value==0)gutter else slider.width-gutter
            val down=SystemClock.uptimeMillis()
            event(slider,down,MotionEvent.ACTION_DOWN,from,slider.height/2f)
            for(step in 1..10) {
                event(slider,down,MotionEvent.ACTION_MOVE,from+(to-from)*step/10,slider.height/2f)
                SystemClock.sleep(20)
            }
            SystemClock.sleep(120)
            screenshot(name,0)
            event(slider,down,MotionEvent.ACTION_UP,to,slider.height/2f)
            waitFor("slider endpoint $value") {slider.progress==value && find(root,"export_photo")?.isEnabled==true}
        }
        sliderEndpoint(200,"slider-max-pressed")
        sliderEndpoint(0,"slider-min-pressed")
        instrumentation.runOnMainSync { find(root,"reset_intensity")!!.performClick() }
        waitFor("slider reset") {slider.progress==100 && find(root,"export_photo")?.isEnabled==true}

        instrumentation.runOnMainSync { find(root,"grain_settings")!!.performClick() }
        waitFor("grain sheet") { dialog(a,"grainDialog")?.isShowing == true }
        val grain = dialog(a,"grainDialog")!!
        val strength=find(grain.window!!.decorView,"grain_intensity") as CatalogGlassSlider
        val strengthStart=strength.progress
        val strengthTarget=if(strengthStart<100).95f else .05f
        dragSliderWithDrift(strength,strengthTarget,dp(14).toFloat())
        assertTrue("A small vertical drift stole the horizontal grain strength drag: $strengthStart -> ${strength.progress}, ${strength.width}x${strength.height}",kotlin.math.abs(strength.progress-strengthStart)>20)
        val format=find(grain.window!!.decorView,"grain_format") as android.widget.Spinner
        instrumentation.runOnMainSync {format.setSelection(3)}
        instrumentation.waitForIdleSync()
        val manual=find(grain.window!!.decorView,"grain_manual_size") as CatalogGlassSlider
        instrumentation.runOnMainSync {
            manual.requestRectangleOnScreen(android.graphics.Rect(0,0,manual.width,manual.height),true)
        }
        waitFor("manual slider is fully within the scroll viewport") {
            val visible=android.graphics.Rect()
            manual.isEnabled && manual.getGlobalVisibleRect(visible) && visible.height()>=manual.height-1
        }
        val manualStart=manual.progress
        val manualTarget=if(manualStart<5).95f else .05f
        dragSliderWithDrift(manual,manualTarget,dp(14).toFloat(),steps=160)
        assertTrue("Manual grain size slider did not track a horizontal drag: $manualStart -> ${manual.progress}, enabled=${manual.isEnabled}, size=${manual.width}x${manual.height}",kotlin.math.abs(manual.progress-manualStart)>2)
        val grainScroll=find(grain.window!!.decorView,"grain_settings_scroll") as android.widget.ScrollView
        instrumentation.runOnMainSync { grainScroll.scrollTo(0,0) }
        instrumentation.waitForIdleSync()
        val strengthAfterHorizontal=strength.progress
        val trackGutter=dp(36).toFloat();val thumbX=trackGutter+(strength.width-2*trackGutter)*strength.progress/strength.max
        val verticalDown=SystemClock.uptimeMillis();val centerY=strength.height/2f
        inject(strength,verticalDown,MotionEvent.ACTION_DOWN,thumbX,centerY)
        for(step in 1..10) {
            inject(strength,verticalDown,MotionEvent.ACTION_MOVE,thumbX,centerY-dp(16).toFloat()*step)
            SystemClock.sleep(20)
        }
        inject(strength,verticalDown,MotionEvent.ACTION_UP,thumbX,centerY-dp(160).toFloat())
        SystemClock.sleep(600)
        assertTrue("Vertical gesture should still scroll the grain settings, y=${grainScroll.scrollY}, slider=${strength.progress}",grainScroll.scrollY>0)
        assertEquals("Vertical page scroll must not change grain intensity",strengthAfterHorizontal,strength.progress)
        instrumentation.runOnMainSync {grainScroll.scrollTo(0,0)}
        val toggle = find(grain.window!!.decorView,"grain_enabled") as CatalogGlassToggle
        waitFor("grain toggle and pinned actions fully visible") {
            listOf(toggle,find(grain.window!!.decorView,"grain_apply")!!,find(grain.window!!.decorView,"grain_cancel")!!).all { view ->
                val visible=android.graphics.Rect()
                view.getGlobalVisibleRect(visible) && visible.height()>=view.height-1
            }
        }
        val selected = toggle.isChecked
        val toggleDown = SystemClock.uptimeMillis()
        // Hit the actual upstream thumb, not an edge of the 64dp track.
        val density=toggle.resources.displayMetrics.density
        val toggleX=toggle.width-(if(selected)46f else 66f)*density
        event(toggle,toggleDown,MotionEvent.ACTION_DOWN,toggleX,toggle.height/2f)
        SystemClock.sleep(160)
        screenshot("toggle-pressed",0)
        event(toggle,toggleDown,MotionEvent.ACTION_UP,toggleX,toggle.height/2f)
        waitFor("toggle select") { toggle.isChecked != selected }
        screenshot("grain-sheet")
        instrumentation.runOnMainSync { grain.dismiss() }

        // The former duplicate picker and bottom tabs are deliberately absent.
        assertNull(find(root,"browse_films"))
        assertNull(find(root,"editor_bottom_tabs"))
        val chooser = find(root,"choose_film") as CatalogGlassButton
        val wideDown = SystemClock.uptimeMillis()
        event(chooser,wideDown,MotionEvent.ACTION_DOWN,chooser.width/2f,chooser.height/2f)
        for (step in 1..5) {
            event(chooser,wideDown,MotionEvent.ACTION_MOVE,chooser.width*(.5f+.3f*step/5),chooser.height/2f)
            SystemClock.sleep(24)
        }
        SystemClock.sleep(180)
        screenshot("wide-button-dragging",0)
        event(chooser,wideDown,MotionEvent.ACTION_CANCEL,chooser.width*.8f,chooser.height/2f)
        assertNull("Cancelled button drag opened the film picker",dialog(a,"filmDialog"))
        SystemClock.sleep(350)

        val stock = films(a)
        val strip = find(root,"film_strip_items") as LinearLayout
        val scroll = find(root,"film_strip") as HorizontalScrollView
        assertEquals(153, stock.size)
        assertEquals("Carousel must contain every film",stock.size,strip.childCount)
        val originalOrder = (0 until strip.childCount).map { strip.getChildAt(it).tag }
        assertEquals(stock.map { "film_card_${it.token}" }, originalOrder)
        waitFor("initial visible thumbnail batch") { thumbnailCount(a) >= 3 }
        assertTrue("Thumbnail generation eagerly processed the full catalog",thumbnailCount(a)<stock.size/2)

        // Choose the final item through the traditional searchable text list.
        // It starts far outside the carousel's initial viewport.
        val far = stock.last()
        instrumentation.runOnMainSync { chooser.performClick() }
        waitFor("full film menu") { dialog(a,"filmDialog")?.isShowing == true }
        val film = dialog(a,"filmDialog")!!
        assertNotNull("Film picker title belongs in the sheet content",find(film.window!!.decorView,"film_picker_title"))
        waitFor("film picker close action stays above navigation bar") {
            val close=find(film.window!!.decorView,"film_close") ?: return@waitFor false
            val visible=android.graphics.Rect();close.getGlobalVisibleRect(visible) && visible.height()>=close.height-1
        }
        lateinit var list: ListView
        instrumentation.runOnMainSync {
            list=type(film.window!!.decorView,ListView::class.java)!!
            assertEquals(stock.size,list.adapter.count)
            assertEquals(far.token,(list.adapter.getItem(stock.lastIndex) as FilmChoice).token)
            list.setSelection(stock.lastIndex)
        }
        waitFor("last text film reachable") { list.lastVisiblePosition==stock.lastIndex && list.childCount>0 }
        instrumentation.runOnMainSync {
            val position=stock.lastIndex
            val child=list.getChildAt(position-list.firstVisiblePosition)
            assertNotNull("Final text film not on screen",child)
            list.performItemClick(child,position,list.adapter.getItemId(position))
        }
        val farCard=find(root,"film_card_${far.token}") as FilmCard
        waitFor("menu selection scrolls carousel and renders") {
            chooser.text.contains(far.name) && farCard.isSelected && find(root,"export_photo")?.isEnabled==true &&
                scroll.scrollX>scroll.width && farCard.left>=scroll.scrollX && farCard.right<=scroll.scrollX+scroll.width
        }
        waitFor("distant selected film gets an actual thumbnail") { farCard.thumbnail.drawable is BitmapDrawable }
        screenshot("carousel-last-selected")
        assertTrue("A distant selection rendered every thumbnail",thumbnailCount(a)<stock.size/2)

        // Favorites may decorate selection, but must not reorder all-film cards
        // or jump the strip away from the chosen stock.
        val beforeFavoriteScroll=scroll.scrollX
        instrumentation.runOnMainSync { favorite.performClick() }
        waitFor("favorite state updates without reorder") {
            originalOrder==(0 until strip.childCount).map {strip.getChildAt(it).tag} &&
                kotlin.math.abs(scroll.scrollX-beforeFavoriteScroll)<=2 && farCard.isSelected
        }
        instrumentation.runOnMainSync { favorite.performClick() }

        // Select a genuinely visible adjacent thumbnail; reopening/searching the
        // text menu must mark this same stock selected with the filled dot.
        val neighbor = stock[stock.lastIndex-1]
        val neighborCard=find(root,"film_card_${neighbor.token}") as FilmCard
        waitFor("adjacent card visible") { neighborCard.left>=scroll.scrollX && neighborCard.right<=scroll.scrollX+scroll.width }
        val cardDown=SystemClock.uptimeMillis()
        event(neighborCard,cardDown,MotionEvent.ACTION_DOWN,neighborCard.width/2f,neighborCard.height/2f)
        SystemClock.sleep(55)
        event(neighborCard,cardDown,MotionEvent.ACTION_UP,neighborCard.width/2f,neighborCard.height/2f)
        waitFor("thumbnail updates selection") { neighborCard.isSelected && chooser.text.contains(neighbor.name) && find(root,"export_photo")?.isEnabled==true }
        instrumentation.runOnMainSync { chooser.performClick() }
        waitFor("linked text menu") { dialog(a,"filmDialog")?.isShowing == true }
        val linked = dialog(a,"filmDialog")!!
        instrumentation.runOnMainSync { type(linked.window!!.decorView,EditText::class.java)!!.setText(neighbor.name) }
        waitFor("search finds chosen thumbnail") {
            val linkedList=type(linked.window!!.decorView,ListView::class.java)!!
            linkedList.adapter.count==1 && (linkedList.adapter.getItem(0) as FilmChoice).token==neighbor.token && linkedList.childCount>0 &&
                (find(linkedList.getChildAt(0),"film_row_label") as TextView).text.startsWith("●")
        }
        screenshot("carousel-linked-search")
        instrumentation.runOnMainSync { linked.dismiss() }
    }

    fun testFilmInfoPreservesSelectionAndSearch() {
        val a=activity;val root=a.window.decorView
        waitFor("initialized") {find(root,"choose_film")?.isEnabled==true}
        val before=(find(root,"choose_film") as CatalogGlassButton).text.toString()
        instrumentation.runOnMainSync {find(root,"choose_film")!!.performClick()}
        waitFor("picker") {dialog(a,"filmDialog")?.isShowing==true}
        val picker=dialog(a,"filmDialog")!!;val decor=picker.window!!.decorView
        val search=find(decor,"film_search") as EditText
        instrumentation.runOnMainSync {search.setText("Elite Color 400")}
        val list=find(decor,"film_results") as ListView
        waitFor("filtered row") {list.adapter.count==1 && list.childCount==1}
        val film=list.adapter.getItem(0) as FilmChoice
        val info=find(list.getChildAt(0),"film_info_${film.token}")!!
        val down=SystemClock.uptimeMillis()
        inject(info,down,MotionEvent.ACTION_DOWN,info.width/2f,info.height/2f)
        inject(info,down,MotionEvent.ACTION_UP,info.width/2f,info.height/2f)
        waitFor("details") {dialog(a,"filmInfoDialog")?.isShowing==true}
        val detail=dialog(a,"filmInfoDialog")!!
        assertEquals(film.name,(find(detail.window!!.decorView,"film_info_title") as TextView).text.toString())
        val metadata=(find(detail.window!!.decorView,"film_info_metadata") as TextView).text.toString()
        assertTrue(metadata,metadata.contains("2004") && metadata.contains("2012"))
        val description=(find(detail.window!!.decorView,"film_info_description") as TextView).text.toString()
        assertTrue(description,description.contains("400 ISO"))
        assertEquals("Info must not change film",before,(find(root,"choose_film") as CatalogGlassButton).text.toString())
        assertTrue("Underlying picker must remain",picker.isShowing)
        screenshot("film-info")
        instrumentation.runOnMainSync {find(detail.window!!.decorView,"film_info_close")!!.performClick()}
        waitFor("return") {dialog(a,"filmInfoDialog")==null}
        assertEquals("Elite Color 400",search.text.toString())
        assertEquals(1,list.adapter.count)
        screenshot("film-info-list")
        instrumentation.runOnMainSync {picker.dismiss()}
    }

    fun testFilmSearchOpensImeAndKeepsListBelowCategory() {
        val a=activity;val root=a.window.decorView
        waitFor("initialized") {find(root,"choose_film")?.isEnabled==true}
        instrumentation.runOnMainSync {find(root,"choose_film")!!.performClick()}
        waitFor("film sheet") {dialog(a,"filmDialog")?.isShowing==true}
        val sheet=dialog(a,"filmDialog")!!;val decor=sheet.window!!.decorView
        val search=find(decor,"film_search") as EditText
        val category=find(decor,"film_category")!!
        val results=find(decor,"film_results") as ListView
        assertEquals(0,sheet.window!!.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        val down=SystemClock.uptimeMillis()
        inject(search,down,MotionEvent.ACTION_DOWN,search.width*.4f,search.height*.5f)
        inject(search,down,MotionEvent.ACTION_UP,search.width*.4f,search.height*.5f)
        waitFor("IME becomes visible on search tap") {
            search.hasFocus() && decor.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true
        }
        instrumentation.sendStringSync("Portra")
        waitFor("typed search filters films") {
            search.text.toString()=="Portra" && results.adapter.count in 1..10 && results.childCount>0
        }
        val rows=IntArray(2);val categories=IntArray(2)
        instrumentation.runOnMainSync {
            results.getLocationOnScreen(rows);category.getLocationOnScreen(categories)
            assertTrue("Results must be below the category",rows[1]>=categories[1]+category.height)
            assertEquals(android.graphics.Rect(0,0,results.width,results.height),results.clipBounds)
        }
        screenshot("film-search-keyboard")
        instrumentation.runOnMainSync {
            (a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(search.windowToken,0)
        }
        waitFor("keyboard hides") {decor.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==false}
        instrumentation.runOnMainSync {search.setText("");search.clearFocus();results.setSelection(30)}
        waitFor("list scrolls with bounded viewport") {results.firstVisiblePosition>=29}
        screenshot("film-list-scrolled")
        instrumentation.runOnMainSync {sheet.dismiss()}
    }

    fun testDrawerDragAndPhotoZoomAutoCollapse() {
        val a=activity
        val root=a.window.decorView
        waitFor("initialized") {find(root,"choose_film")?.isEnabled==true}
        instrumentation.runOnMainSync {a.loadPhoto(Uri.parse("content://dev.cxo.filmpack.app.test.photos/photo-mosaic.png"))}
        waitFor("photo") {find(root,"export_photo")?.isEnabled==true}
        val drawer=find(root,"editor_drawer")!!
        val handle=find(root,"editor_drawer_handle")!!
        assertNotNull("Status must belong to the operation panel",find(drawer,"editor_panel_status"))
        assertEquals("Panel must meet both screen edges",root.width,drawer.width)
        val down=SystemClock.uptimeMillis()
        val x=handle.width/2f;val y=handle.height/2f
        event(handle,down,MotionEvent.ACTION_DOWN,x,y)
        for(step in 1..8)event(handle,down,MotionEvent.ACTION_MOVE,x,y-dp(32)*step)
        event(handle,down,MotionEvent.ACTION_UP,x,y-dp(256))
        waitFor("upward pull expands drawer") {drawer.height>dp(300)}

        val photo=find(root,"photo_preview") as ZoomablePreviewImageView
        val centerX=photo.width*.5f;val centerY=photo.height*.45f
        val start=SystemClock.uptimeMillis();val span=dp(56).toFloat()
        multiEvent(photo,start,MotionEvent.ACTION_DOWN,centerX-span/2 to centerY)
        multiEvent(photo,start,MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            centerX-span/2 to centerY,centerX+span/2 to centerY)
        for(step in 1..10) {
            val current=span+(dp(120)-span)*step/10f
            multiEvent(photo,start,MotionEvent.ACTION_MOVE,centerX-current/2 to centerY,centerX+current/2 to centerY)
            SystemClock.sleep(16)
        }
        multiEvent(photo,start,MotionEvent.ACTION_CANCEL,centerX-dp(120)/2 to centerY,centerX+dp(120)/2 to centerY)
        assertTrue("Photo zoom gesture did not zoom",photo.zoomScale>1.2f)
        waitFor("zoom automatically retracts drawer") {drawer.height<=dp(114)}
    }

    private fun dp(value:Int)=(value*instrumentation.targetContext.resources.displayMetrics.density+.5f).toInt()
    private fun dragSliderWithDrift(slider:CatalogGlassSlider,toFraction:Float,driftY:Float,steps:Int=12) {
        val view=slider
        val gutter=dp(36).toFloat()
        val trackWidth=view.width-2*gutter
        val fromX=gutter+trackWidth*slider.progress/slider.max
        val toX=gutter+trackWidth*toFraction
        val mid=view.height/2f;val down=SystemClock.uptimeMillis()
        inject(view,down,MotionEvent.ACTION_DOWN,fromX,mid)
        for(step in 1..steps) {
            inject(view,down,MotionEvent.ACTION_MOVE,fromX+(toX-fromX)*step/steps,mid+driftY*step/steps)
            SystemClock.sleep(16)
        }
        inject(view,down,MotionEvent.ACTION_UP,toX,mid+driftY)
    }
}
