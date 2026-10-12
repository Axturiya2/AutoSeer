package com.autoseer.runner

import com.autoseer.libautomata.AutomataApi
import com.autoseer.libautomata.IGestureService
import com.autoseer.libautomata.IImageMatcher
import com.autoseer.libautomata.IScreenshotProvider
import com.autoseer.libautomata.Logger
import com.autoseer.libautomata.Script
import com.autoseer.libautomata.ScriptAbortException

/**
 * Runs one [Script] at a time on a background thread. Wires an [AutomataApi]
 * from the platform pieces and exposes a stop flag the script polls via the API.
 */
class ScriptRunner(
    private val screenshotProvider: IScreenshotProvider,
    private val matcher: IImageMatcher,
    private val gestures: IGestureService,
    private val logger: Logger,
    private val onStateChange: (running: Boolean) -> Unit = {},
) {
    @Volatile
    var isRunning: Boolean = false
        private set

    private var thread: Thread? = null

    /** Build the API and start [scriptFactory]'s script. No-op if already running. */
    fun start(scriptFactory: (AutomataApi) -> Script) {
        if (isRunning) return
        isRunning = true
        onStateChange(true)
        val api = AutomataApi(
            screenshotProvider = screenshotProvider,
            matcher = matcher,
            gestures = gestures,
            logger = logger,
            isRunning = { isRunning },
        )
        val script = scriptFactory(api)
        thread = Thread({
            try {
                // 生命週期訊息只進 Logcat，不進氣泡狀態列——否則「腳本結束」會蓋掉腳本自己真正
                // 的最後一行(例如結束原因)，讓使用者在氣泡上看不到為何停。
                android.util.Log.i("AutoSeer", "啟動腳本：${script.name}")
                script.run()
                android.util.Log.i("AutoSeer", "腳本結束：${script.name}")
            } catch (e: ScriptAbortException) {
                android.util.Log.i("AutoSeer", "腳本已停止：${script.name}")
            } catch (e: InterruptedException) {
                android.util.Log.i("AutoSeer", "腳本已停止：${script.name}")
            } catch (e: Throwable) {
                logger.e("腳本錯誤：${e.message}", e)   // 錯誤仍進氣泡
            } finally {
                isRunning = false
                onStateChange(false)
            }
        }, "AutoSeerScript").also { it.start() }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        thread?.interrupt()
        thread = null
        onStateChange(false)
    }
}
