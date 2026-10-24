package io.github.azaberrypi.lonefuzz

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.azaberrypi.lonefuzz.model.ComponentKind
import io.github.azaberrypi.lonefuzz.model.TargetApp
import io.github.azaberrypi.lonefuzz.model.TargetComponent
import io.github.azaberrypi.lonefuzz.ui.AppListAdapter
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.util.ArrayDeque

class MainActivity : AppCompatActivity(), AttackEngine.Listener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var engine: AttackEngine
    private lateinit var adapter: AppListAdapter
    private lateinit var scanEngine: ScanEngine
    private lateinit var hitStore: HitStore

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var stats: TextView
    private lateinit var recipeContainer: ChipGroup
    private lateinit var detectorStatus: TextView
    private lateinit var dumpStatus: TextView
    private lateinit var statusRow: LinearLayout
    private lateinit var btnScan: Button
    private lateinit var btnBurst: Button

    private val a11yManager by lazy {
        getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
    }

    /** 前景のままユーザー補助が切り替わっても状態ピルを即時追従させる。 */
    private val a11yStateListener =
        AccessibilityManager.AccessibilityStateChangeListener { updateDetectorStatus() }

    private val recipeChecks = LinkedHashMap<RecipeType, Chip>()

    private var allApps: List<TargetApp> = emptyList()
    private lateinit var searchBox: EditText

    private val logBuffer = ArrayDeque<String>()
    private var flushPending = false
    private var pendingSent = 0
    private var pendingErr = 0
    private var statsPending = false

    private var totalHits = 0
    private var lastScanHits = emptyList<ScanEngine.Result>()

    /** BURST の連射サイクル数（連射は止めるまで継続するため、UI に明示する）。 */
    private var burstCycle = 0

    /** ログ領域の開閉（既定は閉。対象アプリ一覧のスペースを優先する）。 */
    private var logExpanded = false

    private val hitListener = CrashWatcher.HitListener { hit ->
        mainHandler.post {
            // spray/BURST 実行中は ScanEngine が結果と信頼度を一元集計する。
            // ここで数えると二重カウントになるため、手動 SHOT/連射 時のみ数える。
            if (scanEngine.scanning) return@post
            totalHits++
            val msg = "HIT! ${hit.appLabel} / ${hit.componentShort} [${hit.recipe}] (${hit.kind})"
            appendLog("[$msg]")
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            updateStatsDisplay()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyWindowInsets()

        engine = AttackEngine(this)
        scanEngine = ScanEngine(this)
        hitStore = HitStore(this)

        logView = findViewById(R.id.log)
        logScroll = findViewById(R.id.logScroll)
        stats = findViewById(R.id.stats)
        recipeContainer = findViewById(R.id.recipeContainer)
        detectorStatus = findViewById(R.id.crashDetectorStatus)
        dumpStatus = findViewById(R.id.dumpStatus)
        statusRow = findViewById(R.id.statusRow)
        btnScan = findViewById(R.id.btnScan)
        btnBurst = findViewById(R.id.btnBurst)

        val list = findViewById<RecyclerView>(R.id.appList)
        adapter = AppListAdapter()
        adapter.onSelectionChanged = { refreshBurstButton() }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        buildRecipeChips()

        searchBox = findViewById(R.id.searchBox)
        searchBox.doAfterTextChanged { applyFilter() }

        findViewById<Button>(R.id.btnRefresh).setOnClickListener { enumerate() }

        findViewById<Button>(R.id.btnShot).setOnClickListener {
            engine.fireBurst(selectedComponents(), selectedRecipes(), this)
        }

        setupFireButton(findViewById(R.id.btnFire))

        btnScan.setOnClickListener { toggleScan() }
        btnBurst.setOnClickListener { toggleBurst() }

        // ボタンの意味は長押しツールチップで補足する（連射ボタンは長押し=発火なので除く）。
        TooltipCompat.setTooltipText(findViewById(R.id.btnScan), getString(R.string.tip_spray))
        TooltipCompat.setTooltipText(findViewById(R.id.btnBurst), getString(R.string.tip_burst))
        TooltipCompat.setTooltipText(findViewById(R.id.btnShot), getString(R.string.tip_shot))

        detectorStatus.setOnClickListener {
            if (!isDetectorEnabled()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        dumpStatus.setOnClickListener {
            Toast.makeText(
                this,
                "adb shell pm grant $packageName android.permission.DUMP",
                Toast.LENGTH_LONG,
            ).show()
        }

        // ログは既定で閉。見出しタップで開閉、長押しで結果コピー（旧「結果コピー」ボタンを置換）
        val logToggle = findViewById<TextView>(R.id.logToggle)
        logToggle.setOnClickListener { setLogExpanded(!logExpanded) }
        logToggle.setOnLongClickListener {
            copyResults()
            true
        }
        logView.setOnLongClickListener {
            copyResults()
            true
        }

        setupScanCallbacks()

        logView.text = getString(R.string.log_cleared)
        requestNotificationPermissionIfNeeded()
        enumerate()

        handleStopIntent(intent)
    }

    /**
     * Android 13+ は通知がランタイム権限。未付与だと BURST の FGS 常駐通知が出ず、
     * 通知バーから停止できないため、起動時に要求する。
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleStopIntent(intent)
    }

    private fun handleStopIntent(intent: Intent?) {
        if (intent?.action == AttackForegroundService.ACTION_STOP) {
            stopBurst()
        }
    }

    private fun setupScanCallbacks() {
        scanEngine.onLog = { line -> appendLog(line) }
        scanEngine.onProgress = { done, total, hits ->
            if (scanEngine.bursting) {
                stats.text = "BURST: 連射 $burstCycle 周目 | HIT $hits（止めるまで継続）"
            } else {
                stats.text = "spray: $done/$total  hit: $hits"
            }
        }
        scanEngine.onHit = { result ->
            totalHits++
            val conf = if (result.confidence == ScanEngine.HitConfidence.CONFIRMED) "確定" else "疑い"
            val msg = "HIT[$conf] ${result.appLabel} / ${result.component} [${result.recipe}]"
            appendLog("[$msg]")
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
        scanEngine.onComplete = { results ->
            if (!scanEngine.bursting) {
                // spray 完了
                val hits = results.count { it.hit }
                val confirmed = results.count { it.confidence == ScanEngine.HitConfidence.CONFIRMED }
                val suspected = results.count { it.confidence == ScanEngine.HitConfidence.SUSPECTED }
                val apps = results.map { it.packageName }.distinct().size
                val vulnApps = results.filter { it.hit }.map { it.packageName }.distinct().size
                appendLog("=== spray完了: $apps アプリ / ${results.size} テスト → $hits HIT ($vulnApps アプリ) | 確定$confirmed / 疑い$suspected ===")
                btnScan.text = getString(R.string.btn_scan)

                lastScanHits = results.filter { it.hit }
                hitStore.record(lastScanHits) // 武器庫へ永続化（アンインストールまで保持。版では無効化しない）
                refreshBurstButton()
            } else {
                appendLog("=== BURST 停止 ===")
                btnBurst.text = getString(R.string.btn_burst)
                AttackControl.setStopListener(null)
            }
            stopFgs()
            updateStatsDisplay()
        }
        scanEngine.onCycleComplete = { cycle ->
            burstCycle = cycle
            appendLog("--- 連射サイクル $cycle 完了（止めるまで継続）---")
        }
        scanEngine.onStaleDetected = { result ->
            // 「効かなくなった」を検知。武器庫からは無効化するが、連射は止めない
            // （通知バー/ボタンで止めるまで継続）。再探索は手動 spray で行う。
            appendLog("=== 効かなくなった: ${result.appLabel} / ${result.component} [${result.recipe}] を武器庫から無効化（連射は継続。再探索は手動 spray で） ===")
            hitStore.remove(result.packageName, result.className, result.recipeType.name)
        }
    }

    private fun toggleScan() {
        if (scanEngine.scanning) {
            scanEngine.stop()
            btnScan.text = getString(R.string.btn_scan)
            appendLog("=== spray中断 ===")
            lastScanHits = scanEngine.getHits()
            hitStore.record(lastScanHits) // 中断時点までのHITも武器庫へ
            refreshBurstButton()
            return
        }
        startSpray(selectedComponents(), selectedRecipes(), "spray")
    }

    /** spray を開始する共通導線（手動 spray と効果ベース再適応の自動再探索から使う）。 */
    private fun startSpray(targets: List<TargetComponent>, recipes: List<RecipeType>, label: String): Boolean {
        if (scanEngine.scanning) return false
        if (!CrashDetectorService.isRunning) {
            appendLog("sprayにはユーザー補助の有効化が必要です。")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return false
        }
        // 対象選択は必須（全アプリ無差別 spray は廃止）。
        if (targets.isEmpty()) {
            appendLog("対象を選択してください（アプリ行のチェック＝全コンポーネント、コンポーネント行タップ＝個別）。")
            return false
        }
        if (recipes.isEmpty()) {
            appendLog("レシピが選択されていません。")
            return false
        }

        val appCount = targets.map { it.packageName }.distinct().size
        val a = targets.count { it.kind == ComponentKind.ACTIVITY }
        val s = targets.count { it.kind == ComponentKind.SERVICE }
        val r = targets.count { it.kind == ComponentKind.RECEIVER }
        val p = targets.count { it.kind == ComponentKind.PROVIDER }
        appendLog(
            "=== ${label}開始: $appCount アプリ / $a A, $s S, $r R, $p P × ${recipes.size} レシピ ===",
        )
        if (s + r + p > 0 && !CrashWatcher.hasDumpPermission(this)) {
            appendLog(
                "※ Activity 以外は DUMP 未付与だとダイアログ観測のみで取りこぼしがあります" +
                    "（確定には DUMP を付与）。",
            )
        }
        btnScan.text = getString(R.string.btn_scan_stop)
        refreshBurstButton()
        setLogExpanded(true)
        scanEngine.start(targets, recipes)
        return true
    }

    private fun toggleBurst() {
        if (scanEngine.bursting) {
            stopBurst()
            return
        }

        if (lastScanHits.isEmpty()) {
            // 今回の spray が無くても、武器庫に選択対象の HIT があれば再利用する（バージョンは問わない）。
            val pkgs = selectedComponents().map { it.packageName }.distinct()
            if (pkgs.isEmpty()) {
                appendLog("対象を選択し、spray するか保存済みHITのある対象を選んでください。")
                return
            }
            val stored = pkgs.flatMap { hitStore.storedFor(it) }
            if (stored.isEmpty()) {
                appendLog("選択対象に保存済みHITがありません。先にsprayを実行してください。")
                return
            }
            lastScanHits = HitStore.asResults(stored)
            appendLog("武器庫から ${lastScanHits.size} 件のHITを読み込みました（最終確認版を保持・版では無効化しません）。")
        }

        if (!CrashDetectorService.isRunning) {
            appendLog("BURST にはユーザー補助の有効化が必要です。")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        val hitApps = lastScanHits.map { it.packageName }.distinct().size
        burstCycle = 0
        appendLog("=== BURST 開始: ${lastScanHits.size} HIT ($hitApps アプリ) — 止めるまで連射を継続 ===")
        btnBurst.text = getString(R.string.btn_burst_stop)
        setLogExpanded(true)
        startFgs()
        AttackControl.setStopListener {
            mainHandler.post { if (scanEngine.bursting) stopBurst() }
        }
        scanEngine.startBurst(lastScanHits)
        refreshBurstButton()
    }

    private fun stopBurst() {
        AttackControl.setStopListener(null)
        scanEngine.stop()
        stopFgs()
        btnBurst.text = getString(R.string.btn_burst)
        appendLog("=== BURST 停止 ===")
        refreshBurstButton()
        updateStatsDisplay()
    }

    private fun startFgs() {
        val intent = Intent(this, AttackForegroundService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopFgs() {
        stopService(Intent(this, AttackForegroundService::class.java))
    }

    /** edge-to-edge 有効時、ステータス/ナビゲーションバー分の余白を確保する。 */
    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.root)
        val baseTop = root.paddingTop
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = baseTop + bars.top, bottom = baseBottom + bars.bottom)
            insets
        }
    }

    private fun setLogExpanded(expanded: Boolean) {
        logExpanded = expanded
        logScroll.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    private fun copyResults() {
        val text = scanEngine.exportResults()
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("LoneFuzz Results", text))
        Toast.makeText(this, "結果をコピーしました", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        CrashWatcher.setListener(hitListener)
        a11yManager.addAccessibilityStateChangeListener(a11yStateListener)
        updateDetectorStatus()
        updateDumpStatus()
        refreshBurstButton()
        // a11y サービスは onResume 直後に接続完了することがあり、初回表示が
        // 「検知 OFF」のまま残る。少し遅らせて再確認する。
        mainHandler.postDelayed({
            updateDetectorStatus()
            updateDumpStatus()
        }, 700)

        if (scanEngine.scanning) {
            scanEngine.onResumed()
        } else {
            val rapidHit = CrashWatcher.checkRapidReturn()
            if (rapidHit != null) {
                totalHits++
                val msg = "HIT! ${rapidHit.appLabel} / ${rapidHit.componentShort} [${rapidHit.recipe}]"
                appendLog("[$msg] (即戻り検知)")
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                updateStatsDisplay()
            }
        }
    }

    override fun onPause() {
        CrashWatcher.setListener(null)
        a11yManager.removeAccessibilityStateChangeListener(a11yStateListener)
        super.onPause()
    }

    /**
     * 残留クラッシュダイアログを閉じるための BACK が、万一ダイアログが無い状態で
     * 自分に届いても UI を閉じないようにする（scan 中は BACK を無視）。
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (scanEngine.scanning) return
        super.onBackPressed()
    }

    /** 検知/DUMP は「未付与で要対応」のときだけ出す（ON はノイズなので隠す）。 */
    private fun updateDetectorStatus() {
        val on = isDetectorEnabled()
        detectorStatus.visibility = if (on) View.GONE else View.VISIBLE
        if (!on) applyPill(detectorStatus)
        updateStatusRowVisibility()
    }

    /**
     * プロセス内フラグ（CrashDetectorService.isRunning）は接続タイミングに依存して
     * 表示が古くなるため、実際に有効なユーザー補助サービス一覧を照会して判定する。
     */
    private fun isDetectorEnabled(): Boolean {
        if (!a11yManager.isEnabled) return false
        return a11yManager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any { it.resolveInfo?.serviceInfo?.packageName == packageName }
    }

    private fun updateDumpStatus() {
        val on = CrashWatcher.hasDumpPermission(this)
        dumpStatus.visibility = if (on) View.GONE else View.VISIBLE
        if (!on) applyPill(dumpStatus)
        updateStatusRowVisibility()
    }

    private fun updateStatusRowVisibility() {
        val anyOff = detectorStatus.visibility == View.VISIBLE || dumpStatus.visibility == View.VISIBLE
        statusRow.visibility = if (anyOff) View.VISIBLE else View.GONE
    }

    private fun applyPill(v: TextView) {
        // 未付与=赤系のピル。タップで対処。
        v.backgroundTintList = ColorStateList.valueOf(0x33FF5252.toInt())
        v.setTextColor(0xFFFF5252.toInt())
    }

    private fun buildRecipeChips() {
        val strokePx = resources.displayMetrics.density * 1f
        // 未選択でも「無効」に見えないよう、空色の輪郭＋明るい文字で操作可能であることを示す。
        val chipTextColors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(getColor(R.color.on_accent), getColor(R.color.on_surface)),
        )
        val chipBgColors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(getColor(R.color.accent), getColor(R.color.surface_high)),
        )
        for (type in RecipeType.entries) {
            val chip = Chip(this).apply {
                text = type.label
                isCheckable = true
                isChecked = type.defaultOn
                textSize = 12f
                chipStrokeWidth = strokePx
                chipStrokeColor = ColorStateList.valueOf(getColor(R.color.accent_dim))
                setTextColor(chipTextColors)
                chipBackgroundColor = chipBgColors
            }
            recipeContainer.addView(chip)
            recipeChecks[type] = chip
        }
    }

    private fun selectedRecipes(): List<RecipeType> =
        recipeChecks.filter { it.value.isChecked }.keys.toList()

    private fun selectedComponents(): List<TargetComponent> =
        allApps.flatMap { it.components }.filter { it.selected }

    /**
     * BURST が今すぐ開始できるか。今回の spray 結果があるか、選択対象に
     * 武器庫の保存済み HIT があれば可能。ボタンは常時表示し、可否だけ反映する。
     */
    private fun canBurst(): Boolean {
        if (lastScanHits.isNotEmpty()) return true
        val pkgs = selectedComponents().map { it.packageName }.distinct()
        if (pkgs.isEmpty()) return false
        return pkgs.any { hitStore.storedFor(it).isNotEmpty() }
    }

    /**
     * BURST は常時表示のまま、押せる/押せないだけを切り替える
     * （実行中にボタンが生えたり消えたりしないように）。
     */
    private fun refreshBurstButton() {
        btnBurst.isEnabled = when {
            scanEngine.bursting -> true // 停止ボタンとして押せる
            scanEngine.scanning -> false // spray 中は開始不可
            else -> canBurst()
        }
    }

    private fun enumerate() {
        appendLog("アプリ列挙中...")
        Thread {
            val apps = ComponentEnumerator(packageManager).enumerate(excludePackage = packageName)
            mainHandler.post {
                allApps = apps
                applyFilter()
                appendLog("列挙完了: ${apps.size} アプリに exported コンポーネントあり")
                // アンインストール済みを掃除し、残っている武器庫を通知。
                hitStore.pruneTo(apps.map { it.packageName }.toSet())
                val stored = hitStore.count()
                if (stored > 0) {
                    appendLog("武器庫: 保存済みHIT $stored 件（対象を選び「BURST」で spray 無しに再利用可）")
                }
                refreshBurstButton()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun applyFilter() {
        val query = searchBox.text?.toString()?.trim()?.lowercase().orEmpty()
        var shown = allApps
        if (query.isNotEmpty()) {
            shown = shown.filter {
                it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query)
            }
        }
        shown = shown.sortedBy { it.label.lowercase() }
        adapter.submit(shown)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFireButton(button: View) {
        button.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    v.isPressed = true
                    engine.start(selectedComponents(), selectedRecipes(), this)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    engine.stop()
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    v.performClick()
                    true
                }

                else -> false
            }
        }
    }

    // ---- AttackEngine.Listener ----

    override fun onLog(line: String) = appendLog(line)

    override fun onStats(sent: Int, errors: Int) {
        synchronized(logBuffer) {
            pendingSent = sent
            pendingErr = errors
            if (statsPending) return
            statsPending = true
        }
        mainHandler.post {
            synchronized(logBuffer) {
                statsPending = false
            }
            updateStatsDisplay()
        }
    }

    private fun updateStatsDisplay() {
        if (scanEngine.scanning) return
        val s: Int
        val e: Int
        synchronized(logBuffer) {
            s = pendingSent
            e = pendingErr
        }
        stats.text = "sent: $s  err: $e  hit: $totalHits"
    }

    private fun appendLog(line: String) {
        synchronized(logBuffer) {
            logBuffer.addLast(line)
            while (logBuffer.size > MAX_LOG_LINES) logBuffer.removeFirst()
            if (flushPending) return
            flushPending = true
        }
        mainHandler.post {
            val text: String
            synchronized(logBuffer) {
                flushPending = false
                text = logBuffer.joinToString("\n")
            }
            logView.text = text
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onDestroy() {
        AttackControl.setStopListener(null)
        engine.stop()
        scanEngine.stop()
        stopFgs()
        CrashWatcher.setListener(null)
        super.onDestroy()
    }

    companion object {
        private const val MAX_LOG_LINES = 300
        private const val REQ_NOTIFICATIONS = 1001
    }
}
