package ai.openclaw.runtime.cron

import java.util.Calendar
import java.util.TimeZone

/**
 * Lightweight cron expression parser supporting standard 5-field cron expressions.
 * Fields: minute hour day-of-month month day-of-week
 *
 * Supports: numbers, ranges (1-5), steps (star/5), lists (1,3,5), star wildcard.
 */
class CronExpression(expression: String) {
    private val minutes: Set<Int>
    private val hours: Set<Int>
    private val daysOfMonth: Set<Int>
    private val months: Set<Int>
    private val daysOfWeek: Set<Int>

    init {
        val parts = expression.trim().split(Regex("\\s+"))
        require(parts.size == 5) { "Cron expression must have 5 fields, got ${parts.size}: $expression" }
        minutes = parseField(parts[0], 0, 59)
        hours = parseField(parts[1], 0, 23)
        daysOfMonth = parseField(parts[2], 1, 31)
        months = parseField(parts[3], 1, 12)
        daysOfWeek = parseField(parts[4], 0, 6)
    }

    /**
     * Compute the next run time in milliseconds after [afterMs].
     * Returns null if no valid next time can be found within a year.
     */
    fun nextAfter(afterMs: Long, tz: TimeZone = TimeZone.getDefault()): Long? {
        val cal = Calendar.getInstance(tz)
        cal.timeInMillis = afterMs
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        // Advance to the next minute
        cal.add(Calendar.MINUTE, 1)

        val maxIterations = 366 * 24 * 60 // ~1 year of minutes
        for (i in 0 until maxIterations) {
            val month = cal.get(Calendar.MONTH) + 1
            val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val minute = cal.get(Calendar.MINUTE)
            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1 // Calendar.SUNDAY=1, we want 0=Sunday

            if (month !in months) {
                // Skip to the first day of next month
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.add(Calendar.MONTH, 1)
                continue
            }
            if (dayOfMonth !in daysOfMonth || dayOfWeek !in daysOfWeek) {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.add(Calendar.DAY_OF_MONTH, 1)
                continue
            }
            if (hour !in hours) {
                cal.set(Calendar.MINUTE, 0)
                cal.add(Calendar.HOUR_OF_DAY, 1)
                continue
            }
            if (minute !in minutes) {
                cal.add(Calendar.MINUTE, 1)
                continue
            }

            return cal.timeInMillis
        }

        return null
    }

    companion object {
        fun parseField(field: String, min: Int, max: Int): Set<Int> {
            val result = mutableSetOf<Int>()
            for (part in field.split(",")) {
                val trimmed = part.trim()
                if (trimmed == "*") {
                    result.addAll(min..max)
                } else if ("/" in trimmed) {
                    val (rangePart, stepStr) = trimmed.split("/", limit = 2)
                    val step = stepStr.toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid step in cron field: $field")
                    require(step > 0) { "Step must be positive: $field" }
                    val start = if (rangePart == "*") min else {
                        rangePart.toIntOrNull()
                            ?: throw IllegalArgumentException("Invalid range start in cron field: $field")
                    }
                    var v = start
                    while (v <= max) {
                        result.add(v)
                        v += step
                    }
                } else if ("-" in trimmed) {
                    val (startStr, endStr) = trimmed.split("-", limit = 2)
                    val start = startStr.toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid range start in cron field: $field")
                    val end = endStr.toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid range end in cron field: $field")
                    require(start in min..max && end in min..max) {
                        "Range out of bounds [$min-$max]: $field"
                    }
                    result.addAll(start..end)
                } else {
                    val v = trimmed.toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid value in cron field: $field")
                    require(v in min..max) { "Value out of bounds [$min-$max]: $field" }
                    result.add(v)
                }
            }
            return result
        }
    }
}
