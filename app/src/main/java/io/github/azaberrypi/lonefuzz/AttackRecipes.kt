package io.github.azaberrypi.lonefuzz

import android.content.Intent
import android.net.Uri
import android.os.Parcel
import android.os.Parcelable

/**
 * アタックレシピ集。exported コンポーネントに送ると未処理例外（クラッシュ/ANR）を
 * 誘発しやすい Intent ペイロードの「型」をまとめた総当たり集。
 */
enum class RecipeType(val label: String, val defaultOn: Boolean = true) {
    /** extras 無しで発火。期待する extra を前提にした getXxx().method() で NPE を誘発。 */
    EMPTY("空Intent", true),

    /** 期待される型と違う extra（文字列 ↔ 数値 等）→ ClassCastException / 解釈失敗。 */
    TYPE_MISMATCH("型不一致", false),

    /** 極端な int/long（MIN/MAX・負値）→ ArrayIndexOutOfBounds / サイズ計算破綻。 */
    HUGE_NUMERIC("巨大int/負値", false),

    /** 大きすぎる extra（巨大 byte[]）→ Binder の TransactionTooLargeException。 */
    TRANSACTION_TOO_LARGE("TooLarge", false),

    /** null/存在しない URI → Provider の FileNotFoundException 等が未処理でクラッシュ。 */
    BAD_URI("不正URI", false),

    /** 対象プロセスに存在しない Parcelable クラス → unparcel 時に BadParcelableException。 */
    BAD_PARCELABLE("BadParcelable", false),

    /** Service を startForegroundService で起動 → 5秒以内に startForeground されないと落ちる。 */
    FOREGROUND_SERVICE_5S("FGS 5秒", false),
    ;
}

object AttackRecipes {

    /** アプリが extra を読むときに使いがちなキー群。これらに payload を仕込んで的中率を上げる。 */
    private val COMMON_KEYS = listOf(
        "id", "url", "uri", "data", "key", "value", "name", "title", "type",
        "index", "position", "count", "token", "json", "payload", "extra", "message",
    )

    /**
     * [recipe] を [intent] に適用する（extras / data の付与）。
     * FOREGROUND_SERVICE_5S は発火方法側で扱うため、ここでは extras を付けない。
     */
    fun applyPayload(intent: Intent, recipe: RecipeType) {
        when (recipe) {
            RecipeType.EMPTY, RecipeType.FOREGROUND_SERVICE_5S -> {
                // 何も付けない（EMPTY は素の発火、FGS は発火方法で表現）。
            }

            RecipeType.TYPE_MISMATCH -> {
                // 数値を期待しがちなキーには文字列、文字列を期待しがちなキーには配列などを入れる。
                for (k in COMMON_KEYS) intent.putExtra(k, "lonefuzz_not_a_number")
                intent.putExtra("id", "NaN")
                intent.putExtra("index", booleanArrayOf(true, false))
                intent.putExtra("count", charArrayOf('x', 'y'))
            }

            RecipeType.HUGE_NUMERIC -> {
                for (k in COMMON_KEYS) intent.putExtra(k, Int.MIN_VALUE)
                intent.putExtra("count", Int.MAX_VALUE)
                intent.putExtra("size", Int.MAX_VALUE)
                intent.putExtra("length", -1)
                intent.putExtra("index", Int.MAX_VALUE)
                intent.putExtra("offset", Long.MIN_VALUE)
            }

            RecipeType.TRANSACTION_TOO_LARGE -> {
                // 2MB。Binder のトランザクション上限(約1MB)を超え、呼び出し側で例外になることも。
                intent.putExtra("payload", ByteArray(2 * 1024 * 1024))
            }

            RecipeType.BAD_URI -> {
                intent.data = Uri.parse("content://invalid.authority.lonefuzz/nonexistent")
                intent.putExtra("uri", "content://invalid.authority.lonefuzz/nonexistent")
                intent.putExtra("url", "file:///nonexistent/lonefuzz")
            }

            RecipeType.BAD_PARCELABLE -> {
                // 対象プロセスに BadParcelable クラスが無いため、extras の unparcel で
                // BadParcelableException (ClassNotFound) を誘発する。
                for (k in COMMON_KEYS) intent.putExtra(k, BadParcelable())
            }
        }
    }

    /** Provider 攻撃用の URI（BAD_URI は存在しないパス、それ以外も不正パスを投げる）。 */
    fun providerUri(authority: String): Uri =
        Uri.parse("content://$authority/lonefuzz_nonexistent_${System.nanoTime()}")
}

/**
 * 相手プロセスに存在しない Parcelable。相手が extras を unparcel した瞬間、
 * CREATOR をリフレクションで解決できず BadParcelableException が投げられる。
 */
class BadParcelable() : Parcelable {
    @Suppress("UNUSED_PARAMETER")
    private constructor(parcel: Parcel) : this()

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(0xCB)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<BadParcelable> {
        override fun createFromParcel(parcel: Parcel): BadParcelable = BadParcelable(parcel)
        override fun newArray(size: Int): Array<BadParcelable?> = arrayOfNulls(size)
    }
}
