package com.atlas.ledger.domain

import com.atlas.ledger.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CalculationsTest {
    private val today = LocalDate.of(2026, 10, 13)
    private fun expense(id: String = "t", cents: Long = 300000, kind: String = "EXPENSE", date: String = "2026-10-01") =
        LedgerTransaction(id, kind, cents, date, "购物", "微信", targetAccount = if (kind == "TRANSFER") "现金" else "")
    private fun validGraph(): LedgerBackup {
        val subscription = Subscription("s", "云存储", billingCycle = "年付")
        val period = SubscriptionPeriod("p", "s", 36500, "2026-01-01", "2027-01-01")
        val item = Durable("d", "手机", amountCents = 300000, purchaseDate = "2026-01-01", startDate = "2026-01-01", expectedDays = 1000)
        return LedgerBackup(version = 2, transactions = listOf(expense("st", 36500).copy(category = "订阅", origin = "SUBSCRIPTION", originId = "p"), expense("dt").copy(date = "2026-01-01", origin = "DURABLE", originId = "d")), subscriptions = listOf(subscription), periods = listOf(period), durables = listOf(item))
    }
    private fun fails(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }

    @Test fun moneyUsesExactCents() { assertEquals(29L, parseAmount("0.29")); assertEquals(1001L, parseAmount("10.01")); assertEquals("¥0.29", money(29)) }
    @Test fun invalidMoneyIsRejected() { listOf("0", "-1", "1.001", "1e3", "NaN", "1000000000", "").forEach { input -> fails { parseAmount(input) } } }
    @Test fun costRoundsHalfUp() { assertEquals("¥0.33", dailyMoney(100, 3)); assertEquals("¥0.01", dailyMoney(1, 2)) }
    @Test fun annualSubscriptionUsesExactCalendarDays() { assertEquals(366L, periodDays(SubscriptionPeriod("p", "s", 36600, "2024-01-01", "2025-01-01"))) }
    @Test fun subscriptionIncludesStartAndExcludesEnd() {
        val p = SubscriptionPeriod("p", "s", 1000, "2026-10-13", "2026-10-14")
        assertTrue(activeOn(p, today)); assertFalse(activeOn(p, today.plusDays(1))); assertEquals(1L, remainingDays(p, today))
    }
    @Test fun firstUseDayCountsAsOne() { val item = validGraph().durables.single().copy(startDate = today.toString()); assertEquals(1L, usedDays(item, today)); assertEquals(0L, usedDays(item, today.minusDays(1))) }
    @Test fun stoppedDurableFreezesDays() { val item = validGraph().durables.single().copy(startDate = "2026-10-01", stoppedDate = "2026-10-10"); assertEquals(10L, usedDays(item, today)); assertEquals(10L, usedDays(item, today.plusYears(1))) }
    @Test fun futureStopDoesNotCountFutureDays() { val item = validGraph().durables.single().copy(startDate = "2026-10-01", stoppedDate = "2026-10-20"); assertEquals(13L, usedDays(item, today)) }
    @Test fun totalsExcludeTransfersAndFutureDates() {
        val txs = listOf(expense("a", 100), expense("b", 200, "INCOME"), expense("c", 999, "TRANSFER"), expense("d", 999, date = "2026-11-01"), expense("e", 999, date = "2026-10-14"))
        assertEquals(200L to 100L, monthTotals(txs, today))
    }
    @Test fun validLinkedGraphIsAccepted() { validateBackup(validGraph()) }
    @Test fun missingLinkedExpenseIsRejected() { fails { validateBackup(validGraph().copy(transactions = emptyList())) } }
    @Test fun mismatchedExpenseAmountIsRejected() { val b = validGraph(); fails { validateBackup(b.copy(transactions = b.transactions.map { it.copy(amountCents = 1) })) } }
    @Test fun overlappingSubscriptionPeriodsAreRejected() {
        val b = validGraph(); val p = b.periods.single().copy(id = "p2", startDate = "2026-10-01", endExclusive = "2027-10-01")
        val tx = b.transactions.first().copy(id = "st2", originId = "p2")
        fails { validateBackup(b.copy(periods = b.periods + p, transactions = b.transactions + tx)) }
    }
    @Test fun adjacentSubscriptionPeriodsAreAccepted() {
        val b = validGraph(); val p = b.periods.single().copy(id = "p2", startDate = "2027-01-01", endExclusive = "2028-01-01")
        validateBackup(b.copy(periods = b.periods + p, transactions = b.transactions + b.transactions.first().copy(id = "st2", originId = "p2")))
    }
    @Test fun repeatedImportDoesNotDuplicateRows() { val b = validGraph(); val merged = mergeBackup(b, b); assertEquals(2, merged.transactions.size); assertEquals(1, merged.periods.size) }
    @Test fun conflictingImportIsRejected() { val b = validGraph(); fails { mergeBackup(b, b.copy(subscriptions = listOf(b.subscriptions.single().copy(name = "不同内容")))) } }
    @Test fun unknownVersionAndDuplicateIdsAreRejected() { fails { validateBackup(validGraph().copy(version = 4)) }; fails { validateBackup(LedgerBackup(transactions = listOf(expense(), expense()))) } }
    @Test fun danglingPeriodIsRejected() { val b = validGraph(); fails { validateBackup(b.copy(subscriptions = emptyList())) } }
    @Test fun invalidDurableDatesAreRejected() { val b = validGraph(); fails { validateBackup(b.copy(durables = listOf(b.durables.single().copy(stoppedDate = "2025-12-31")))) } }
    @Test fun subscriptionPaymentIsCountedOnce() { val b = validGraph(); assertEquals(0L, monthTotals(b.transactions, today).second); assertEquals("¥1.00", dailyMoney(b.periods.single().amountCents, periodDays(b.periods.single()))) }
    @Test fun backupRoundTripAndRequiredFields() { val b = validGraph(); assertEquals(b, decodeBackup(encodeBackup(b))); fails { decodeBackup("{}") }; fails { decodeBackup("{\"version\":1}") } }
    @Test fun weeklySubscriptionCanBeExportedAndRestored() {
        val b = validGraph()
        val p = b.periods.single().copy(amountCents = 700, startDate = "2026-10-01", endExclusive = "2026-10-08")
        val weekly = b.copy(subscriptions = listOf(b.subscriptions.single().copy(billingCycle = "周付")), periods = listOf(p), transactions = b.transactions.map { if (it.origin == "SUBSCRIPTION") it.copy(amountCents = 700, date = p.startDate) else it })
        assertEquals(7L, periodDays(p)); assertEquals("¥1.00", dailyMoney(p.amountCents, periodDays(p)))
        assertEquals(weekly, decodeBackup(encodeBackup(weekly)))
    }

    @Test fun combinedDailyIncludesSubscriptionsAndBothDurableCosts() {
        val period = SubscriptionPeriod("weekly", "s", 700, "2026-10-13", "2026-10-20")
        val item = Durable("phone", "手机", amountCents = 300000, purchaseDate = "2026-10-04", startDate = "2026-10-04", expectedDays = 1000)
        val totals = dailyConsumption(listOf(period), listOf(item), today)
        assertEquals("301.00", totals.current.toPlainString())
        assertEquals("4.00", totals.expected.toPlainString())
        assertEquals(1, totals.subscriptions); assertEquals(1, totals.durables); assertEquals(0, totals.fallbackDurables)
        // Monthly actual spending remains the original payment, with no added daily entries.
        assertEquals(300700L, monthTotals(listOf(expense("p", 700), expense("d", 300000)), today).second)
    }

    @Test fun dailyTotalsIgnoreInactiveRecordsAndExplainUnsetExpectedDays() {
        val period = SubscriptionPeriod("p", "s", 700, "2026-10-13", "2026-10-20")
        val item = Durable("item", "物品", amountCents = 100, purchaseDate = "2026-10-11", startDate = "2026-10-11", expectedDays = null)
        val totals = dailyConsumption(listOf(period.copy(id = "past", startDate = "2026-10-01", endExclusive = "2026-10-08"), period.copy(id = "future", startDate = "2026-10-14", endExclusive = "2026-10-21")), listOf(item, item.copy(id = "stopped", stoppedDate = "2026-10-12"), item.copy(id = "future", startDate = "2026-10-14")), today)
        assertEquals("0.33", totals.current.toPlainString()); assertEquals("0.33", totals.expected.toPlainString())
        assertEquals(0, totals.subscriptions); assertEquals(1, totals.durables); assertEquals(1, totals.fallbackDurables)
        val empty = dailyConsumption(emptyList(), emptyList(), today)
        assertEquals("0.00", empty.current.toPlainString()); assertEquals("0.00", empty.expected.toPlainString())
    }

}
