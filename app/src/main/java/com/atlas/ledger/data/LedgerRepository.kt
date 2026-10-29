package com.atlas.ledger.data

import androidx.room.withTransaction
import com.atlas.ledger.domain.*
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class LedgerRepository(private val db: LedgerDatabase) {
    private val dao = db.dao()
    val state = combine(dao.observeTransactions(), dao.observeSubscriptions(), dao.observePeriods(), dao.observeDurables()) { tx, sub, periods, items ->
        LedgerState(tx, sub, periods, items, loaded = true)
    }.combine(dao.observeRecurringRules()) { state, rules -> state.copy(recurringRules = rules) }
        .combine(dao.observeRecurringOccurrences()) { state, occurrences -> state.copy(recurringOccurrences = occurrences) }
        .combine(dao.observeImportReceipts()) { state, receipts -> state.copy(importReceipts = receipts) }
    private suspend fun snapshot() = LedgerBackup(exportedAt = Instant.now().toString(), transactions = dao.transactions(), subscriptions = dao.subscriptions(), periods = dao.periods(), durables = dao.durables(), recurringRules = dao.recurringRules(), recurringOccurrences = dao.recurringOccurrences(), importReceipts = dao.importReceipts())
    suspend fun exportJson(): String = db.withTransaction { encodeBackup(snapshot()) }
    suspend fun importJson(text: String): Int {
        val incoming = decodeBackup(text)
        return db.withTransaction {
            val current = snapshot()
            val merged = mergeBackup(current, incoming)
            merged.subscriptions.forEach { dao.saveSubscription(it) }
            merged.periods.forEach { dao.savePeriod(it) }
            merged.durables.forEach { dao.saveDurable(it) }
            merged.transactions.forEach { dao.saveTransaction(it) }
            merged.recurringRules.forEach { dao.saveRecurringRule(it) }
            merged.recurringOccurrences.forEach { dao.saveRecurringOccurrence(it) }
            merged.importReceipts.forEach { dao.saveImportReceipt(it) }
            (merged.transactions.size - current.transactions.size)
        }
    }
    suspend fun saveTransaction(value: LedgerTransaction) = db.withTransaction {
        require(value.origin == null) { "请在订阅或物品页面修改关联账单" }
        val current = snapshot()
        require(current.transactions.none { it.id == value.id && it.origin != null }) { "不能覆盖关联账单" }
        validateBackup(current.copy(transactions = current.transactions.filterNot { it.id == value.id } + value))
        dao.saveTransaction(value)
    }
    suspend fun saveSubscription(subscription: Subscription, period: SubscriptionPeriod) = db.withTransaction {
        require(period.subscriptionId == subscription.id) { "订阅关联不正确" }
        val current = snapshot()
        validateBackup(current.copy(
            subscriptions = current.subscriptions.filterNot { it.id == subscription.id } + subscription,
            periods = current.periods.filterNot { it.id == period.id } + period,
        ))
        dao.saveSubscription(subscription); dao.savePeriod(period)
    }
    suspend fun saveDurable(item: Durable) = db.withTransaction {
        val current = snapshot()
        validateBackup(current.copy(durables = current.durables.filterNot { it.id == item.id } + item))
        dao.saveDurable(item)
    }
    suspend fun deleteTransaction(id: String) = dao.deleteManualTransaction(id)
    suspend fun deleteSubscription(id: String) = db.withTransaction {
        dao.deleteSubscription(id)
    }
    suspend fun deletePeriod(id: String) = db.withTransaction {
        val period = dao.periods().find { it.id == id }
        dao.deletePeriod(id)
        if (period != null && dao.periods().none { it.subscriptionId == period.subscriptionId }) dao.deleteSubscription(period.subscriptionId)
    }
    suspend fun deleteDurable(id: String) = db.withTransaction {
        dao.deleteDurable(id)
    }
    suspend fun saveRecurringRule(rule: RecurringRule) = db.withTransaction {
        val value = rule.copy(nextDate = maxOf(rule.nextDate, rule.startDate))
        val current = snapshot()
        validateBackup(current.copy(recurringRules = current.recurringRules.filterNot { it.id == value.id } + value))
        dao.saveRecurringRule(value)
    }
    suspend fun skipRecurringDate(ruleId: String, date: String) = db.withTransaction {
        val rule = dao.recurringRule(ruleId) ?: error("固定记账规则不存在")
        val day = LocalDate.parse(date)
        require(day.year in 1900..2200 && date >= rule.startDate && (rule.endDate == null || date <= rule.endDate)) { "请选择规则有效期内的日期" }
        require(day.dayOfWeek.value in rule.weekdays.split(',').map(String::toInt)) { "该日期不在规则执行星期中" }
        val existing = dao.recurringOccurrence(ruleId, date)
        val occurrence = existing?.copy(status = "SKIPPED") ?: RecurringOccurrence(
            UUID.nameUUIDFromBytes("$ruleId:$date".toByteArray(Charsets.UTF_8)).toString(), ruleId, date, "SKIPPED", rule.name, rule.amountCents, rule.category, rule.account,
        )
        dao.saveRecurringOccurrence(occurrence)
    }
    suspend fun deleteRecurringRule(id: String) = dao.deleteRecurringRule(id)
    suspend fun restoreSkippedDate(ruleId: String, date: String) = db.withTransaction {
        val occurrence = dao.recurringOccurrence(ruleId, date) ?: return@withTransaction
        if (occurrence.status == "SKIPPED") {
            // A historical auto-record is preserved; restoring only affects the forecast marker.
            val hasHistory = dao.transactions().any { it.origin == "RECURRING" && it.originId == occurrence.id }
            if (hasHistory) dao.saveRecurringOccurrence(occurrence.copy(status = "BOOKED"))
            else dao.deleteSkippedOccurrence(occurrence.id)
        }
    }
    data class ReconcileResult(val created: List<RecurringOccurrence>, val processed: Int)
    // Compatibility for an already queued old worker: never materialize actual spending.
    suspend fun reconcileRecurring(now: LocalDateTime = LocalDateTime.now()): ReconcileResult = ReconcileResult(emptyList(), 0)
    suspend fun hasImported(source: String, fingerprint: String): Boolean = dao.importReceipt(source, fingerprint) != null
    suspend fun existingImportFingerprints(source: String, fingerprints: List<String>): Set<String> = fingerprints.distinct().chunked(400).flatMap { dao.existingImportFingerprints(source, it) }.toSet()
    suspend fun importBills(source: String, candidates: List<BillCandidate>): Int = db.withTransaction {
        require(source in listOf("ALIPAY", "WECHAT")) { "账单来源不受支持" }
        val current = snapshot()
        val known = current.importReceipts.filter { it.source == source }.map { it.fingerprint }.toMutableSet()
        val knownReceipts = current.importReceipts.filter { it.source == source }.associateBy { it.fingerprint }
        val knownIds = current.transactions.map { it.id }.toMutableSet()
        val transactions = mutableListOf<LedgerTransaction>()
        val receipts = mutableListOf<ImportReceipt>()
        for (candidate in candidates) {
            val old = knownReceipts[candidate.fingerprint]
            require(old == null || old.contentHash.isEmpty() || old.contentHash == candidate.contentHash) { "同一交易编号的原始内容已变化，请核对后手动修改" }
            if (!known.add(candidate.fingerprint)) continue
            val tx = candidate.transaction.copy(id = UUID.nameUUIDFromBytes("$source:${candidate.fingerprint}".toByteArray(Charsets.UTF_8)).toString())
            require(tx.origin == null && tx.originId == null && tx.kind in listOf("INCOME", "EXPENSE")) { "导入文件包含不支持的账单类型" }
            if (knownIds.add(tx.id)) transactions += tx
            receipts += ImportReceipt(newId(), source, candidate.fingerprint, tx.id, Instant.now().toString(), candidate.contentHash)
        }
        validateBackup(current.copy(transactions = current.transactions + transactions, importReceipts = current.importReceipts + receipts))
        transactions.forEach { dao.saveTransaction(it) }
        receipts.forEach { dao.saveImportReceipt(it) }
        transactions.size
    }
    suspend fun possibleDuplicateBills(candidates: List<BillCandidate>): Set<String> {
        val keys = dao.transactions().filter(::isActualTransaction).map { Triple(it.date, it.kind, it.amountCents) }.toSet()
        return candidates.filter { Triple(it.transaction.date, it.transaction.kind, it.transaction.amountCents) in keys }.map { it.fingerprint }.toSet()
    }
    suspend fun conflictingImportBills(source: String, candidates: List<BillCandidate>): Set<String> {
        val receipts = dao.importReceipts().filter { it.source == source }.associateBy { it.fingerprint }
        return candidates.filter { candidate -> receipts[candidate.fingerprint]?.let { it.contentHash.isNotEmpty() && it.contentHash != candidate.contentHash } == true }.map { it.fingerprint }.toSet()
    }
    companion object { fun newId(): String = UUID.randomUUID().toString() }
}
