package com.atlas.ledger.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.atlas.ledger.domain.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class RecurringImportTest {
    private lateinit var db: LedgerDatabase
    private lateinit var repo: LedgerRepository
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java).build(); repo = LedgerRepository(db) }
    @After fun close() { db.close() }
    private fun at(date: String, time: String = "21:00") = LocalDateTime.parse("${date}T$time:00")
    private fun rule() = RecurringRule("r", "QA公交", 200, "交通", "微信", "1,2,3,4,5", "20:00", "2026-10-12")
    private fun candidate(cents: Long = 200, fingerprint: String = "trade-1") = BillCandidate(fingerprint, LedgerTransaction("unused", "EXPENSE", cents, "2026-10-13", "交通", "微信", note = "QA公交"))

    @Test fun oldWorkerAndRepeatedChecksNeverMaterializeSpending() = runBlocking {
        repo.saveRecurringRule(rule())
        coroutineScope { awaitAll(async { repo.reconcileRecurring(at("2026-10-22")) }, async { repo.reconcileRecurring(at("2026-10-22")) }) }
        assertTrue(db.dao().transactions().isEmpty()); assertTrue(db.dao().recurringOccurrences().isEmpty())
        assertEquals(15, forecastMonth(db.dao().recurringRules(), emptyList(), java.time.YearMonth.of(2026, 10)).size)
    }
    @Test fun skippedForecastDatesSurviveBackupWithoutCreatingBills() = runBlocking {
        repo.saveRecurringRule(rule()); repo.skipRecurringDate("r", "2026-10-13"); repo.skipRecurringDate("r", "2026-10-15")
        assertTrue(db.dao().transactions().isEmpty())
        val other = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java).build()
        try {
            val restored = LedgerRepository(other); restored.importJson(repo.exportJson()); restored.importJson(repo.exportJson()); restored.reconcileRecurring(at("2026-10-22"))
            val forecast = forecastMonth(other.dao().recurringRules(), other.dao().recurringOccurrences(), java.time.YearMonth.of(2026, 10))
            assertEquals(2, forecast.count { it.skipped }); assertTrue(other.dao().transactions().isEmpty())
            restored.restoreSkippedDate("r", "2026-10-13"); assertEquals(1, other.dao().recurringOccurrences().size)
        } finally { other.close() }
    }
    @Test fun pauseResumeAndPriceChangesOnlyAffectForecast() = runBlocking {
        repo.saveRecurringRule(rule().copy(enabled = false)); assertTrue(forecastMonth(db.dao().recurringRules(), emptyList(), java.time.YearMonth.of(2026, 10)).isEmpty())
        repo.saveRecurringRule(rule().copy(amountCents = 300)); assertTrue(forecastMonth(db.dao().recurringRules(), emptyList(), java.time.YearMonth.of(2026, 10)).all { it.amountCents == 300L })
        assertTrue(db.dao().transactions().isEmpty())
    }
    @Test fun actualBillImportIsNotBlockedBySamePriceArchivedForecast() = runBlocking {
        val period = SubscriptionPeriod("p", "s", 200, "2026-10-13", "2026-10-14")
        val archived = candidate().transaction.copy(id = "history", origin = "SUBSCRIPTION", originId = "p")
        repo.importJson(encodeBackup(LedgerBackup(transactions = listOf(archived), subscriptions = listOf(Subscription("s", "预期")), periods = listOf(period))))
        assertTrue(repo.possibleDuplicateBills(listOf(candidate())).isEmpty()); assertEquals(1, repo.importBills("WECHAT", listOf(candidate())))
        assertEquals(2, db.dao().transactions().size); assertEquals(1, db.dao().transactions().count(::isActualTransaction))
    }
    @Test fun billReceiptsPreventReimportAfterEditingOrDeleting() = runBlocking {
        val bill = candidate()
        assertEquals(1, repo.importBills("WECHAT", listOf(bill)))
        val saved = db.dao().transactions().single()
        repo.saveTransaction(saved.copy(amountCents = 350, note = "已编辑"))
        assertEquals(0, repo.importBills("WECHAT", listOf(bill)))
        assertEquals(350L, db.dao().transactions().single().amountCents)
        repo.deleteTransaction(saved.id)
        assertEquals(0, repo.importBills("WECHAT", listOf(bill))); assertTrue(db.dao().transactions().isEmpty())
        assertEquals(1, db.dao().importReceipts().size)
    }
    @Test fun changedOriginalContentIsFlaggedAndImportRollsBack() = runBlocking {
        repo.importBills("WECHAT", listOf(candidate()))
        assertEquals(setOf("trade-1"), repo.conflictingImportBills("WECHAT", listOf(candidate(300))))
        try { repo.importBills("WECHAT", listOf(candidate(400, "new"), candidate(300))); fail("Must reject changed original") } catch (_: IllegalArgumentException) { }
        assertEquals(1, db.dao().transactions().size); assertEquals(1, db.dao().importReceipts().size)
    }
    @Test fun invalidBillBatchDoesNotPartiallyWriteAndPotentialManualDuplicatesAreFlagged() = runBlocking {
        try { repo.importBills("WECHAT", listOf(candidate(), candidate(0, "bad"))); fail("Must reject whole batch") } catch (_: IllegalArgumentException) { }
        assertTrue(db.dao().transactions().isEmpty()); assertTrue(db.dao().importReceipts().isEmpty())
        repo.saveTransaction(candidate().transaction)
        assertEquals(setOf("trade-1"), repo.possibleDuplicateBills(listOf(candidate())))
    }
    @Test fun legacyBackupIsReadableAndNewBackupIncludesImportReceipts() = runBlocking {
        val legacy = """{"format":"daily-ledger","version":1,"exportedAt":"","transactions":[{"id":"old","kind":"INCOME","amountCents":100,"date":"2026-10-01","category":"工资","account":"现金"}],"subscriptions":[],"periods":[],"durables":[]}"""
        assertEquals(1, repo.importJson(legacy)); repo.importBills("WECHAT", listOf(candidate()))
        val backup = decodeBackup(repo.exportJson())
        assertEquals(3, backup.version); assertEquals(1, backup.importReceipts.size); assertEquals(2, backup.transactions.size)
    }
}
