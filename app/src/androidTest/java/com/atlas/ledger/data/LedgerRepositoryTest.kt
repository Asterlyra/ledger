package com.atlas.ledger.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.atlas.ledger.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class LedgerRepositoryTest {
    private lateinit var db: LedgerDatabase
    private lateinit var repo: LedgerRepository
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java).build(); repo = LedgerRepository(db) }
    @After fun close() { db.close() }
    private val sub = Subscription("s", "测试订阅")
    private val period = SubscriptionPeriod("p", "s", 3100, "2026-10-01", "2026-11-01")
    private val history = LedgerTransaction("t", "EXPENSE", 3100, "2026-09-30", "订阅", "微信", origin = "SUBSCRIPTION", originId = "p")
    private val item = Durable("d", "测试手机", amountCents = 300000, purchaseDate = "2026-10-01", startDate = "2026-10-05", expectedDays = 1000)
    private fun legacyGraph(): LedgerBackup {
        val purchase = LedgerTransaction("dt", "EXPENSE", item.amountCents, item.purchaseDate, "耐用品", "银行卡", origin = "DURABLE", originId = item.id)
        val rule = RecurringRule("r", "历史公交", 200, weekdays = "1,2,3,4,5,6,7", startDate = "2026-10-01")
        val booked = RecurringOccurrence("o", rule.id, "2026-10-02", "BOOKED", rule.name, rule.amountCents, rule.category, rule.account)
        val fixed = LedgerTransaction("rt", "EXPENSE", 200, booked.date, rule.category, rule.account, origin = "RECURRING", originId = booked.id)
        val manual = LedgerTransaction("manual", "EXPENSE", 500, "2026-10-01", "餐饮", "微信", note = "实际午餐")
        val receipt = ImportReceipt("receipt", "WECHAT", "old-trade", manual.id, "2026-10-01T00:00:00Z", "0".repeat(64))
        return LedgerBackup(version = 2, transactions = listOf(history, purchase, fixed, manual), subscriptions = listOf(sub), periods = listOf(period), durables = listOf(item), recurringRules = listOf(rule), recurringOccurrences = listOf(booked), importReceipts = listOf(receipt))
    }
    @Test fun savingForecastsNeverCreatesActualOrHistoryTransactions() = runBlocking {
        repo.saveSubscription(sub, period); repo.saveDurable(item.copy(residualCents = 100000))
        assertEquals(1, db.dao().periods().size); assertEquals(1, db.dao().durables().size); assertTrue(db.dao().transactions().isEmpty())
        repo.saveSubscription(sub, period.copy(amountCents = 5000)); repo.saveDurable(item.copy(amountCents = 200000))
        assertTrue(db.dao().transactions().isEmpty())
    }
    @Test fun rejectedOverlapDoesNotPartiallyWrite() = runBlocking {
        repo.saveSubscription(sub, period)
        try { repo.saveSubscription(sub.copy(name = "不应写入"), period.copy(id = "p2")); fail("Should reject overlap") } catch (_: IllegalArgumentException) { }
        assertEquals(sub, db.dao().subscriptions().single()); assertEquals(1, db.dao().periods().size); assertTrue(db.dao().transactions().isEmpty())
    }
    @Test fun invalidResidualDoesNotChangeExistingItem() = runBlocking {
        repo.saveDurable(item)
        try { repo.saveDurable(item.copy(residualCents = item.amountCents + 1)); fail("Invalid residual") } catch (_: IllegalArgumentException) { }
        assertEquals(item, db.dao().durables().single())
    }
    @Test fun forecastAndAllHistorySurviveExportRestoreAndRepeatImport() = runBlocking {
        val old = legacyGraph(); repo.importJson(encodeBackup(old))
        repo.saveDurable(item.copy(residualCents = 100000))
        repo.saveSubscription(Subscription("new-s", "新预期"), SubscriptionPeriod("new-p", "new-s", 700, "2027-01-01", "2027-01-08"))
        val text = repo.exportJson(); val backup = decodeBackup(text)
        assertEquals(3, backup.version); assertEquals(old.transactions.toSet(), backup.transactions.toSet()); assertEquals(100000L, backup.durables.single().residualCents)
        val other = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java).build()
        try {
            val restored = LedgerRepository(other); assertEquals(4, restored.importJson(text)); assertEquals(0, restored.importJson(text))
            assertEquals(backup.copy(exportedAt = ""), decodeBackup(restored.exportJson()).copy(exportedAt = ""))
        } finally { other.close() }
    }
    @Test fun editingAndDeletingExpectedPlansPreservesLegacyHistoryAndActualBills() = runBlocking {
        val old = legacyGraph(); repo.importJson(encodeBackup(old))
        repo.saveSubscription(sub.copy(name = "新名字"), period.copy(amountCents = 5000, startDate = "2026-10-02"))
        repo.saveDurable(item.copy(amountCents = 200000, purchaseDate = "2026-10-02", startDate = "2026-10-02", residualCents = 50000))
        repo.saveRecurringRule(old.recurringRules.single().copy(amountCents = 400))
        assertEquals(old.transactions.toSet(), db.dao().transactions().toSet())
        repo.deleteSubscription(sub.id); repo.deleteDurable(item.id); repo.deleteRecurringRule("r")
        assertEquals(old.transactions.toSet(), db.dao().transactions().toSet())
        validateBackup(decodeBackup(repo.exportJson())) // Orphaned legacy origins are intentional immutable history.
    }
    @Test fun oldLinkedHistoryIsExcludedWhileManualActualSpendingRemains() = runBlocking {
        val old = legacyGraph(); repo.importJson(encodeBackup(old))
        assertEquals(0L to 500L, monthTotals(db.dao().transactions(), LocalDate.parse("2026-10-14")))
        assertEquals(3, db.dao().transactions().count { !isActualTransaction(it) })
        assertEquals(4, db.dao().transactions().size)
    }
    @Test fun actualEditingCannotOverwriteArchive() = runBlocking {
        repo.importJson(encodeBackup(legacyGraph()))
        try { repo.saveTransaction(history.copy(origin = null, originId = null)); fail("Must protect history") } catch (_: IllegalArgumentException) { }
        assertEquals(history, db.dao().transactions().single { it.id == history.id })
    }
    @Test fun lastPeriodDeletionRemovesEmptyPlanButKeepsArchive() = runBlocking {
        repo.importJson(encodeBackup(legacyGraph())); repo.deletePeriod(period.id)
        assertTrue(db.dao().subscriptions().isEmpty()); assertTrue(db.dao().periods().isEmpty()); assertEquals(4, db.dao().transactions().size)
    }
    @Test fun conflictImportRollsBackCompletely() = runBlocking {
        repo.importJson(encodeBackup(legacyGraph())); val before = decodeBackup(repo.exportJson()).copy(exportedAt = "")
        try { repo.importJson(repo.exportJson().replace("测试订阅", "冲突订阅")); fail("Must reject") } catch (_: IllegalArgumentException) { }
        assertEquals(before, decodeBackup(repo.exportJson()).copy(exportedAt = ""))
    }
    @Test fun malformedImportLeavesAllDataIntact() = runBlocking {
        repo.importJson(encodeBackup(legacyGraph())); val before = decodeBackup(repo.exportJson()).copy(exportedAt = "")
        try { repo.importJson("{}"); fail("Must reject") } catch (_: IllegalArgumentException) { }
        assertEquals(before, decodeBackup(repo.exportJson()).copy(exportedAt = ""))
    }
    @Test fun legacyDatesArePreservedWhenOnlyForecastNotesChange() = runBlocking {
        repo.importJson(encodeBackup(legacyGraph())); repo.saveSubscription(sub.copy(note = "预期"), period); repo.saveDurable(item.copy(note = "预期"))
        assertEquals("2026-09-30", db.dao().transactions().single { it.id == history.id }.date)
        assertEquals("2026-10-05", db.dao().durables().single().startDate)
    }
}
