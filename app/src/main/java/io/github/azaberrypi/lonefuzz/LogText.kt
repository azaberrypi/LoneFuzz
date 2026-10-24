package io.github.azaberrypi.lonefuzz

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

/**
 * 走査を開始した時点の言語を固定してログ文字列を解決する。
 * 走査中に UI 言語が変わっても、その走査のログは開始時の言語で出力する。
 */
class LogText(context: Context, val locale: Locale) {

    private val ctx: Context = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        },
    )

    fun get(id: Int, vararg args: Any): String =
        if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)

    companion object {
        /** 現在アプリに適用されている言語（未設定なら端末の言語）。 */
        fun currentLocale(context: Context): Locale =
            AppCompatDelegate.getApplicationLocales()[0]
                ?: context.resources.configuration.locales[0]
    }
}
