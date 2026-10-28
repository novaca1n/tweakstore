package dev.novacain.tweakstore

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** What the store is doing with a tweak right now. */
sealed interface Job {
    data class Downloading(val progress: Float) : Job       // −1 — size unknown
    data object Verifying : Job
    data object Installing : Job
    data object Enabling : Job
    data object Removing : Job
    data class Failed(val reason: Failure, val detail: String = "") : Job
}

enum class Failure { NO_ROOT, NO_VECTOR, DOWNLOAD, CHECKSUM, WRONG_PACKAGE, INSTALL, VECTOR, UNINSTALL }

/**
 * Installs tweaks the way derealization patches itself — through root:
 *  1. downloads the APK, checks SHA-256 from the index and that the package name is the tweak id;
 *  2. `pm install` (streamed from the store's cache, so SELinux doesn't need to let system_server read it);
 *  3. for "xposed" tweaks — enables the module in Vector with the tweak's scope and restarts those apps
 *     (SystemUI last, after the UI says so: the phone locks when it restarts).
 * Uninstall is the reverse: disable in Vector, `pm uninstall`, restart the scope.
 */
class Installer(private val ctx: Context) {
    private val _jobs = MutableStateFlow<Map<String, Job>>(emptyMap())
    val jobs: StateFlow<Map<String, Job>> = _jobs
    private val _installed = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Installed version code per tweak id (only for ids the store knows about). */
    val installed: StateFlow<Map<String, Long>> = _installed

    private fun set(id: String, job: Job?) {
        _jobs.value = if (job == null) _jobs.value - id else _jobs.value + (id to job)
    }

    fun refresh(ids: Collection<String>) {
        val pm = ctx.packageManager
        _installed.value = ids.mapNotNull { id ->
            runCatching { id to pm.getPackageInfo(id, 0).longVersionCode }.getOrNull()
        }.toMap()
    }

    fun clearFailure(id: String) {
        if (_jobs.value[id] is Job.Failed) set(id, null)
    }

    /** derealization version code (0 — not installed). */
    fun derealization(): Long = runCatching {
        ctx.packageManager.getPackageInfo(DEREALIZATION, 0).longVersionCode
    }.getOrDefault(0L)

    fun compatible(t: Tweak): Boolean {
        if (t.minSdk > 0 && android.os.Build.VERSION.SDK_INT < t.minSdk) return false
        if (!deviceOk(t)) return false
        if (t.minDerealization > 0 && derealization() < t.minDerealization) return false
        return true
    }

    /** Device codenames are compared case-insensitively (galaga reports itself as "Galaga"). */
    fun deviceOk(t: Tweak): Boolean =
        t.devices.isEmpty() || t.devices.any { it.equals(android.os.Build.DEVICE, ignoreCase = true) }

    /** Returns true when SystemUI is in the scope and must be restarted by [restartSystemUi]. */
    suspend fun install(t: Tweak): Boolean = withContext(Dispatchers.IO) {
        val v = t.latest ?: return@withContext false
        try {
            if (!Root.available()) return@withContext fail(t.id, Failure.NO_ROOT)
            if (t.type == TweakType.XPOSED && !Root.vector()) return@withContext fail(t.id, Failure.NO_VECTOR)

            set(t.id, Job.Downloading(0f))
            val dir = File(ctx.cacheDir, "apk").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val apk = File(dir, "${t.id}-${v.code}.apk")
            val sha = try {
                Net.download(v.apk, apk) { set(t.id, Job.Downloading(it)) }
            } catch (e: Throwable) {
                return@withContext fail(t.id, Failure.DOWNLOAD, e.message ?: "")
            }

            set(t.id, Job.Verifying)
            if (v.sha256 != null && v.sha256 != sha) return@withContext fail(t.id, Failure.CHECKSUM)
            val info = ctx.packageManager.getPackageArchiveInfo(apk.path, 0)
            if (info == null || info.packageName != t.id) return@withContext fail(t.id, Failure.WRONG_PACKAGE, info?.packageName ?: "")

            set(t.id, Job.Installing)
            val (rc, out) = Root.sh("cat '${apk.path}' | pm install -r -S ${apk.length()}")
            apk.delete()
            if (rc != 0 || !out.contains("Success")) return@withContext fail(t.id, Failure.INSTALL, out.take(160))

            var systemUi = false
            if (t.type == TweakType.XPOSED) {
                set(t.id, Job.Enabling)
                val (rc1, out1) = Root.sh("${Root.VECTOR} modules enable ${t.id}")
                if (rc1 != 0) return@withContext fail(t.id, Failure.VECTOR, out1.take(160))
                if (t.scope.isNotEmpty()) {
                    val (rc2, out2) = Root.sh("${Root.VECTOR} scope set ${t.id} " + t.scope.joinToString(" ") { "$it/0" })
                    if (rc2 != 0) return@withContext fail(t.id, Failure.VECTOR, out2.take(160))
                }
                systemUi = restartScope(t)
            }
            set(t.id, null)
            refresh(_installed.value.keys + t.id)
            systemUi
        } catch (e: Throwable) {
            fail(t.id, Failure.INSTALL, e.message ?: "")
        }
    }

    suspend fun uninstall(t: Tweak): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!Root.available()) return@withContext fail(t.id, Failure.NO_ROOT)
            set(t.id, Job.Removing)
            if (t.type == TweakType.XPOSED && Root.vector()) Root.sh("${Root.VECTOR} modules disable ${t.id}")
            val (rc, out) = Root.sh("pm uninstall ${t.id}")
            if (rc != 0 || !out.contains("Success")) return@withContext fail(t.id, Failure.UNINSTALL, out.take(160))
            val systemUi = if (t.type == TweakType.XPOSED) restartScope(t) else false
            set(t.id, null)
            refresh(_installed.value.keys - t.id)
            systemUi
        } catch (e: Throwable) {
            fail(t.id, Failure.UNINSTALL, e.message ?: "")
        }
    }

    /** Restarts every app in the scope except SystemUI; returns whether SystemUI is in it. */
    private fun restartScope(t: Tweak): Boolean {
        for (pkg in t.scope) if (pkg != SYSTEMUI && pkg != "android") Root.sh("am force-stop $pkg")
        return SYSTEMUI in t.scope
    }

    fun restartSystemUi() {
        Root.sh("kill \$(pidof $SYSTEMUI)")
    }

    fun open(id: String): Boolean {
        val intent = ctx.packageManager.getLaunchIntentForPackage(id) ?: return false
        ctx.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private fun fail(id: String, reason: Failure, detail: String = ""): Boolean {
        set(id, Job.Failed(reason, detail))
        return false
    }

    companion object {
        const val DEREALIZATION = "dev.novacain.ntclocks"
        private const val SYSTEMUI = "com.android.systemui"
    }
}

object Root {
    const val VECTOR = "/data/adb/modules/zygisk_vector/cli"

    fun sh(cmd: String): Pair<Int, String> = try {
        // --mount-master: the global mount namespace (the app's own namespace hides other apps' data)
        val p = ProcessBuilder("su", "--mount-master", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor() to out
    } catch (t: Throwable) {
        -1 to (t.message ?: t.toString())
    }

    fun available(): Boolean = sh("id -u").let { it.first == 0 && it.second.trim() == "0" }

    fun vector(): Boolean = sh("test -x $VECTOR").first == 0
}
