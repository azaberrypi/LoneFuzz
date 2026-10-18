package io.github.azaberrypi.lonefuzz

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import io.github.azaberrypi.lonefuzz.model.ComponentKind
import io.github.azaberrypi.lonefuzz.model.TargetApp
import io.github.azaberrypi.lonefuzz.model.TargetComponent

/**
 * PackageManager でインストール済みアプリを列挙し、exported コンポーネントを抽出する。
 * 外部の静的解析ツールを使わず、オンデバイスで完結する。
 *
 * 注意: Android 11+ では QUERY_ALL_PACKAGES が無いと他アプリがほぼ見えない。
 */
class ComponentEnumerator(private val pm: PackageManager) {

    @Suppress("DEPRECATION")
    fun enumerate(excludePackage: String? = null): List<TargetApp> {
        val flags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS

        val packages: List<PackageInfo> = try {
            pm.getInstalledPackages(flags)
        } catch (t: Throwable) {
            emptyList()
        }

        val result = ArrayList<TargetApp>()
        for (pkg in packages) {
            if (pkg.packageName == excludePackage) continue

            val label = try {
                pkg.applicationInfo?.let { pm.getApplicationLabel(it).toString() } ?: pkg.packageName
            } catch (t: Throwable) {
                pkg.packageName
            }

            val versionName = pkg.versionName ?: "?"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkg.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkg.versionCode.toLong()
            }
            // 再現性レポート/再適応判定で使う版識別子。
            val versionTag = "$versionName ($versionCode)"

            val components = ArrayList<TargetComponent>()

            pkg.activities?.forEach { info ->
                if (info.exported) {
                    components += TargetComponent(info.packageName, info.name, ComponentKind.ACTIVITY, appLabel = label, appVersion = versionTag)
                }
            }
            pkg.services?.forEach { info ->
                if (info.exported) {
                    components += TargetComponent(info.packageName, info.name, ComponentKind.SERVICE, appLabel = label, appVersion = versionTag)
                }
            }
            pkg.receivers?.forEach { info ->
                if (info.exported) {
                    components += TargetComponent(info.packageName, info.name, ComponentKind.RECEIVER, appLabel = label, appVersion = versionTag)
                }
            }
            pkg.providers?.forEach { info ->
                if (info.exported) {
                    val authority = info.authority?.split(";")?.firstOrNull()?.trim()
                    components += TargetComponent(
                        info.packageName,
                        info.name,
                        ComponentKind.PROVIDER,
                        authority = authority,
                        appLabel = label,
                        appVersion = versionTag,
                    )
                }
            }

            if (components.isEmpty()) continue
            components.sortWith(compareBy<TargetComponent> { it.kind.ordinal }.thenBy { it.shortName().lowercase() })

            result += TargetApp(pkg.packageName, label, components)
        }

        // exported コンポーネントが多い順 → 名前順。
        result.sortWith(
            compareByDescending<TargetApp> { it.components.size }.thenBy { it.label.lowercase() },
        )
        return result
    }
}
