package dev.novacain.tweakstore

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Repositories: the official one (novaca1n/tweaks) plus any the user adds. Each index is fetched over HTTPS and kept
 * on disk, so the store still works offline (the repo is then marked with the fetch error). If two repositories offer
 * the same tweak id, the one listed first wins (the official repo is first).
 */
class Repos(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("repos", Context.MODE_PRIVATE)
    private val cache = File(ctx.filesDir, "repos").apply { mkdirs() }

    private val _repos = MutableStateFlow<List<Repo>>(emptyList())
    val repos: StateFlow<List<Repo>> = _repos
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    /** All tweaks across repositories, de-duplicated by id. */
    fun tweaks(): List<Tweak> {
        val seen = HashSet<String>()
        return _repos.value.flatMap { it.tweaks }.filter { seen.add(it.id) }
    }

    fun urls(): List<String> {
        val custom = runCatching { JSONArray(prefs.getString("custom", "[]")) }.getOrNull()
        val list = ArrayList<String>()
        if (!prefs.getBoolean("hideOfficial", false)) list += OFFICIAL
        if (custom != null) for (i in 0 until custom.length()) list += custom.getString(i)
        return list
    }

    private fun saveCustom(list: List<String>) {
        prefs.edit().putString("custom", JSONArray(list.filter { it != OFFICIAL }).toString()).apply()
    }

    /** Shows cached indexes immediately, then refreshes from the network. */
    suspend fun load(network: Boolean = true) {
        _repos.value = urls().map { cached(it) ?: Repo(it, it, "", "", emptyList(), error = null, official = it == OFFICIAL) }
        if (!network) return
        _loading.value = true
        try {
            _repos.value = coroutineScope { urls().map { async(Dispatchers.IO) { fetch(it) } }.awaitAll() }
        } finally {
            _loading.value = false
        }
    }

    /**
     * Adds a repository. Accepts a full URL to repo.json, a folder URL, or the GitHub shorthand "owner/repo"
     * (→ raw.githubusercontent.com/owner/repo/main/repo.json). The index is fetched first; nothing is saved if it fails.
     */
    suspend fun add(input: String): Result<Repo> = withContext(Dispatchers.IO) {
        val url = normalize(input) ?: return@withContext Result.failure(IllegalArgumentException("address"))
        if (url in urls()) return@withContext Result.failure(IllegalStateException("exists"))
        val repo = fetch(url)
        if (repo.error != null) return@withContext Result.failure(IllegalStateException(repo.error))
        if (url == OFFICIAL) prefs.edit().putBoolean("hideOfficial", false).apply()
        else saveCustom(urls().filter { it != OFFICIAL } + url)
        _repos.value = _repos.value + repo
        Result.success(repo)
    }

    fun remove(url: String) {
        if (url == OFFICIAL) prefs.edit().putBoolean("hideOfficial", true).apply()
        else saveCustom(urls().filter { it != url && it != OFFICIAL })
        file(url).delete()
        _repos.value = _repos.value.filter { it.url != url }
    }

    private fun normalize(input: String): String? {
        val s = input.trim().removeSuffix("/")
        if (s.isEmpty()) return null
        Regex("^([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)$").matchEntire(s)?.let {
            return "https://raw.githubusercontent.com/${it.groupValues[1]}/${it.groupValues[2]}/main/repo.json"
        }
        val url = if (s.startsWith("http://") || s.startsWith("https://")) s else "https://$s"
        // github.com/owner/repo → its repo.json
        Regex("^https://github\\.com/([^/]+)/([^/]+)$").matchEntire(url)?.let {
            return "https://raw.githubusercontent.com/${it.groupValues[1]}/${it.groupValues[2]}/main/repo.json"
        }
        return if (url.endsWith(".json")) url else "$url/repo.json"
    }

    private fun file(url: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(cache, "$h.json")
    }

    private fun cached(url: String): Repo? = runCatching {
        RepoParser.parse(url, file(url).readText(), url == OFFICIAL)
    }.getOrNull()

    private fun fetch(url: String): Repo {
        return try {
            val text = Net.text(url)
            val repo = RepoParser.parse(url, text, url == OFFICIAL)
            file(url).writeText(text)
            repo
        } catch (t: Throwable) {
            val msg = t.message ?: t.javaClass.simpleName
            cached(url)?.copy(error = msg) ?: Repo(url, url, "", "", emptyList(), error = msg, official = url == OFFICIAL)
        }
    }

    companion object {
        const val OFFICIAL = "https://raw.githubusercontent.com/novaca1n/tweaks/main/repo.json"
    }
}

object Net {
    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "tweakstore/${BuildConfigLite.VERSION}")
        return c
    }

    fun text(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    /** Downloads to [to], reporting progress 0..1 (or -1 when the size is unknown). Returns the SHA-256. */
    fun download(url: String, to: File, progress: (Float) -> Unit): String {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var last = 0L
            c.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (done - last > 64 * 1024) {
                            last = done
                            progress(if (total > 0) done.toFloat() / total else -1f)
                        }
                    }
                }
            }
            progress(1f)
            return md.digest().joinToString("") { "%02x".format(it) }
        } finally {
            c.disconnect()
        }
    }

    fun bytes(url: String): ByteArray {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.readBytes()
        } finally {
            c.disconnect()
        }
    }
}

/** Version for the User-Agent without generating BuildConfig. */
object BuildConfigLite {
    const val VERSION = "1.0"
}
