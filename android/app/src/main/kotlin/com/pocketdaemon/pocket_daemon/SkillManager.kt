package com.pocketdaemon.pocket_daemon

import android.content.Context
import android.util.Log
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SkillManager(private val context: Context) {

    companion object {
        private const val TAG = "SkillManager"
        private const val SKILLS_DIR = "skills"
        private const val SKILL_FILE = "skill.md"
        private const val CACHE_FILE = ".cache.json"

        private val FRONTMATTER_RE = Regex("\\A---[^\\S\\n]*\\r?\\n(.*?)\\r?\\n---", RegexOption.DOT_MATCHES_ALL)
        private val DESCRIPTION_RE = Regex("^description:\\s*(.+)", RegexOption.MULTILINE)
        private val FETCH_RE = Regex("^fetch:\\s*(.+)", RegexOption.MULTILINE)
        private val NETWORK_RE = Regex("^network:\\s*(.+)", RegexOption.MULTILINE)
        private val AUTO_RE = Regex("^auto:\\s*(true|yes)", RegexOption.MULTILINE)

        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private val ISO_FMT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

        private val KEEP_FIELDS = setOf("id", "name", "date", "startTime", "location",
            "description", "adults", "children", "transportCosts")

        private val README = """
# PocketDaemon Skills

Skills give the agent specialized knowledge it can load on demand.
Each skill is a subfolder inside this `skills/` directory containing a `skill.md` file.

## Directory structure

```
PocketDaemon/
  skills/
    my-skill/
      skill.md
    another-skill/
      skill.md
```

The folder name is the skill identifier the agent uses internally.

## skill.md format

Start with YAML frontmatter containing a `description` field, followed by the full
instructions in Markdown:

```markdown
---
description: One-line summary of what this skill does and when the agent should use it.
---

# My Skill

Detailed instructions, context, examples, constraints — anything the agent needs
to carry out this skill effectively.
```

If you omit the frontmatter, the first non-empty paragraph is used as the description.

## Live data skills

Skills can fetch data from a local API by adding `fetch` and `network` fields:

```markdown
---
description: ...
fetch: https://example.local/api/events
network: 192.168.1.*
auto: true
---
```

- `fetch` — URL to GET when the skill is loaded
- `network` — device IP must match this pattern for the fetch to run
- `auto` — if true, skill content is injected into every session automatically

Fetched data is cached locally so it remains available off-network.

## Editing from a PC

Connect the phone via USB and open the device's internal storage.
Navigate to `PocketDaemon/skills/`, create or edit folders and `skill.md` files directly.
Changes take effect the next time a session starts — no restart required.
        """.trimIndent()
    }

    private val app: PocketDaemonApp
        get() = PocketDaemonApp.instance!!

    private val skillsDir: File
        get() = File(app.persistentDir, SKILLS_DIR).also { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
                File(dir, "README.md").writeText(README)
                Log.i(TAG, "Created skills directory with README at ${dir.absolutePath}")
            }
        }

    data class SkillInfo(
        val name: String,
        val description: String,
        val dir: File,
        val auto: Boolean = false,
    )

    fun listSkills(): List<SkillInfo> {
        val dir = skillsDir
        Log.i(TAG, "listSkills: dir=${dir.absolutePath} exists=${dir.exists()}")
        if (!dir.exists()) return emptyList()
        val subdirs = dir.listFiles { f -> f.isDirectory }
        Log.i(TAG, "listSkills: subdirs=${subdirs?.map { it.name }}")
        return subdirs
            ?.sortedBy { it.name }
            ?.mapNotNull { sub ->
                val file = File(sub, SKILL_FILE)
                if (!file.exists() || file.length() == 0L) {
                    Log.i(TAG, "listSkills: skip ${sub.name} (exists=${file.exists()}, len=${file.length()})")
                    return@mapNotNull null
                }
                val content = try { file.readText() } catch (e: Exception) {
                    Log.w(TAG, "Failed to read ${file.absolutePath}: ${e.message}")
                    return@mapNotNull null
                }
                val fmMatch = FRONTMATTER_RE.find(content)
                val yaml = fmMatch?.groupValues?.getOrNull(1) ?: ""
                val desc = extractDescription(content)
                val auto = AUTO_RE.containsMatchIn(yaml)
                Log.i(TAG, "listSkills: found '${sub.name}' auto=$auto desc=$desc")
                SkillInfo(name = sub.name, description = desc, dir = sub, auto = auto)
            } ?: emptyList()
    }

    fun getSkillContent(name: String): String? {
        val dir = File(skillsDir, name)
        val file = File(dir, SKILL_FILE)
        Log.i(TAG, "getSkillContent('$name'): path=${file.absolutePath} exists=${file.exists()}")
        if (!file.exists()) return null
        val raw = try { file.readText() } catch (e: Exception) {
            Log.w(TAG, "Failed to read skill '$name': ${e.message}")
            return null
        }

        val fmMatch = FRONTMATTER_RE.find(raw)
        val yaml = fmMatch?.groupValues?.getOrNull(1) ?: ""
        val fetchUrl = FETCH_RE.find(yaml)?.groupValues?.getOrNull(1)?.trim()
        Log.i(TAG, "getSkillContent('$name'): fmMatch=${fmMatch != null} fetchUrl=$fetchUrl yamlLen=${yaml.length}")
        if (fetchUrl == null) return raw
        val networkPattern = NETWORK_RE.find(yaml)?.groupValues?.getOrNull(1)?.trim()

        val dataBlock = fetchData(fetchUrl, networkPattern, dir)
        return if (dataBlock != null) "$raw\n\n$dataBlock" else raw
    }

    fun getSkillsPromptBlock(): String {
        val skills = listSkills()
        if (skills.isEmpty()) return ""

        val parts = mutableListOf<String>()
        val manualSkills = mutableListOf<SkillInfo>()

        for (skill in skills) {
            if (skill.auto) {
                val content = getSkillContent(skill.name)
                if (content != null) {
                    parts.add(content.trim())
                    Log.i(TAG, "Auto-injected skill '${skill.name}' (${content.length} chars)")
                }
            } else {
                manualSkills.add(skill)
            }
        }

        if (manualSkills.isNotEmpty()) {
            val listing = buildString {
                append("## Available Skills\n")
                append("If a skill below is relevant, call the use_skill tool with its name to load full instructions before acting.\n")
                for (skill in manualSkills) {
                    append("- ${skill.name}: ${skill.description}\n")
                }
            }
            parts.add(listing.trimEnd())
        }

        return parts.joinToString("\n---\n")
    }

    // ---------------------------------------------------------------------
    // Live data fetch + cache
    // ---------------------------------------------------------------------

    private fun fetchData(url: String, networkPattern: String?, skillDir: File): String? {
        val localIp = getLocalIpAddress()
        val onNetwork = networkPattern == null || (localIp != null && matchesNetwork(localIp, networkPattern))

        if (onNetwork) {
            try {
                val request = Request.Builder().url(url).build()
                app.httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val cleaned = transformResponse(body)
                        writeCache(skillDir, cleaned)
                        Log.i(TAG, "Fetched live data from $url (${cleaned.length} chars)")
                        return "## Live Data\n$cleaned"
                    }
                    Log.w(TAG, "Fetch failed: ${response.code} from $url")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fetch error from $url: ${e.message}")
            }
        } else {
            Log.i(TAG, "Off-network (ip=$localIp, pattern=$networkPattern) — using cache")
        }

        return readCache(skillDir)
    }

    private fun transformResponse(body: String): String {
        return try {
            val arr = JSONArray(body)
            val today = DATE_FMT.format(Date())
            val result = JSONArray()
            for (i in 0 until arr.length()) {
                val event = arr.getJSONObject(i)
                val date = event.optString("date", "")
                if (date < today) continue
                val clean = JSONObject()
                for (key in KEEP_FIELDS) {
                    if (event.has(key)) clean.put(key, event.get(key))
                }
                result.put(clean)
            }
            result.toString(2)
        } catch (e: Exception) {
            Log.w(TAG, "Transform failed, returning raw: ${e.message}")
            body
        }
    }

    private fun writeCache(skillDir: File, data: String) {
        try {
            val cache = JSONObject()
            cache.put("fetchedAt", ISO_FMT.format(Date()))
            cache.put("data", data)
            File(skillDir, CACHE_FILE).writeText(cache.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Cache write failed: ${e.message}")
        }
    }

    private fun readCache(skillDir: File): String? {
        val file = File(skillDir, CACHE_FILE)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            val fetchedAt = json.optString("fetchedAt", "unknown")
            val data = json.optString("data", "")
            if (data.isBlank()) null
            else "## Cached Data (from $fetchedAt)\n$data"
        } catch (e: Exception) {
            Log.w(TAG, "Cache read failed: ${e.message}")
            null
        }
    }

    // ---------------------------------------------------------------------
    // Network helpers
    // ---------------------------------------------------------------------

    private fun getLocalIpAddress(): String? {
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    val ip = addr.hostAddress ?: continue
                    if (ip.contains('.')) return ip
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get local IP: ${e.message}")
        }
        return null
    }

    private fun matchesNetwork(ip: String, pattern: String): Boolean {
        val prefix = pattern.removeSuffix("*")
        return ip.startsWith(prefix)
    }

    // ---------------------------------------------------------------------
    // Frontmatter parsing
    // ---------------------------------------------------------------------

    private fun extractDescription(content: String): String {
        val fmMatch = FRONTMATTER_RE.find(content)
        if (fmMatch != null) {
            val yaml = fmMatch.groupValues[1]
            val descMatch = DESCRIPTION_RE.find(yaml)
            if (descMatch != null) {
                return descMatch.groupValues[1].trim().trimEnd('.')  + "."
            }
        }
        val body = if (fmMatch != null) content.substring(fmMatch.range.last + 1) else content
        val firstParagraph = body.trim().split(Regex("\\n\\s*\\n")).firstOrNull { line ->
            line.isNotBlank() && !line.trimStart().startsWith("#")
        }
        return firstParagraph
            ?.trim()?.replace('\n', ' ')?.take(200)?.trimEnd('.')
            ?.let { "$it." }
            ?: "No description."
    }
}
