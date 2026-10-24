package io.github.azaberrypi.lonefuzz

/**
 * 実行中の spray/BURST を外部から止めるための制御バス。
 *
 * ScanEngine は Activity に紐づくため、通知バーの停止アクション（BroadcastReceiver）から
 * 直接操作できない。MainActivity が走査中だけ停止コールバックを登録し、
 * レシーバはこのシングルトン経由でそれを呼ぶ。
 */
object AttackControl {

    @Volatile
    private var stopListener: (() -> Unit)? = null

    fun setStopListener(listener: (() -> Unit)?) {
        stopListener = listener
    }

    fun requestStop() {
        stopListener?.invoke()
    }
}
