package com.s70rm3892.enginesim

import android.content.Context
import org.json.JSONObject

/** assets/engines/ のカタログ (プラグイン型: JSON を追加するだけで機種が増える)。 */
class EngineCatalog(private val context: Context) {
    data class Entry(val file: String, val id: String, val name: String, val category: String)

    val entries: List<Entry> by lazy {
        val root = JSONObject(readAsset("engines/index.json"))
        val arr = root.getJSONArray("engines")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Entry(o.getString("file"), o.getString("id"), o.getString("name"), o.optString("category", ""))
        }
    }

    val categories: List<String> get() = entries.map { it.category }.distinct()

    fun json(entry: Entry): String = readAsset("engines/${entry.file}")

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
