package io.github.azaberrypi.lonefuzz

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.TooltipCompat
import androidx.core.os.LocaleListCompat
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
    private lateinit var btnShot: Button
    private lateinit var langJa: TextView
    private lateinit var langEn: TextView
    private lateinit var langIndicator: View
    private var langChanging = false

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
    private var logGeneration = 0
    private var lastScanHits = emptyList<ScanEngine.Result>()

    /** 言語切替で Activity が再生成された際、列挙完了後に選択状態を復元する。 */
    private var pendingSelectedKeys: Set<String>? = null
    private var pendingExpandedPkgs: Set<String>? = null

    /** BURST の連射サイクル数（連射は止めるまで継続するため、UI に明示する）。 */
    private var burstCycle = 0

    /** ログ領域の開閉（既定は閉。対象アプリ一覧のスペースを優先する）。 */
    private var logExpanded = false

    /** 実行中の spray/BURST を開始した時点の言語（ログの言語を固定する）。 */
    private var runLogText: LogText? = null

    private fun runText(): LogText =
        runLogText ?: LogText(this, LogText.currentLocale(this)).also { runLogText = it }

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
        langJa = findViewById(R.id.langJa)
        langEn = findViewById(R.id.langEn)
        langIndicator = findViewById(R.id.langIndicator)

        val list = findViewById<RecyclerView>(R.id.appList)
        adapter = AppListAdapter()
        adapter.onSelectionChanged = { refreshRunButtons() }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        btnShot = findViewById(R.id.btnShot)
        btnShot.setOnClickListener {
            if (scanEngine.scanning) {
                appendLog(getString(R.string.log_shot_blocked))
                return@setOnClickListener
            }
            val comps = selectedComponents()
            val recipes = selectedRecipes()
            if (comps.size != 1 || recipes.size != 1) {
                appendLog(getString(R.string.log_shot_select_one))
                return@setOnClickListener
            }
            engine.fireShot(comps[0], recipes[0], this)
        }

        buildRecipeChips()
        recipeContainer.setOnCheckedStateChangeListener { _, _ -> refreshRunButtons() }

        searchBox = findViewById(R.id.searchBox)
        searchBox.doAfterTextChanged { applyFilter() }

        findViewById<Button>(R.id.btnRefresh).setOnClickListener { enumerate() }

        btnScan.setOnClickListener { toggleScan() }
        btnBurst.setOnClickListener { toggleBurst() }

        // ボタンの意味は長押しツールチップで補足する（連射ボタンは長押し=発火なので除く）。
        TooltipCompat.setTooltipText(findViewById(R.id.btnScan), getString(R.string.tip_spray))
        TooltipCompat.setTooltipText(findViewById(R.id.btnBurst), getString(R.string.tip_burst))
        TooltipCompat.setTooltipText(btnShot, getString(R.string.tip_shot))

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
        findViewById<TextView>(R.id.btnClearLog).setOnClickListener { clearLog() }

        setupScanCallbacks()
        setupLanguageToggle()

        logView.text = getString(R.string.log_cleared)
        restoreUiState(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        val cached = cachedApps
        if (savedInstanceState != null && cached != null) {
            // 言語切替の再生成では再列挙せず、直前の一覧と選択をそのまま使う。
            allApps = cached
            restoreSelectionState()
            applyFilter()
            refreshRunButtons()
        } else {
            enumerate()
        }

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
                stats.text = getString(R.string.stats_burst, burstCycle, hits)
            } else {
                stats.text = getString(R.string.stats_spray, done, total, hits)
            }
        }
        scanEngine.onHit = { result ->
            val t = runText()
            val conf = if (result.confidence == ScanEngine.HitConfidence.CONFIRMED) {
                t.get(R.string.conf_confirmed)
            } else {
                t.get(R.string.conf_suspected)
            }
            val msg = t.get(
                R.string.log_hit, conf, result.appLabel, result.component,
                t.get(result.recipeType.labelRes),
            )
            appendLog("[$msg]")
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
        scanEngine.onComplete = { results ->
            val t = runText()
            if (!scanEngine.bursting) {
                // spray 完了
                val hits = results.count { it.hit }
                val confirmed = results.count { it.confidence == ScanEngine.HitConfidence.CONFIRMED }
                val suspected = results.count { it.confidence == ScanEngine.HitConfidence.SUSPECTED }
                val apps = results.map { it.packageName }.distinct().size
                val vulnApps = results.filter { it.hit }.map { it.packageName }.distinct().size
                appendLog(
                    t.get(
                        R.string.log_spray_complete, apps, results.size,
                        hits, vulnApps, confirmed, suspected,
                    ),
                )
                btnScan.text = getString(R.string.btn_scan)

                lastScanHits = results.filter { it.hit }
                hitStore.record(lastScanHits) // 武器庫へ永続化（アンインストールまで保持。版では無効化しない）
                refreshRunButtons()
                AttackControl.setStopListener(null)
            } else {
                appendLog(t.get(R.string.log_burst_stop))
                btnBurst.text = getString(R.string.btn_burst)
                AttackControl.setStopListener(null)
            }
            stopFgs()
        }
        scanEngine.onCycleComplete = { cycle ->
            burstCycle = cycle
            appendLog(runText().get(R.string.log_cycle_complete, cycle))
        }
        scanEngine.onStaleDetected = { result ->
            // 「効かなくなった」を検知。武器庫からは無効化するが、連射は止めない
            // （通知バー/ボタンで止めるまで継続）。再探索は手動 spray で行う。
            val t = runText()
            appendLog(
                t.get(
                    R.string.log_stale, result.appLabel, result.component,
                    t.get(result.recipeType.labelRes),
                ),
            )
            hitStore.remove(result.packageName, result.className, result.recipeType.name)
        }
    }

    private fun toggleScan() {
        if (scanEngine.scanning) {
            stopScan()
            return
        }
        startSpray(selectedComponents(), selectedRecipes(), "spray")
    }

    /** spray を中断する（ボタン/通知の共通経路）。 */
    private fun stopScan() {
        if (!scanEngine.scanning) return
        scanEngine.stop()
        AttackControl.setStopListener(null)
        stopFgs()
        btnScan.text = getString(R.string.btn_scan)
        appendLog(runText().get(R.string.log_spray_abort))
        lastScanHits = scanEngine.getHits()
        hitStore.record(lastScanHits) // 中断時点までのHITも武器庫へ
        refreshRunButtons()
    }

    /** spray を開始する共通導線（手動 spray と効果ベース再適応の自動再探索から使う）。 */
    private fun startSpray(targets: List<TargetComponent>, recipes: List<RecipeType>, label: String): Boolean {
        if (scanEngine.scanning) return false
        // この走査のログは、SPRAY を押した時点の言語で固定する。
        val t = LogText(this, LogText.currentLocale(this))
        runLogText = t
        if (!CrashDetectorService.isRunning) {
            appendLog(t.get(R.string.log_need_a11y_spray))
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return false
        }
        // 対象選択は必須（全アプリ無差別 spray は廃止）。
        if (targets.isEmpty()) {
            appendLog(t.get(R.string.log_select_target))
            return false
        }
        if (recipes.isEmpty()) {
            appendLog(t.get(R.string.log_select_recipe))
            return false
        }

        val appCount = targets.map { it.packageName }.distinct().size
        val a = targets.count { it.kind == ComponentKind.ACTIVITY }
        val s = targets.count { it.kind == ComponentKind.SERVICE }
        val r = targets.count { it.kind == ComponentKind.RECEIVER }
        val p = targets.count { it.kind == ComponentKind.PROVIDER }
        appendLog(t.runHeader())
        appendLog(t.get(R.string.log_spray_start, label, appCount, a, s, r, p, recipes.size))
        if (s + r + p > 0 && !CrashWatcher.hasDumpPermission(this)) {
            appendLog(t.get(R.string.log_dump_warning))
        }
        btnScan.text = getString(R.string.btn_scan_stop)
        refreshRunButtons()
        setLogExpanded(true)
        // SPRAY も FGS にして通知バーから中断できるようにする（画面を離れても継続）。
        AttackControl.setStopListener {
            mainHandler.post { if (scanEngine.scanning) stopScan() }
        }
        startFgs(AttackForegroundService.MODE_SPRAY, t)
        scanEngine.start(targets, recipes, t)
        refreshRunButtons()
        return true
    }

    private fun toggleBurst() {
        if (scanEngine.bursting) {
            stopBurst()
            return
        }

        // BURST のログも開始時点の言語で固定する。
        val t = LogText(this, LogText.currentLocale(this))
        runLogText = t

        if (lastScanHits.isEmpty()) {
            // 今回の spray が無くても、武器庫に選択対象の HIT があれば再利用する（バージョンは問わない）。
            val pkgs = selectedComponents().map { it.packageName }.distinct()
            if (pkgs.isEmpty()) {
                appendLog(t.get(R.string.log_burst_need_target))
                return
            }
            val stored = pkgs.flatMap { hitStore.storedFor(it) }
            if (stored.isEmpty()) {
                appendLog(t.get(R.string.log_burst_no_hits))
                return
            }
            lastScanHits = HitStore.asResults(stored)
            appendLog(t.get(R.string.log_burst_loaded, lastScanHits.size))
        }

        if (!CrashDetectorService.isRunning) {
            appendLog(t.get(R.string.log_need_a11y_burst))
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        val hitApps = lastScanHits.map { it.packageName }.distinct().size
        burstCycle = 0
        appendLog(t.runHeader())
        appendLog(t.get(R.string.log_burst_start, lastScanHits.size, hitApps))
        btnBurst.text = getString(R.string.btn_burst_stop)
        setLogExpanded(true)
        startFgs(AttackForegroundService.MODE_BURST, t)
        AttackControl.setStopListener {
            mainHandler.post { if (scanEngine.bursting) stopBurst() }
        }
        scanEngine.startBurst(lastScanHits, t)
        refreshRunButtons()
    }

    private fun stopBurst() {
        AttackControl.setStopListener(null)
        scanEngine.stop()
        stopFgs()
        btnBurst.text = getString(R.string.btn_burst)
        appendLog(runText().get(R.string.log_burst_stop))
        refreshRunButtons()
    }

    private fun startFgs(mode: String, text: LogText) {
        val intent = Intent(this, AttackForegroundService::class.java)
            .putExtra(AttackForegroundService.EXTRA_MODE, mode)
            .putExtra(AttackForegroundService.EXTRA_LANG, text.locale.toLanguageTag())
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

    /**
     * ヘッダ右上の言語トグル（JP / EN のスライド式セグメント）。
     * AppCompat の per-app locale を切り替える。Activity は再生成されるため、
     * ログ・検索語・選択状態は onSaveInstanceState / cachedApps で引き継ぐ。
     */
    private fun setupLanguageToggle() {
        val ja = currentLanguage().startsWith("ja")
        applyLangSelection(ja, animate = false)
        langJa.setOnClickListener { switchLanguage("ja") }
        langEn.setOnClickListener { switchLanguage("en") }
    }

    private fun currentLanguage(): String =
        AppCompatDelegate.getApplicationLocales()[0]?.language
            ?: resources.configuration.locales[0].language

    private fun switchLanguage(tag: String) {
        if (langChanging || currentLanguage().startsWith(tag)) return
        langChanging = true
        // 先にインジケータを滑らせてから再生成する（切替の手応えを出す）。
        applyLangSelection(tag == "ja", animate = true)
        mainHandler.postDelayed({
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        }, 140)
    }

    private fun applyLangSelection(ja: Boolean, animate: Boolean) {
        langJa.isSelected = ja
        langEn.isSelected = !ja
        langJa.setTextColor(getColor(if (ja) R.color.on_accent else R.color.on_surface_dim))
        langEn.setTextColor(getColor(if (!ja) R.color.on_accent else R.color.on_surface_dim))
        moveLangIndicator(ja, animate)
    }

    private fun moveLangIndicator(ja: Boolean, animate: Boolean) {
        val indicator = langIndicator
        if (indicator.width == 0) {
            indicator.post { moveLangIndicator(ja, animate) }
            return
        }
        indicator.visibility = View.VISIBLE
        val target = if (ja) 0f else (langEn.left - langJa.left).toFloat()
        if (animate) {
            indicator.animate().translationX(target).setDuration(150).start()
        } else {
            indicator.translationX = target
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_LOG, synchronized(logBuffer) { logBuffer.joinToString("\n") })
        outState.putBoolean(KEY_LOG_EXPANDED, logExpanded)
        outState.putString(KEY_SEARCH, searchBox.text?.toString().orEmpty())
        outState.putStringArrayList(
            KEY_SELECTED,
            ArrayList(allApps.flatMap { it.components }.filter { it.selected }.map { componentKey(it) }),
        )
        outState.putStringArrayList(
            KEY_EXPANDED_APPS,
            ArrayList(allApps.filter { it.expanded }.map { it.packageName }),
        )
    }

    private fun restoreUiState(state: Bundle?) {
        if (state == null) return
        val savedLog = state.getString(KEY_LOG)
        if (!savedLog.isNullOrEmpty()) {
            synchronized(logBuffer) {
                logBuffer.clear()
                savedLog.split('\n').takeLast(MAX_LOG_LINES).forEach { logBuffer.addLast(it) }
            }
            logView.text = savedLog
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
        logExpanded = state.getBoolean(KEY_LOG_EXPANDED, false)
        setLogExpanded(logExpanded)
        pendingSelectedKeys = state.getStringArrayList(KEY_SELECTED)?.toSet()
        pendingExpandedPkgs = state.getStringArrayList(KEY_EXPANDED_APPS)?.toSet()
        state.getString(KEY_SEARCH)?.takeIf { it.isNotEmpty() }?.let { searchBox.setText(it) }
    }

    /** 再列挙後に、言語切替前の選択・展開状態を戻す。 */
    private fun restoreSelectionState() {
        val selected = pendingSelectedKeys
        val expanded = pendingExpandedPkgs
        if (selected == null && expanded == null) return
        allApps.forEach { app ->
            app.expanded = app.packageName in expanded.orEmpty()
            app.components.forEach { c -> c.selected = componentKey(c) in selected.orEmpty() }
        }
        pendingSelectedKeys = null
        pendingExpandedPkgs = null
    }

    private fun componentKey(c: TargetComponent): String = "${c.packageName}/${c.className}"

    private fun copyResults() {
        val text = scanEngine.exportResults()
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("LoneFuzz Results", text))
        Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        a11yManager.addAccessibilityStateChangeListener(a11yStateListener)
        updateDetectorStatus()
        updateDumpStatus()
        refreshRunButtons()
        // a11y サービスは onResume 直後に接続完了することがあり、初回表示が
        // 「検知 OFF」のまま残る。少し遅らせて再確認する。
        mainHandler.postDelayed({
            updateDetectorStatus()
            updateDumpStatus()
        }, 700)

        if (scanEngine.scanning) {
            scanEngine.onResumed()
        }
    }

    override fun onPause() {
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
                text = getString(type.labelRes)
                isCheckable = true
                isChecked = type.defaultOn
                isCheckedIconVisible = false
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
    private fun refreshRunButtons() {
        btnBurst.isEnabled = when {
            scanEngine.bursting -> true // 停止ボタンとして押せる
            scanEngine.scanning -> false // spray 中は開始不可
            else -> canBurst()
        }
        // SHOT は「1コンポーネント × 1レシピ」のときだけ。走査中も押せない。
        val shotReady = selectedComponents().size == 1 && selectedRecipes().size == 1
        btnShot.isEnabled = !scanEngine.scanning && shotReady
    }

    private fun enumerate() {
        appendLog(getString(R.string.log_enumerating))
        Thread {
            val apps = ComponentEnumerator(packageManager).enumerate(excludePackage = packageName)
            mainHandler.post {
                allApps = apps
                cachedApps = apps
                restoreSelectionState()
                applyFilter()
                appendLog(getString(R.string.log_enumerated, apps.size))
                // アンインストール済みを掃除し、残っている武器庫を通知。
                hitStore.pruneTo(apps.map { it.packageName }.toSet())
                val stored = hitStore.count()
                if (stored > 0) {
                    appendLog(getString(R.string.log_armory_count, stored))
                }
                refreshRunButtons()
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

    // ---- AttackEngine.Listener ----

    override fun onLog(line: String) = appendLog(line)

    private fun appendLog(line: String) {
        val generation: Int
        synchronized(logBuffer) {
            logBuffer.addLast(line)
            while (logBuffer.size > MAX_LOG_LINES) logBuffer.removeFirst()
            if (flushPending) return
            flushPending = true
            generation = logGeneration
        }
        mainHandler.post {
            val text: String
            synchronized(logBuffer) {
                if (generation != logGeneration) {
                    // クリア後に古い flush が走っても、新しいログを消さない。
                    flushPending = false
                    return@post
                }
                flushPending = false
                text = logBuffer.joinToString("\n")
            }
            logView.text = text
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /** ログ表示だけを消す（走査結果や武器庫はそのまま）。 */
    private fun clearLog() {
        synchronized(logBuffer) {
            logGeneration++
            logBuffer.clear()
            flushPending = false
        }
        logView.text = getString(R.string.log_cleared)
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        AttackControl.setStopListener(null)
        scanEngine.stop()
        stopFgs()
        super.onDestroy()
    }

    companion object {
        private const val MAX_LOG_LINES = 300
        private const val REQ_NOTIFICATIONS = 1001

        private const val KEY_LOG = "ui_log"
        private const val KEY_LOG_EXPANDED = "ui_log_expanded"
        private const val KEY_SEARCH = "ui_search"
        private const val KEY_SELECTED = "ui_selected"
        private const val KEY_EXPANDED_APPS = "ui_expanded_apps"

        /** 言語切替で Activity を再生成しても再列挙しないためのプロセス内キャッシュ。 */
        private var cachedApps: List<TargetApp>? = null
    }
}
