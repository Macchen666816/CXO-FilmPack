package dev.cxo.filmpack.app

import android.os.ParcelFileDescriptor
import android.test.ActivityInstrumentationTestCase2
import android.view.WindowManager

/** MIUI blocks instrumentation.startActivitySync as a background launch. Start
 * our own activity through the authorized test shell, then bind the same instance
 * to the standard testcase. This changes no device security/background setting. */
@Suppress("DEPRECATION")
abstract class ForegroundEditorTest : ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    private var launched:MainActivity?=null
    override fun getActivity():MainActivity {
        launched?.let {return it}
        val monitor=instrumentation.addMonitor(MainActivity::class.java.name,null,false)
        try {
            instrumentation.setInTouchMode(false)
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n dev.cxo.filmpack.app/.MainActivity"
            )).use {it.readBytes()}
            val a=monitor.waitForActivityWithTimeout(30_000) as? MainActivity ?: error("Editor did not launch")
            instrumentation.runOnMainSync {a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            setActivity(a);launched=a
            return a
        } finally {instrumentation.removeMonitor(monitor)}
    }
}
