package com.jasonz3157.weightjournal

import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToInt

private const val MAX_CLOCK = 9_007_199_254_740_991L
internal const val HEIGHT_KEY = "height"
private val datePattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

internal data class JournalEntry(val value: Double?, val clock: Long, val id: String) {
    fun winsOver(other: JournalEntry): Boolean = when {
        clock != other.clock -> clock > other.clock
        id != other.id -> id > other.id
        else -> (value ?: Double.POSITIVE_INFINITY) > (other.value ?: Double.POSITIVE_INFINITY)
    }
}

internal data class WeightRecord(val date: LocalDate, val kg: Double, val delta: Double?)

internal data class Journal(val entries: Map<String, JournalEntry> = emptyMap()) {
    val height: Double? get() = entries[HEIGHT_KEY]?.value

    fun records(through: LocalDate): List<WeightRecord> {
        val sorted = entries.mapNotNull { (key, entry) ->
            if (key == HEIGHT_KEY || entry.value == null) return@mapNotNull null
            val date = LocalDate.parse(key)
            if (date > through) null else date to entry.value
        }.sortedBy { it.first }
        return sorted.mapIndexed { index, (date, kg) ->
            WeightRecord(date, kg, sorted.getOrNull(index - 1)?.second?.let { kg - it })
        }
    }

    fun set(key: String, value: Double?, now: Long = System.currentTimeMillis()): Journal {
        require(isJournalKey(key) && (value == null || validValue(key, value)))
        if (entries[key]?.value == value) return this
        val clock = maxOf(now, (entries.values.maxOfOrNull { it.clock } ?: 0L) + 1)
        check(clock <= MAX_CLOCK) { "同步文件的版本已达上限。" }
        return Journal(entries + (key to JournalEntry(value, clock, UUID.randomUUID().toString())))
    }

    fun merge(incoming: Journal): Journal {
        val changes = incoming.entries.filter { (key, entry) ->
            val previous = entries[key]
            previous == null || entry.winsOver(previous)
        }
        return if (changes.isEmpty()) this else Journal(entries + changes)
    }

    fun toJson(): String {
        val data = JSONObject()
        entries.forEach { (key, entry) ->
            data.put(key, JSONObject()
                .put("value", entry.value ?: JSONObject.NULL)
                .put("clock", entry.clock)
                .put("id", entry.id))
        }
        return JSONObject().put("version", 1).put("entries", data).toString()
    }

    companion object {
        fun parse(raw: String): Journal {
            val root = JSONObject(raw)
            val version = root.get("version") as? Number
            require(version?.toDouble() == 1.0)
            val data = root.getJSONObject("entries")
            return Journal(data.keys().asSequence().associateWith { key ->
                val entry = data.getJSONObject(key)
                val number = (entry.get("clock") as? Number)?.toDouble()
                val clock = number?.toLong()
                val id = entry.get("id") as? String
                val rawValue = entry.get("value")
                val value = (rawValue as? Number)?.toDouble()
                require(isJournalKey(key) && clock != null && clock in 0..MAX_CLOCK &&
                    number == clock.toDouble() && id != null &&
                    (rawValue === JSONObject.NULL || (value != null && validValue(key, value))))
                JournalEntry(value, clock, id)
            })
        }
    }
}

private fun isJournalKey(key: String): Boolean = key == HEIGHT_KEY ||
    (datePattern.matches(key) && runCatching { LocalDate.parse(key) }.isSuccess)

private fun validValue(key: String, value: Double): Boolean =
    value in 1.0..if (key == HEIGHT_KEY) 300.0 else 1000.0

internal fun bmi(weight: Double, height: Double): Double {
    val meters = height / 100
    return weight / (meters * meters)
}

internal enum class BmiLevel(val label: String) {
    Low("营养不足"),
    Normal("正常"),
    Overweight("超重"),
    Moderate("中度肥胖"),
    Severe("严重肥胖"),
}

internal data class BmiReading(val value: Double, val level: BmiLevel)

internal fun bmiReading(weight: Double, height: Double): BmiReading {
    val value = (bmi(weight, height) * 10).roundToInt() / 10.0
    val level = when {
        value < 18.5 -> BmiLevel.Low
        value < 23.0 -> BmiLevel.Normal
        value < 25.0 -> BmiLevel.Overweight
        value < 30.0 -> BmiLevel.Moderate
        else -> BmiLevel.Severe
    }
    return BmiReading(value, level)
}
