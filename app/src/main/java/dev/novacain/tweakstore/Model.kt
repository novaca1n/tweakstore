package dev.novacain.tweakstore

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/**
 * Repository index (repo.json), format 1:
 *
 * ```
 * {
 *   "format": 1,
 *   "name": "novaca1n", "description": {"en": "...", "ru": "..."}, "maintainer": "novaca1n",
 *   "tweaks": [{
 *     "id": "dev.novacain.hive",                     // package name of the APK
 *     "name": "Hive", "author": "novaca1n",
 *     "summary": {"en": "...", "ru": "..."}, "description": {"en": "...", "ru": "..."},
 *     "icon": "icons/hive.png", "screenshots": ["shots/hive-1.png"],   // relative to repo.json or absolute
 *     "type": "app" | "xposed", "scope": ["com.nothing.launcher"],     // scope — Vector targets for "xposed"
 *     "category": "launcher", "homepage": "https://...", "source": "https://...", "license": "GPL-3.0",
 *     "requires": {"derealization": 45, "devices": ["galaga"], "android": 36},
 *     "versions": [{"name": "0.1", "code": 1, "apk": "https://.../hive-0.1.apk", "sha256": "...",
 *                   "size": 123, "changelog": {"en": "...", "ru": "..."}, "date": "2026-10-28"}]
 *   }]
 * }
 * ```
 * Any text can be a plain string or a {language: text} object; the phone's language is used, then English.
 */
data class Repo(
    val url: String,
    val name: String,
    val description: String,
    val maintainer: String,
    val tweaks: List<Tweak>,
    val error: String? = null,
    val official: Boolean = false,
)

enum class TweakType { APP, XPOSED }

data class Tweak(
    val id: String,
    val name: String,
    val author: String,
    val summary: String,
    val description: String,
    val icon: String?,
    val screenshots: List<String>,
    val type: TweakType,
    val scope: List<String>,
    val category: String,
    val homepage: String?,
    val source: String?,
    val license: String?,
    val minDerealization: Long,
    val devices: List<String>,
    val minSdk: Int,
    val versions: List<Version>,
    val repoUrl: String,
    val repoName: String,
) {
    val latest: Version? get() = versions.maxByOrNull { it.code }
}

data class Version(
    val name: String,
    val code: Long,
    val apk: String,
    val sha256: String?,
    val size: Long,
    val changelog: String?,
    val date: String?,
)

object RepoParser {
    const val FORMAT = 1

    fun parse(url: String, text: String, official: Boolean): Repo {
        val o = JSONObject(text)
        val format = o.optInt("format", 1)
        require(format <= FORMAT) { "format $format" }
        val name = o.optString("name").ifBlank { URI(url).host ?: url }
        val tweaks = ArrayList<Tweak>()
        val arr = o.optJSONArray("tweaks") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            runCatching { tweak(t, url, name) }.getOrNull()?.let { tweaks += it }
        }
        return Repo(url, name, text(o.opt("description")), o.optString("maintainer"), tweaks, official = official)
    }

    private fun tweak(t: JSONObject, base: String, repoName: String): Tweak {
        val id = t.getString("id")
        val req = t.optJSONObject("requires") ?: JSONObject()
        val versions = ArrayList<Version>()
        val va = t.optJSONArray("versions") ?: JSONArray()
        for (i in 0 until va.length()) {
            val v = va.optJSONObject(i) ?: continue
            val apk = v.optString("apk").takeIf { it.isNotBlank() } ?: continue
            versions += Version(
                name = v.optString("name", v.optLong("code").toString()),
                code = v.optLong("code"),
                apk = resolve(base, apk)!!,
                sha256 = v.optString("sha256").takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT),
                size = v.optLong("size"),
                changelog = text(v.opt("changelog")).takeIf { it.isNotBlank() },
                date = v.optString("date").takeIf { it.isNotBlank() },
            )
        }
        return Tweak(
            id = id,
            name = text(t.opt("name")).ifBlank { id },
            author = t.optString("author"),
            summary = text(t.opt("summary")),
            description = text(t.opt("description")),
            icon = resolve(base, t.optString("icon")),
            screenshots = strings(t.optJSONArray("screenshots")).mapNotNull { resolve(base, it) },
            type = if (t.optString("type") == "xposed") TweakType.XPOSED else TweakType.APP,
            scope = strings(t.optJSONArray("scope")),
            category = t.optString("category"),
            homepage = t.optString("homepage").takeIf { it.isNotBlank() },
            source = t.optString("source").takeIf { it.isNotBlank() },
            license = t.optString("license").takeIf { it.isNotBlank() },
            minDerealization = req.optLong("derealization", 0),
            devices = strings(req.optJSONArray("devices")),
            minSdk = req.optInt("android", 0),
            versions = versions,
            repoUrl = base,
            repoName = repoName,
        )
    }

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }

    /** Localized text: plain string, or {language: text} — phone language, then English, then anything. */
    fun text(v: Any?): String = when (v) {
        is String -> v
        is JSONObject -> {
            val lang = Locale.getDefault().language
            v.optString(lang).ifBlank { v.optString("en") }.ifBlank {
                v.keys().asSequence().firstOrNull()?.let { v.optString(it) } ?: ""
            }
        }
        else -> ""
    }

    /** Relative paths are resolved against the repo.json address. */
    fun resolve(base: String, path: String?): String? {
        if (path.isNullOrBlank()) return null
        return runCatching { URI(base).resolve(path).toString() }.getOrNull() ?: path
    }
}
