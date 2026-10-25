package io.github.azaberrypi.lonefuzz.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.github.azaberrypi.lonefuzz.R
import io.github.azaberrypi.lonefuzz.model.ComponentKind
import io.github.azaberrypi.lonefuzz.model.TargetApp
import io.github.azaberrypi.lonefuzz.model.TargetComponent

/**
 * アプリ行（展開可）と、その下の exported コンポーネント行（個別チェック）を
 * 1 本の RecyclerView にフラットに並べるアダプタ。
 *
 * - アプリ行タップ → 展開/折りたたみ
 * - アプリ行のチェックボックス → そのアプリの全コンポーネントを一括選択/解除
 * - コンポーネント行タップ → そのコンポーネントだけ選択トグル
 */
class AppListAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed interface Row {
        data class Header(val app: TargetApp) : Row
        data class Comp(val app: TargetApp, val component: TargetComponent) : Row
    }

    private val apps = mutableListOf<TargetApp>()
    private val rows = mutableListOf<Row>()
    private var confirmedHitKeys: Set<String> = emptySet()
    private var suspectedHitKeys: Set<String> = emptySet()

    /** 選択状態が変わったとき（BURST の有効/無効の再計算など）に通知する。 */
    var onSelectionChanged: (() -> Unit)? = null

    /** HIT バッジがタップされたとき（pkg|class）。 */
    var onHitBadgeClick: ((String) -> Unit)? = null

    /** このセッションの HIT 状態を反映する（確定=pkg|class の集合、疑い=同）。 */
    fun setHitKeys(confirmed: Set<String>, suspected: Set<String>) {
        if (confirmedHitKeys == confirmed && suspectedHitKeys == suspected) return
        confirmedHitKeys = confirmed.toSet()
        suspectedHitKeys = suspected.toSet()
        notifyDataSetChanged()
    }

    fun submit(list: List<TargetApp>) {
        apps.clear()
        apps.addAll(list)
        rebuild()
    }

    /** 表示中アプリの全コンポーネントを一括設定。 */
    fun setAllSelected(selected: Boolean) {
        apps.forEach { app -> app.components.forEach { it.selected = selected } }
        notifyDataSetChanged()
        onSelectionChanged?.invoke()
    }

    private fun rebuild() {
        rows.clear()
        for (app in apps) {
            rows += Row.Header(app)
            if (app.expanded) {
                app.components.forEach { rows += Row.Comp(app, it) }
            }
        }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_COMP

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(inflater.inflate(R.layout.item_app, parent, false))
        } else {
            CompVH(inflater.inflate(R.layout.item_component, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderVH).bind(row.app)
            is Row.Comp -> (holder as CompVH).bind(row.app, row.component)
        }
    }

    private inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        val check: CheckBox = view.findViewById(R.id.check)
        val label: TextView = view.findViewById(R.id.appLabel)
        val pkg: TextView = view.findViewById(R.id.appPackage)
        val summary: TextView = view.findViewById(R.id.componentSummary)
        val caret: TextView = view.findViewById(R.id.caret)

        fun bind(app: TargetApp) {
            label.text = app.label
            pkg.text = app.packageName
            summary.text = itemView.context.getString(
                R.string.app_summary, app.summary(), app.selectedCount, app.components.size,
            )
            caret.text = if (app.expanded) "▾" else "▸"
            check.setOnCheckedChangeListener(null)
            check.isChecked = app.components.isNotEmpty() && app.selectedCount == app.components.size
            check.setOnCheckedChangeListener { _, isChecked ->
                app.components.forEach { it.selected = isChecked }
                rebuild()
                onSelectionChanged?.invoke()
            }
            itemView.setOnClickListener {
                app.expanded = !app.expanded
                rebuild()
            }
        }
    }

    private inner class CompVH(view: View) : RecyclerView.ViewHolder(view) {
        val check: CheckBox = view.findViewById(R.id.check)
        val badge: TextView = view.findViewById(R.id.kindBadge)
        val name: TextView = view.findViewById(R.id.compName)
        val hit: TextView = view.findViewById(R.id.hitBadge)

        fun bind(app: TargetApp, component: TargetComponent) {
            badge.text = when (component.kind) {
                ComponentKind.ACTIVITY -> "A"
                ComponentKind.SERVICE -> "S"
                ComponentKind.RECEIVER -> "R"
                ComponentKind.PROVIDER -> "P"
            }
            name.text = component.shortName()
            val key = "${component.packageName}|${component.className}"
            when {
                key in confirmedHitKeys -> {
                    hit.text = "HIT"
                    hit.setTextColor(itemView.context.getColor(R.color.neon_pink))
                    hit.visibility = View.VISIBLE
                }
                key in suspectedHitKeys -> {
                    hit.text = "HIT?"
                    hit.setTextColor(itemView.context.getColor(R.color.hit_suspected))
                    hit.visibility = View.VISIBLE
                }
                else -> hit.visibility = View.GONE
            }
            hit.isClickable = true
            hit.setOnClickListener { onHitBadgeClick?.invoke(key) }
            check.isChecked = component.selected
            itemView.setOnClickListener {
                component.selected = !component.selected
                check.isChecked = component.selected
                // 親ヘッダのチェック状態/選択数表示を更新。
                notifyItemChanged(rows.indexOfFirst { it is Row.Header && it.app === app })
                onSelectionChanged?.invoke()
            }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_COMP = 1
    }
}
