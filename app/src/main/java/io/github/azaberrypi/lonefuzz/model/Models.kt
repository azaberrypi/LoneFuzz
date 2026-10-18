package io.github.azaberrypi.lonefuzz.model

/** exported コンポーネントの種別。 */
enum class ComponentKind { ACTIVITY, SERVICE, RECEIVER, PROVIDER }

/**
 * 攻撃対象となる 1 個の exported コンポーネント。
 * Provider の場合のみ [authority] を持つ（Intent では発火できず ContentResolver 経由のため）。
 */
data class TargetComponent(
    val packageName: String,
    val className: String,
    val kind: ComponentKind,
    val authority: String? = null,
    val appLabel: String = "",
    /** "1.2.3 (456)" 形式。再現性レポート/再適応判定用（空なら未取得）。 */
    val appVersion: String = "",
    var selected: Boolean = false,
) {
    /** "com.foo.Bar" → "Bar"。表示用。 */
    fun shortName(): String = className.substringAfterLast('.')
}

/** 1 アプリ分の列挙結果。[components] は exported コンポーネントのみ。 */
data class TargetApp(
    val packageName: String,
    val label: String,
    val components: List<TargetComponent>,
    var expanded: Boolean = false,
) {
    val selectedCount: Int get() = components.count { it.selected }
    fun countOf(kind: ComponentKind): Int = components.count { it.kind == kind }

    /** "A:3 S:1 R:2 P:0" の形のサマリ。 */
    fun summary(): String = buildString {
        append("A:").append(countOf(ComponentKind.ACTIVITY))
        append(" S:").append(countOf(ComponentKind.SERVICE))
        append(" R:").append(countOf(ComponentKind.RECEIVER))
        append(" P:").append(countOf(ComponentKind.PROVIDER))
    }
}
