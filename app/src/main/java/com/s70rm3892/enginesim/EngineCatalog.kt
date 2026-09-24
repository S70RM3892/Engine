package com.s70rm3892.enginesim

import android.content.Context
import org.json.JSONObject
import java.io.File

/** assets/engines/ のカタログ (プラグイン型: JSON を追加するだけで機種が増える)。 */
class EngineCatalog(private val context: Context) {
    /** custom = true のときは端末内 (filesDir/custom_engines) のユーザー定義 */
    data class Entry(val file: String, val id: String, val name: String, val category: String, val custom: Boolean = false)

    private val builtin: List<Entry> by lazy {
        val root = JSONObject(readAsset("engines/index.json"))
        val arr = root.getJSONArray("engines")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Entry(o.getString("file"), o.getString("id"), o.getString("name"), o.optString("category", ""))
        }
    }

    private val customDir: File get() = File(context.filesDir, "custom_engines").apply { mkdirs() }

    /** 組み込み + ユーザー定義 (新しい順) */
    val entries: List<Entry>
        get() = builtin + customEntries()

    fun customEntries(): List<Entry> =
        (customDir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .mapNotNull { f ->
                runCatching {
                    val o = JSONObject(f.readText())
                    Entry(f.name, o.optString("id", f.nameWithoutExtension), o.optString("name", f.nameWithoutExtension),
                        o.optString("category", "カスタム (Custom)"), custom = true)
                }.getOrNull()
            }

    val categories: List<String> get() = entries.map { it.category }.distinct()

    fun json(entry: Entry): String =
        if (entry.custom) File(customDir, entry.file).readText() else readAsset("engines/${entry.file}")

    /** ユーザー定義を保存し、そのエントリを返す */
    fun saveCustom(engineJson: String): Entry {
        val o = JSONObject(engineJson)
        val file = "c_${System.currentTimeMillis()}.json"
        File(customDir, file).writeText(engineJson)
        return Entry(file, o.optString("id"), o.optString("name"), o.optString("category", "カスタム (Custom)"), custom = true)
    }

    fun deleteCustom(entry: Entry) {
        if (entry.custom) File(customDir, entry.file).delete()
    }

    private fun readAsset(path: String): String =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
}

/** ネイティブが返す機種要約。 */
data class EngineSummary(
    val id: String,
    val name: String,
    val description: String,
    val family: String,
    val cycle: String,
    val cylinders: Int,
    val displacementL: Float,
    val boreMm: Float,
    val strokeMm: Float,
    val rodMm: Float,
    val compressionRatio: Float,
    val idleRpm: Float,
    val redlineRpm: Float,
    val limiterRpm: Float,
    val peakTorque: Float,
    val gears: Int,
    val propeller: Boolean,
    val induction: Int,
    val chambers: Int,
    val firingOrder: String,
) {
    val isCombustion get() = family == "reciprocating" || family == "wankel"
    val isTurbine get() = family == "turbine"
    val isElectric get() = family == "electric"

    companion object {
        fun parse(json: String): EngineSummary? {
            val o = JSONObject(json)
            if (o.has("error")) return null
            return EngineSummary(
                o.getString("id"), o.getString("name"), o.optString("description"), o.getString("family"),
                o.getString("cycle"), o.getInt("cylinders"), o.getDouble("displacementL").toFloat(),
                o.getDouble("boreMm").toFloat(), o.getDouble("strokeMm").toFloat(), o.getDouble("rodMm").toFloat(),
                o.getDouble("compressionRatio").toFloat(), o.getDouble("idleRpm").toFloat(),
                o.getDouble("redlineRpm").toFloat(), o.getDouble("limiterRpm").toFloat(),
                o.getDouble("peakTorque").toFloat(), o.getInt("gears"), o.getBoolean("propeller"),
                o.getInt("induction"), o.getInt("chambers"), o.optString("firingOrder"),
            )
        }
    }
}
