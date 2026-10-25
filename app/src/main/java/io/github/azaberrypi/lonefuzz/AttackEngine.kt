package io.github.azaberrypi.lonefuzz

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.azaberrypi.lonefuzz.model.ComponentKind
import io.github.azaberrypi.lonefuzz.model.TargetComponent

/**
 * SHOT: 選択した1コンポーネント × 1レシピへ1回だけIntentを送る。
 * 検知はせず、送った内容のログだけを [Listener] に流す。
 *
 * 注意: クラッシュは「相手プロセス」で非同期に起きるため、こちらの startXxx() は
 * 多くの場合に例外を投げない。呼び出し側で捕捉できる例外
 * （TransactionTooLarge / Security / ActivityNotFound 等）だけがローカルに出る。
 */
class AttackEngine(context: Context) {

    interface Listener {
        fun onLog(line: String)
    }

    private val appContext = context.applicationContext

    @Volatile private var shotRunning = false

    /** SHOT 開始時の言語。ログはこの言語で固定する。 */
    @Volatile private var logText: LogText? = null

    private fun text(id: Int, vararg args: Any): String {
        val lt = logText ?: LogText(appContext, LogText.currentLocale(appContext)).also { logText = it }
        return lt.get(id, *args)
    }

    private fun header(): String {
        val lt = logText ?: LogText(appContext, LogText.currentLocale(appContext)).also { logText = it }
        return lt.runHeader()
    }

    /**
     * SHOT: 選択した1コンポーネント × 1レシピに1回だけ送る。
     * 検知はせず、送ったIntent（またはProvider URI）だけをログする。
     */
    fun fireShot(tc: TargetComponent, recipe: RecipeType, listener: Listener) {
        logText = LogText(appContext, LogText.currentLocale(appContext))
        if (shotRunning) return
        if (recipe == RecipeType.FOREGROUND_SERVICE_5S && tc.kind != ComponentKind.SERVICE) {
            listener.onLog(
                text(R.string.log_shot_not_applicable, text(recipe.labelRes), tc.kind.name),
            )
            return
        }
        shotRunning = true
        Thread {
            try {
                listener.onLog(header())
                val label = text(recipe.labelRes)
                if (tc.kind == ComponentKind.PROVIDER) {
                    val authority = tc.authority
                    if (authority.isNullOrEmpty()) {
                        listener.onLog(text(R.string.log_shot_error, "no authority", ""))
                        return@Thread
                    }
                    listener.onLog(
                        text(
                            R.string.log_shot, tc.appLabel, tc.shortName(),
                            "PRO", label, "content://$authority",
                        ),
                    )
                    try {
                        val uri = AttackRecipes.providerUri(authority)
                        appContext.contentResolver.query(uri, null, null, null, null)?.close()
                        appContext.contentResolver.insert(uri, ContentValues())
                    } catch (t: Throwable) {
                        listener.onLog(
                            text(R.string.log_shot_error, t.javaClass.simpleName, t.message.orEmpty()),
                        )
                    }
                    return@Thread
                }
                val i = baseIntent(tc)
                AttackRecipes.applyPayload(i, recipe)
                if (tc.kind == ComponentKind.ACTIVITY) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                listener.onLog(
                    text(
                        R.string.log_shot, tc.appLabel, tc.shortName(),
                        tc.kind.name.take(3), label, describeIntent(i),
                    ),
                )
                try {
                    when (tc.kind) {
                        ComponentKind.ACTIVITY -> appContext.startActivity(i)
                        ComponentKind.RECEIVER -> appContext.sendBroadcast(i)
                        ComponentKind.SERVICE -> if (recipe == RecipeType.FOREGROUND_SERVICE_5S &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ) {
                            appContext.startForegroundService(i)
                        } else {
                            appContext.startService(i)
                        }
                        ComponentKind.PROVIDER -> {}
                    }
                } catch (t: Throwable) {
                    listener.onLog(
                        text(R.string.log_shot_error, t.javaClass.simpleName, t.message.orEmpty()),
                    )
                }
            } finally {
                shotRunning = false
            }
        }.apply { isDaemon = true }.start()
    }

    /** ログ用に Intent の中身を短く要約する（巨大な配列はサイズだけ）。 */
    @Suppress("DEPRECATION")
    private fun describeIntent(intent: Intent): String {
        val sb = StringBuilder()
        intent.component?.let { sb.append("cmp=").append(it.flattenToShortString()) }
        intent.action?.let {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append("act=").append(it)
        }
        intent.data?.let {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append("data=").append(it.toString().take(120))
        }
        intent.type?.let {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append("type=").append(it)
        }
        if (intent.flags != 0) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append("flags=0x").append(Integer.toHexString(intent.flags))
        }
        val extras = intent.extras
        if (extras != null && !extras.isEmpty) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append("extras={")
            var n = 0
            for (key in extras.keySet()) {
                if (n >= 16) {
                    sb.append(" ...")
                    break
                }
                if (n > 0) sb.append(' ')
                n++
                val value = extras.get(key)
                val shown = when (value) {
                    is ByteArray -> "byte[${value.size}]"
                    is IntArray -> "int[${value.size}]"
                    is LongArray -> "long[${value.size}]"
                    is ShortArray -> "short[${value.size}]"
                    is BooleanArray -> "boolean[${value.size}]"
                    is FloatArray -> "float[${value.size}]"
                    is DoubleArray -> "double[${value.size}]"
                    is Array<*> -> "array[${value.size}]"
                    else -> runCatching { value.toString() }
                        .getOrElse { value?.javaClass?.simpleName ?: "null" }
                        .take(60)
                }
                sb.append(key).append('=').append(shown)
            }
            sb.append('}')
        }
        return sb.toString().ifEmpty { "(empty)" }.take(400)
    }

    private fun baseIntent(tc: TargetComponent): Intent =
        Intent().setComponent(ComponentName(tc.packageName, tc.className))

}
