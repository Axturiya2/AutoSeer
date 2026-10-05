package com.autoseer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.autoseer.R
import android.graphics.Bitmap
import com.autoseer.capture.ScreenCaptureManager
import com.autoseer.core.AndroidLogger
import com.autoseer.core.DelayPrefs
import com.autoseer.core.RunPrefs
import com.autoseer.core.DeviceTemplates
import com.autoseer.core.OpenCvMatcher
import com.autoseer.core.SeerStorage
import com.autoseer.input.GestureAccessibilityService
import com.autoseer.libautomata.IGestureService
import com.autoseer.libautomata.Location
import com.autoseer.overlay.ControlOverlay
import com.autoseer.core.ScriptStore
import com.autoseer.core.SeerScript
import com.autoseer.runner.ScriptRunner
import com.autoseer.core.FactorLibrary
import com.autoseer.core.FactorPlan
import com.autoseer.core.FactorPlanEntry
import com.autoseer.core.FactorPlanPrefs
import com.autoseer.scripts.BattleScript
import com.autoseer.scripts.BattlePlan
import com.autoseer.scripts.BattlePlanParser
import com.autoseer.scripts.FactorTarget
import com.autoseer.scripts.FactorSearchNavTestScript
import com.autoseer.scripts.FactorSearchSweep
import com.autoseer.scripts.FactorSweepProgress
import com.autoseer.scripts.ProbeScript
import com.autoseer.scripts.SeerFactorScript
import com.autoseer.ui.CardCaptureActivity
import java.io.File
import java.io.FileOutputStream

/**
 * Foreground service that owns the whole automation runtime: MediaProjection
 * capture, the floating control overlay, and the [ScriptRunner]. Started by
 * [com.autoseer.ui.MainActivity] once the user grants screen capture.
 */
class AutoSeerService : Service() {

    private var capture: ScreenCaptureManager? = null
    private var overlay: ControlOverlay? = null
    private var runner: ScriptRunner? = null

    // Resolves the accessibility service lazily so it works once it connects.
    private val gestures = object : IGestureService {
        override fun click(location: Location, durationMs: Long) {
            val svc = GestureAccessibilityService.instance
            if (svc == null) Log.w(TAG, "無障礙服務未連線，無法點擊") else svc.click(location, durationMs)
        }
        override fun swipe(from: Location, to: Location, durationMs: Long) {
            GestureAccessibilityService.instance?.swipe(from, to, durationMs)
        }
        override fun dragSteady(from: Location, to: Location, durationMs: Long, holdMs: Long) {
            GestureAccessibilityService.instance?.dragSteady(from, to, durationMs, holdMs)
        }
        override fun setText(text: String): Boolean =
            GestureAccessibilityService.instance?.setText(text) ?: false
        override fun focusedText(): String? =
            GestureAccessibilityService.instance?.focusedText()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }

        startForegroundInternal()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (resultCode == 0 || data == null) {
            Log.e(TAG, "缺少螢幕擷取授權資料，停止服務")
            stopEverything()
            return START_NOT_STICKY
        }

        setup(resultCode, data)
        return START_NOT_STICKY
    }

    private fun setup(resultCode: Int, data: Intent) {
        // Re-authorizing while already running must not stack overlays/captures:
        // release any previous runtime first (BUG-1), then build fresh from the
        // new projection. This does not stop the service (no stopForeground/Self).
        releaseRuntime()

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection: MediaProjection = mpm.getMediaProjection(resultCode, data)

        val metrics = resources.displayMetrics
        val capture = ScreenCaptureManager(
            projection = projection,
            deviceWidth = metrics.widthPixels,
            deviceHeight = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
        ).also { it.start() }
        this.capture = capture

        val overlay = ControlOverlay(
            context = this,
            onStart = { startScript() },
            onStop = { runner?.stop() },
            onCapture = { captureFrame() },
            onProbe = { probeDetection() },
            onCaptureCards = { captureCardsForReview() },
            onNavTest = { navTest() },
        )
        this.overlay = overlay
        val logger = AndroidLogger(onLine = { line -> overlay.setStatus(line) })

        runner = ScriptRunner(
            screenshotProvider = capture,
            matcher = OpenCvMatcher(),
            gestures = gestures,
            logger = logger,
            onStateChange = { running -> overlay.setRunning(running) },
        )
        overlay.show()
        Log.i(TAG, "AutoSeerService 就緒：device=${metrics.widthPixels}x${metrics.heightPixels}")
    }

    private fun startScript() {
        val runner = runner ?: return
        if (!GestureAccessibilityService.isConnected) {
            overlay?.setStatus("⚠ 無障礙服務未連線，無法點擊。請到設定開啟後再試")
            Log.w(TAG, "無障礙服務未連線，取消啟動腳本")
            return
        }
        val templates = DeviceTemplates(this)
        ScriptStore.ensureSeeded(this)
        val script = ScriptStore.selected(this)
        if (script == null) {
            overlay?.setStatus("⚠ 尚未設定任何腳本，請先到「周回腳本」新增")
            Log.w(TAG, "無選取腳本，取消啟動")
            return
        }
        val delays = DelayPrefs.toBattleDelays(this)
        val defaultPlan = parseFactorPlan(script)
        overlay?.setStatus("腳本「${script.displayName}」 ${BattlePlanParser.describe(defaultPlan)}")
        // 多因子銜接＋每因子腳本：依「因子關卡排序」取得(因子, 指派腳本)清單，對齊解出各自作戰計畫；
        // 未指派＝跟隨主畫面所選腳本。此只驅動「選擇/進入」導航與挑哪個計畫，戰鬥回合引擎不受影響。
        val entries = FactorPlan.resolvedEntries(this)
        val factorTargets = ArrayList<FactorTarget>()
        val factorPlans = ArrayList<BattlePlan>()
        for (e in entries) {
            val scr = e.scriptId?.let { ScriptStore.get(this, it) } ?: script
            // 直接從檔名尾端解析 精靈ID：先去副檔名(e.name 形如 NN_名稱_ID.png)，再取最後一段數字。
            val base = e.name.substringBeforeLast('.')
            val id = base.substringAfterLast('_').takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            factorTargets += FactorTarget(e.name, id = id)
            factorPlans += parseFactorPlan(scr)
        }
        runner.start { api ->
            if (script.category == SeerScript.CATEGORY_SEER_FACTOR) {
                // 銜接＝「搜尋 ID 直達」（FactorSearchSweep）；捲動比對導航已移除。
                val sweep = if (factorTargets.isNotEmpty()) {
                    api.logger.i("搜尋銜接：${factorTargets.size} 個因子（用ID搜尋直達）")
                    overlay?.setStatus("搜尋銜接：${factorTargets.size} 個因子")
                    FactorSearchSweep(
                        api, templates, FactorSweepProgress(factorTargets), delays, api.logger,
                        // 每打完一個因子（達每日上限）就把它從選關計畫移除＝取消勾選，方便事後排查
                        // 打到哪、中途停也留進度。只寫存檔，不動本次執行中的 factorTargets（隔離不破）。
                        onFactorDone = { done -> deselectCompletedFactor(factorTargets, done) },
                    )
                } else null
                SeerFactorScript(
                    api, templates, factorPlans.firstOrNull() ?: defaultPlan, delays,
                    backToLobbyOnExhaust = RunPrefs.backToLobbyOnRetryExhausted(this),
                    sweep = sweep,
                    factorPlans = factorPlans.takeIf { it.isNotEmpty() },
                    onProgress = { stageNo, cleared -> overlay?.setProgress(stageNo, cleared) },
                )
            } else {
                BattleScript(api, templates, defaultPlan, delays)
            }
        }
    }

    /**
     * 某因子達每日上限（今日打完）→ 從「因子關卡排序」計畫把它移除＝取消勾選。
     *
     * 目的：使用者事後（或中途停掉時）一眼看出打到哪——還亮著的第一個就是沒打完/卡住的那個，
     * 不必每次從頭檢視。純寫存檔（SharedPreferences，執行緒安全），**不動**本次執行中已建好的
     * in-memory [factorTargets]，故不干擾正在跑的掃蕩——維持「選關 / 戰鬥」兩模組的隔離。
     *
     * 無存檔計畫（open-box 全打）時，先用本次完整清單具現化再移除；跑到全空時，下次開 UI 因
     * 「無有效計畫」會自動回到預設全選（見 FactorPlan.resolvedEntries）。
     */
    private fun deselectCompletedFactor(allTargets: List<FactorTarget>, done: FactorTarget) {
        val base = FactorPlanPrefs.getEntries(this)
            ?: allTargets.map { FactorPlanEntry(it.name, null) }
        val remaining = base.filterNot { it.name == done.name }
        if (remaining.size != base.size) {
            FactorPlanPrefs.setEntries(this, remaining)
            Log.i(TAG, "因子已完成，取消勾選「${done.name}」，剩 ${remaining.size} 個")
        }
    }

    /** Parse one saved script into a [BattlePlan] (script fields + global run options). */
    private fun parseFactorPlan(script: SeerScript): BattlePlan {
        val parsed = BattlePlanParser.parse(
            text = script.planText,
            maxBattles = script.maxBattles,
            healBeforeBattle = RunPrefs.healBeforeBattle(this),
            advanceMap = script.advanceMap,
            defaultSlot = RunPrefs.defaultSlot(this),
            startStage = 1,  // 起始關卡改為固定 1（動態辨識「關卡 N」校正相位，不再讀 RunPrefs）
            maxRetriesPerStage = script.maxRetries,
        )
        parsed.warnings.forEach { Log.w(TAG, "計畫解析警告：$it") }
        return parsed.plan
    }

    /** Save the current normalized color frame to <externalFilesDir>/captures for cropping into templates. */
    private fun captureFrame() {
        val cap = capture ?: run { overlay?.setStatus("尚未就緒，無法擷取"); return }
        Thread {
            try {
                val bmp = cap.captureColorBitmap()
                val dir = SeerStorage.capturesDir(this)
                val file = File(dir, "cap_${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                Log.i(TAG, "已存畫面：${file.absolutePath}")
                overlay?.setStatus("已存畫面：${file.name}\n${dir.absolutePath}")
            } catch (e: Throwable) {
                Log.e(TAG, "存畫面失敗", e)
                overlay?.setStatus("存畫面失敗：${e.message}")
            }
        }.start()
    }

    /**
     * 精靈圖像卡擷取（階段 A）：對當前畫面截一張正規化彩圖，寫成暫存檔，開啟
     * [CardCaptureActivity] 讓玩家檢視/修正候選卡框並存到自選資料夾。
     */
    private fun captureCardsForReview() {
        val cap = capture ?: run { overlay?.setStatus("尚未就緒，無法擷取"); return }
        // 先隱藏懸浮窗，避免面板出現在截圖裡遮住卡。
        overlay?.setChromeVisible(false)
        Thread {
            try {
                Thread.sleep(CHROME_HIDE_MS)   // 等合成器把面板移出畫面
                val bmp = cap.captureColorBitmap()
                overlay?.setChromeVisible(true)   // 抓到影格後即恢復懸浮窗
                val file = File(cacheDir, "cardcap_${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                val intent = Intent(this, CardCaptureActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(CardCaptureActivity.EXTRA_PATH, file.absolutePath)
                }
                startActivity(intent)
                overlay?.setStatus("已擷取畫面，開啟檢視頁裁卡")
            } catch (e: Throwable) {
                overlay?.setChromeVisible(true)
                Log.e(TAG, "擷取卡失敗", e)
                overlay?.setStatus("擷取卡失敗：${e.message}")
            }
        }.start()
    }

    /**
     * 因子導航測試（不打戰鬥）：只跑「選擇→進入→退出→下一個因子（含捲動尋找）」的銜接
     * 流程，用來單獨驗證導航，不呼叫戰鬥模組。目標＝精靈圖庫內所有因子卡。
     */
    private fun navTest() {
        val runner = runner ?: return
        if (!GestureAccessibilityService.isConnected) {
            overlay?.setStatus("⚠ 無障礙服務未連線，無法點擊。請先到設定開啟")
            return
        }
        // 因子「搜尋導航」測試：讀圖庫中 NN_名稱_ID 檔名的 ID，逐一「搜尋→點結果→進詳情→退出」。
        // 先在因子選擇格畫面按此鈕。結果見狀態列/Logcat（tag AutoSeer）。
        val idTargets = FactorLibrary.listWithIds(this)
        if (idTargets.isEmpty()) {
            overlay?.setStatus("⚠ 圖庫沒有帶ID的因子卡（檔名需 NN_名稱_ID）")
            return
        }
        overlay?.setStatus("因子搜尋導航測試：${idTargets.size} 個帶ID因子（見 Logcat）")
        val templates = DeviceTemplates(this)
        runner.start { api -> FactorSearchNavTestScript(api, templates, idTargets) }
    }

    /** Run the detection probe: report each template's match score without tapping anything. */
    private fun probeDetection() {
        val runner = runner ?: return
        overlay?.setStatus("偵測測試中…（結果見狀態列/Logcat）")
        runner.start { api -> ProbeScript(api, DeviceTemplates(this)) }
    }

    private fun startForegroundInternal() {
        val channelId = "autoseer_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        getString(R.string.notif_channel),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                )
            }
        }
        // NotificationCompat：Notification.Builder(Context, String) 是 API 26 才有，minSdk 24 直接用會在 7.x 閃退
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    /** Tear down the capture/overlay/runner without stopping the service itself. */
    private fun releaseRuntime() {
        runner?.stop()
        overlay?.hide()
        capture?.release()
        runner = null
        overlay = null
        capture = null
    }

    private fun stopEverything() {
        releaseRuntime()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AutoSeer"
        private const val NOTIF_ID = 1001
        private const val CHROME_HIDE_MS = 150L   // 擷取前隱藏懸浮窗、等畫面更新的時間
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "result_data"
        const val ACTION_STOP = "com.autoseer.action.STOP"

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, AutoSeerService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, AutoSeerService::class.java).apply { action = ACTION_STOP }
    }
}
