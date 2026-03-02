package ai.openclaw.runtime.cron

import java.util.Calendar
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class CronExpressionTest {

    @Test
    fun `parse simple every-minute expression`() {
        val cron = CronExpression("* * * * *")
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(2025, Calendar.JANUARY, 1, 12, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val next = cron.nextAfter(cal.timeInMillis, TimeZone.getTimeZone("UTC"))
        assertNotNull(next)
        val result = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        result.timeInMillis = next
        assertEquals(12, result.get(Calendar.HOUR_OF_DAY))
        assertEquals(1, result.get(Calendar.MINUTE))
    }

    @Test
    fun `parse specific minute and hour`() {
        val cron = CronExpression("30 14 * * *")
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(2025, Calendar.JANUARY, 1, 12, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val next = cron.nextAfter(cal.timeInMillis, TimeZone.getTimeZone("UTC"))
        assertNotNull(next)
        val result = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        result.timeInMillis = next
        assertEquals(14, result.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, result.get(Calendar.MINUTE))
    }

    @Test
    fun `parse range expression`() {
        val field = CronExpression.parseField("1-5", 0, 59)
        assertEquals(setOf(1, 2, 3, 4, 5), field)
    }

    @Test
    fun `parse step expression`() {
        val field = CronExpression.parseField("*/15", 0, 59)
        assertEquals(setOf(0, 15, 30, 45), field)
    }

    @Test
    fun `parse list expression`() {
        val field = CronExpression.parseField("1,15,30", 0, 59)
        assertEquals(setOf(1, 15, 30), field)
    }

    @Test
    fun `next run wraps to next day`() {
        val cron = CronExpression("0 9 * * *")
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(2025, Calendar.JANUARY, 1, 10, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val next = cron.nextAfter(cal.timeInMillis, TimeZone.getTimeZone("UTC"))
        assertNotNull(next)
        val result = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        result.timeInMillis = next
        assertEquals(2, result.get(Calendar.DAY_OF_MONTH)) // Next day since 9:00 already passed
        assertEquals(9, result.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, result.get(Calendar.MINUTE))
    }

    @Test
    fun `reject invalid expression with wrong field count`() {
        assertFailsWith<IllegalArgumentException> {
            CronExpression("* * *")
        }
    }

    @Test
    fun `reject out of range value`() {
        assertFailsWith<IllegalArgumentException> {
            CronExpression.parseField("60", 0, 59)
        }
    }

    @Test
    fun `day of week filtering works`() {
        // Monday only (day 1)
        val cron = CronExpression("0 12 * * 1")
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        // Jan 1, 2025 is a Wednesday
        cal.set(2025, Calendar.JANUARY, 1, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val next = cron.nextAfter(cal.timeInMillis, TimeZone.getTimeZone("UTC"))
        assertNotNull(next)
        val result = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        result.timeInMillis = next
        assertEquals(Calendar.MONDAY, result.get(Calendar.DAY_OF_WEEK))
    }
}
