package com.atlas.ledger.domain

import com.atlas.ledger.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class RecordsTest {
    private val today = LocalDate.parse("2026-10-14")
    private fun item() = Durable("d", "手机", amountCents = 100000, purchaseDate = "2026-01-01", startDate = "2026-01-01", expectedDays = 100, residualCents = 20000)
    private fun expense(id: String, cents: Long, date: String, name: String) = LedgerTransaction(id, "EXPENSE", cents, date, "交通", "微信", note = name)
    @Test fun residualIsSubtractedAfterExpectedExpiryAndInForecastImmediately() {
        val value = item()
        assertEquals("8.00", durableExpectedCost(value, today).toPlainString())
        assertEquals("10.00", durableCurrentCost(value, LocalDate.parse("2026-04-10")).toPlainString()) // day 100
        assertEquals("7.92", durableCurrentCost(value, LocalDate.parse("2026-04-11")).toPlainString()) // day 101
        assertEquals(LocalDate.parse("2026-04-10"), durableExpectedEnd(value))
    }
    @Test fun residualValidationAllowsZeroAndFullPriceButRejectsLossyInputs() {
        assertEquals(0L, parseResidual("")); assertEquals(0L, parseResidual("0.00")); assertEquals(12345L, parseResidual("123.45"))
        listOf("-1", "1.001", "1e3", "NaN").forEach { assertThrows(IllegalArgumentException::class.java) { parseResidual(it) } }
        validateBackup(LedgerBackup(durables = listOf(item().copy(residualCents = item().amountCents))))
        assertEquals("0.00", durableExpectedCost(item().copy(residualCents = item().amountCents), today).toPlainString())
        assertThrows(IllegalArgumentException::class.java) { validateBackup(LedgerBackup(durables = listOf(item().copy(residualCents = 100001)))) }
    }
    @Test fun unsetExpectedDaysRetainsCurrentCostInsteadOfInventingExpiry() {
        val value = item().copy(expectedDays = null)
        assertNull(durableExpectedEnd(value)); assertEquals(durableCurrentCost(value, today), durableExpectedCost(value, today))
    }
    @Test fun forecastIncludesSubscriptionDurableAndFixedScheduleWithoutChangingMonthTotals() {
        val period = SubscriptionPeriod("p", "s", 700, "2026-10-14", "2026-10-21")
        val value = item().copy(startDate = "2026-10-14", purchaseDate = "2026-10-14")
        val rule = RecurringRule("r", "公交", 200, weekdays = "1,2,3,4,5", startDate = today.toString())
        val sum = dailyConsumption(listOf(period), listOf(value), today, listOf(rule))
        assertEquals("1003.00", sum.current.toPlainString()); assertEquals("10.43", sum.expected.toPlainString()); assertEquals(1, sum.fixed)
        val skipped = RecurringOccurrence("skip", "r", today.toString(), "SKIPPED", rule.name, rule.amountCents, rule.category, rule.account)
        assertEquals("1001.00", dailyConsumption(listOf(period), listOf(value), today, listOf(rule), listOf(skipped)).current.toPlainString())
        assertEquals(0L to 0L, monthTotals(emptyList(), today))
    }
    @Test fun sameExpenseNameGroupsAcrossDatesAndAccountsWithoutMergingIncomeOrArchive() {
        val a = expense("a", 200, "2026-10-13", " 公交 ")
        val b = expense("b", 300, "2026-10-14", "公交").copy(account = "支付宝")
        val income = expense("i", 100, "2026-10-14", "公交").copy(kind = "INCOME")
        val archive = expense("old", 999, "2026-10-14", "公交").copy(origin = "RECURRING", originId = "old-occurrence")
        val groups = groupedTransactions(listOf(a, b, income, archive), RecordSort.PRICE_DESC)
        assertEquals(2, groups.size); assertEquals(500L, groups.first().amountCents); assertEquals(2, groups.first().transactions.size)
        assertEquals("2026-10-14", groups.first().date); assertEquals(100L to 500L, monthTotals(listOf(a, b, income, archive), today))
    }
    @Test fun sortingUsesVisibleSubscriptionPeriodPriceAndIsStable() {
        val subscriptions = listOf(Subscription("a", "便宜"), Subscription("b", "贵"))
        val periods = listOf(SubscriptionPeriod("a-old", "a", 99900, "2025-01-01", "2025-02-01"), SubscriptionPeriod("a-new", "a", 100, "2026-10-01", "2026-11-01"), SubscriptionPeriod("b-now", "b", 200, "2026-10-01", "2026-11-01"))
        assertEquals(listOf("b", "a"), sortedSubscriptions(subscriptions, periods, today, RecordSort.PRICE_DESC).map { it.id })
        assertEquals(listOf("a", "b"), sortedSubscriptions(subscriptions, periods, today, RecordSort.PRICE_ASC).map { it.id })
        assertEquals(listOf("b", "d"), sortedDurables(listOf(item(), item().copy(id = "b", amountCents = 200000)), RecordSort.PRICE_DESC).map { it.id })
        assertEquals(listOf("b", "a"), sortedTransactions(listOf(expense("a", 300, "2026-10-13", "A"), expense("b", 200, "2026-10-14", "B")), RecordSort.DATE_DESC).map { it.id })
    }
    @Test fun weekdayForecastHonorsRangeAndSkipWithoutAutoBooking() {
        val rule = RecurringRule("r", "公交", 200, weekdays = "3", startDate = "2026-10-14", endDate = "2026-10-21")
        val skip = RecurringOccurrence("o", "r", "2026-10-21", "SKIPPED", rule.name, rule.amountCents, rule.category, rule.account)
        val forecast = forecastMonth(listOf(rule), listOf(skip), YearMonth.of(2026, 10))
        assertEquals(listOf("2026-10-14", "2026-10-21"), forecast.map { it.date }); assertEquals(1, forecast.count { it.skipped })
        assertEquals(200L, forecast.filterNot { it.skipped }.sumOf { it.amountCents })
        assertTrue(forecastMonth(listOf(rule.copy(enabled = false)), listOf(skip), YearMonth.of(2026, 10)).isEmpty())
    }
    @Test fun forecastOnlyBackupRoundTripsAndKeepsOrphanedHistoricalRecords() {
        val archive = expense("old", 200, "2026-10-01", "旧公交").copy(origin = "RECURRING", originId = "removed-rule-history")
        val backup = LedgerBackup(transactions = listOf(archive), durables = listOf(item()))
        val encoded = encodeBackup(backup); assertEquals(backup, decodeBackup(encoded))
        assertThrows(IllegalArgumentException::class.java) { decodeBackup(encoded.replace("\"residualCents\": 20000", "\"removedResidual\": 20000")) }
    }
}
