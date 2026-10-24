# LoneFuzz

**English** | [日本語](README.ja.md)

LoneFuzz is an **on-device** tool that reproducibly demonstrates **local DoS
(crash / ANR)** by sending crafted Intents to the **exported components** of Android
apps (Activity / Service / BroadcastReceiver / ContentProvider) that other apps are
allowed to launch or call, exploiting missing input validation.

No PC, emulator, or root required. A single sideloaded APK takes you through
"enumerate → explore → detect → burst" entirely on the device.

> ## ⚠️
> - Limit testing to **devices and apps you own or manage**, or targets for which you have **explicit written authorization**.
> - Unauthorized use against third parties may violate the law. You are solely responsible for your use.

---

## How it works

### 1. Enumeration

It scans the apps installed on the device and enumerates the components that other apps
can call (the exported ones). Component names and kinds, along with the app version, are
read directly on the device, so no external static analysis is needed.

Because Android 11 and later restricts access to information about other apps, it
declares the "query all packages" permission for enumeration and explicit launching
(granted automatically when sideloaded).

### 2. Payloads

A collection of input "shapes" that tend to trigger unhandled exceptions. It targets
places where an exported component implicitly assumes "the caller will always supply
correct input (extras / data)."

| Recipe | What it sends | Exception / effect induced |
|---|---|---|
| Empty Intent | Fires a bare Intent with no extras | NullPointerException (where a required key's value is used directly) |
| Type mismatch | Puts a string into keys likely expecting numbers, an array into keys likely expecting strings | ClassCastException, etc. |
| Huge int / negative | Puts extreme values such as `Int.MIN_VALUE` into common keys, `Int.MAX_VALUE` into `count`/`size`, `-1` into `length` | ArrayIndexOutOfBounds / broken size arithmetic |
| TooLarge (off by default, heavy) | Puts a 2 MB byte array under the `payload` key (exceeds the Binder transaction limit of ≈1 MB) | TransactionTooLargeException |
| Invalid URI | Puts a nonexistent content:// / file:// URI into `data` | FileNotFoundException, etc. |
| BadParcelable | Puts a custom Parcelable absent from the target process into every common key (fires the moment the target reads extras) | BadParcelableException |
| FGS 5-second rule | Starts a Service with `startForegroundService` and no extras (Service only) | `RemoteServiceException` about 30 s later if `startForeground()` isn't called within 5 seconds (no crash dialog) |

> String/number swaps are planted bidirectionally so that reading either way yields a
> type mismatch. The common keys are a list of generic extras names (id, url, uri, data,
> key, value, name, title, type, index, position, count, token, json, payload, extra, message).
> BadParcelable only fires once the target reads extras, so its hit rate is implementation-dependent.

The firing method depends on the component kind: Activity uses `startActivity`, Receiver
uses `sendBroadcast`, Service uses `startService` / `startForegroundService`, and Provider
uses `query` via `ContentResolver` from an isolated `:probe` process (a provider crash takes
its client process down too, so the probe is the expendable part).

### 3. Detection

You need to identify **which component crashed on which payload**. Because crashes happen
asynchronously in the target process, LoneFuzz determines the "effective payload" from
**multiple signals correlated by fire record and time**.

| Signal | Mechanism | Target components | Main weakness |
|---|---|---|---|
| **Crash/ANR dialog observation** | The accessibility service observes the system's "has stopped" / "isn't responding" dialogs and matches them against the fire records from the last 8 seconds | All kinds (anything fired) | Wording varies by OS, language, and vendor. "Silent crashes" aren't caught |
| **Rapid return** (last resort) | If the target Activity disappears within 1.5 seconds and returns to our app, it's a suspected effective payload | Activity only | False-positives on Activities that `finish()` immediately or return right after a permission prompt |
| **UsageStats disappearance** | Using usage access, confirms from history that the target came to the foreground after firing and then disappeared without our interaction | Activity only (those that came to the foreground). Catches "silent crashes" in the 1.5–3 s band | Indistinguishable from an immediate `finish()`. History appears seconds late. Death right after launch leaves no record |
| **Process exit reason** (optional) | After firing, queries the OS's exit history and observes termination due to crash (`REASON_CRASH` / `REASON_CRASH_NATIVE` / `REASON_INITIALIZATION_FAILURE`) | All kinds (anything fired) | Querying other packages requires the DUMP permission (granted once via `adb`). API 30+ |

These are combined, and each result is given a **confidence**. Observing a crash death
(crash/ANR dialog, or the optional exit reason) counts as confirmed; rapid return and
UsageStats disappearance share the same ambiguity — "disappeared without user
interaction" — so they are treated separately from confirmed judgments.

To suppress false positives, **a lower-confidence signal is used only when higher ones are
unavailable**. With DUMP, the exit reason is ground truth, so a disappearance that cannot be
confirmed as a crash is not reported as a suspect. With usage access, UsageStats is preferred;
rapid return is used only when neither oracle is available.

| Confidence | Basis | Required permission |
|---|---|---|
| **CONFIRMED** | Observed the target process's crash death after firing (crash/ANR dialog, or the optional exit reason) | Dialog: accessibility (optional) / Exit reason: DUMP (optional) |
| **SUSPECTED** | Only when DUMP is unavailable: unexplained disappearance via UsageStats (with usage access), or rapid return as a last resort (without it) | UsageStats: usage access / Rapid return: none |
| NONE | No signal matched (not applicable) | — |

Results can be exported as "app name / version / component / recipe / confidence".

For non-Activity components the check is purely time-based: right after firing, and again
after a short delay, LoneFuzz asks the exit oracle whether the target died since the shot.
Android rejects `startService` to a background target (`BackgroundServiceStartNotAllowedException`);
LoneFuzz retries after `bindService`, which brings the target up far enough for the start to
go through. An FGS violation only surfaces as a crash about 30 seconds later, so that recipe
waits 31 seconds. Provider support confirms **process death** only: an exception thrown back
to the caller is a normal rejection, not a hit.

### 4. Burst

It keeps re-sending only the effective payloads confirmed during exploration, continuously
taking the target down. It runs persistently as a foreground service with an ongoing
notification. Stopping is done from the ongoing notification.

### Armory (persisting and self-adapting effective payloads)

Confirmed effective payloads are stored on the device keyed by "package / component /
recipe", so after a restart you can **skip exploration (spray) and go straight to keeping
the target down**. Suspected hits (SUSPECTED) are not stored, since they can include false
positives. Uninstalling the target removes them automatically.

Self-adaptation is driven by **"whether it still works (effect)" rather than "version
number"**. When a previously effective payload fails repeatedly during the keep-down mode
(possibly invalidated by a spec change), it is deactivated from the armory and logged
(the burst is not stopped; re-exploration is done by a manual spray). The version is only
recorded as "the last version at which it was confirmed effective" and is not used for the
deactivation decision.

---

## Permission model

**Firing an attack (sending an Intent) itself requires no permissions.** Permissions are
used for "detection confidence" and "automation".

- **Query all packages**: Needed to enumerate and explicitly launch other apps' components (granted automatically when sideloaded).
- **Accessibility**: Used to detect crash/ANR dialogs and for automated sweeping (optional; without it you can still verify visually by hand).
- **Usage access**: An auxiliary signal to catch silent crashes (optional).
- **Foreground service / notifications**: Used for the keep-down mode (persistent execution) and the ongoing notification.
- **Kill background processes / reorder tasks**: Auxiliary to cleanup and sweeping.
- **DUMP** (granted via adb): Queries the target process's exit reason to confirm a crash death without relying on dialogs (optional).

---

## Operation modes

| Mode | Purpose | Permissions |
|---|---|---|
| **SHOT** (single) | Fires one pass over the selected targets to visually confirm a crash | Zero permissions |
| **SPRAY** (explore) | Sweeps exported Activities × recipes of the selected apps and records effective payloads (selecting targets is required) | Accessibility (automation/detection) |
| **BURST** | Keeps re-sending only the confirmed effective payloads in a loop, continuously taking the target down (reusable from the armory without spray) | Accessibility + foreground service |

---

## Build & run

1. Open the project in Android Studio (AGP 9.3.3 / Gradle 9.5 / compileSdk 37 / minSdk 24).
2. Sideload to a real device (`Run` or `adb install`).
3. Enumeration runs on first launch. Select the target app and recipes, then fire.
4. To use fully automatic exploration / keep-down mode, enable "Settings → Accessibility → LoneFuzz" (optional).
5. To strengthen detection to the confirmed level, grant DUMP (optional; also confirms the first crash):
   `adb shell pm grant io.github.azaberrypi.lonefuzz android.permission.DUMP`
   When not granted, a red pill is shown on the app screen.

> Note: During debugging, using `adb logcat` together makes confirmation easier.

---

## Known limitations

- Crashes happen asynchronously in the target process, so our calls normally don't throw.
  A "send succeeded" line in the log does not mean "crashed" (whether it crashed is decided by the detection above).
- On Android 12 and later, crash notifications are rate-limited, so dialog observation
  alone can miss confirmation. When DUMP is granted, the exit-reason query compensates,
  confirming first crashes and silent crashes as well.
- Continuous bursting of Activities is affected by Android's background launch restrictions.
- BadParcelable doesn't fire until the target reads extras (hit rate is implementation-dependent).
- Service / Receiver / Provider are supported, but non-Activity hits can only be confirmed via the exit oracle (DUMP) or a crash dialog. A provider that merely throws back to the caller is not counted.

---

## License

LoneFuzz is released under the MIT License. See [LICENSE](LICENSE).

Third-party components bundled in the app are listed in [NOTICE](NOTICE), together with the
full Apache License 2.0 text. The same notices are bundled inside the APK as
`assets/LICENSE.txt` and `assets/NOTICE.txt`.
