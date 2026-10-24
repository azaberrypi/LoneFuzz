package io.github.azaberrypi.lonefuzz

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Provider を別プロセスから撃つためのプローブ。
 *
 * Provider のプロセスが死ぬと、その Provider に query した *クライアント側プロセス* も
 * DEPENDENCY DIED（ApplicationExitInfo reason=12）で殺される。本体プロセスから撃つと
 * LoneFuzz 自身が落ちて観測できないため、`:probe` プロセスに隔離する。
 * 本体はこのサービスの生死に関係なく、対象パッケージの終了理由を DUMP で観測する。
 */
class ProviderProbeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val authority = intent?.getStringExtra(EXTRA_AUTHORITY)
        val pkg = intent?.getStringExtra(EXTRA_PACKAGE)
        if (authority.isNullOrEmpty() || pkg.isNullOrEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // 相手がハングしてもプローブが残留しないように上限を切る。
        Handler(Looper.getMainLooper()).postDelayed({ stopSelf(startId) }, PROBE_TIMEOUT_MS)
        Thread {
            try {
                contentResolver.query(
                    AttackRecipes.providerUri(authority), null, null, null, null,
                )?.close()
            } catch (_: Throwable) {
                // 正常な拒否（FileNotFoundException 等）も、対象プロセスの死もここに来る。
                // 判定は本体側が終了理由で行う。
            } finally {
                stopSelf(startId)
            }
        }.start()
        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_AUTHORITY = "authority"
        const val EXTRA_PACKAGE = "package"
        private const val PROBE_TIMEOUT_MS = 5_000L
    }
}
