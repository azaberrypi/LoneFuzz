package io.github.azaberrypi.lonefuzz

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 確定/疑いの HIT を端末内に永続化する簡易ストア（武器庫）。
 *
 * キーは (packageName, className, recipe)。**バージョンでは無効化しない**。
 * - アプリのバージョンは「最後に有効と確認した版」としてメタ情報で保持するだけ（レポート再現性用）。
 * - 保存済み HIT は版をまたいで再利用し、撃って効かなくなったら [remove] で無効化 → 再探索する
 *   （バージョン変化の追跡ではなく「効果」を自己適応の判断基準にする）。
 * - **アンインストール済み**のパッケージは [pruneTo] で掃除する。
 *
 * メモリ上の [ScanEngine.Result] と相互変換して、再起動後も spray 無しで BURST に使える。
 */
class HitStore(context: Context) {

    data class Entry(
        val packageName: String,
        val appLabel: String,
        val appVersion: String,
        val className: String,
        val recipeName: String,
        val confidence: String,
    )

    private val prefs = context.applicationContext
        .getSharedPreferences("lonefuzz_hits", Context.MODE_PRIVATE)

    fun load(): List<Entry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    o.getString("pkg"), o.optString("label"), o.optString("ver"),
                    o.getString("cls"), o.getString("recipe"), o.optString("conf", "SUSPECTED"),
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun save(entries: List<Entry>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject().apply {
                    put("pkg", e.packageName); put("label", e.appLabel); put("ver", e.appVersion)
                    put("cls", e.className); put("recipe", e.recipeName); put("conf", e.confidence)
                },
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    /** spray/BURST の HIT を追記保存（同一キーは上書き。CONFIRMED は SUSPECTED に優先）。 */
    fun record(hits: List<ScanEngine.Result>) {
        val incoming = hits.filter { it.hit }
        if (incoming.isEmpty()) return
        val map = LinkedHashMap<String, Entry>()
        for (e in load()) map[key(e.packageName, e.className, e.recipeName)] = e
        for (h in incoming) {
            val k = key(h.packageName, h.className, h.recipeType.name)
            val existing = map[k]
            val conf = h.confidence.name
            // 既存が CONFIRMED のとき、SUSPECTED で上書きしない。
            if (existing?.confidence == "CONFIRMED" && conf != "CONFIRMED") continue
            // 版はメタ情報として最新値に更新する（版での無効化はしない）。
            map[k] = Entry(h.packageName, h.appLabel, h.appVersion, h.className, h.recipeType.name, conf)
        }
        save(map.values.toList())
    }

    /** 指定パッケージの保存済み HIT（バージョン不問）。 */
    fun storedFor(packageName: String): List<Entry> =
        load().filter { it.packageName == packageName }

    /** 効かなくなった HIT を武器庫から取り除く（効果ベースの再適応）。 */
    fun remove(packageName: String, className: String, recipe: String) {
        val all = load()
        val kept = all.filterNot {
            it.packageName == packageName && it.className == className && it.recipeName == recipe
        }
        if (kept.size != all.size) save(kept)
    }

    /** インストール済みに無いパッケージのエントリを削除（アンインストール掃除）。 */
    fun pruneTo(installedPackages: Set<String>) {
        val all = load()
        val kept = all.filter { it.packageName in installedPackages }
        if (kept.size != all.size) save(kept)
    }

    fun count(): Int = load().size

    private fun key(pkg: String, cls: String, recipe: String) = "$pkg|$cls|$recipe"

    companion object {
        private const val KEY = "hits_v1"

        /** 保存エントリを BURST 用の [ScanEngine.Result] に変換（不明なレシピ名は除外）。 */
        fun asResults(entries: List<Entry>): List<ScanEngine.Result> = entries.mapNotNull { e ->
            val recipe = runCatching { RecipeType.valueOf(e.recipeName) }.getOrNull() ?: return@mapNotNull null
            val confidence = runCatching { ScanEngine.HitConfidence.valueOf(e.confidence) }
                .getOrDefault(ScanEngine.HitConfidence.SUSPECTED)
            ScanEngine.Result(
                appLabel = e.appLabel,
                packageName = e.packageName,
                component = e.className.substringAfterLast('.'),
                className = e.className,
                appVersion = e.appVersion,
                recipeType = recipe,
                recipe = recipe.label,
                hit = true,
                confidence = confidence,
            )
        }
    }
}
