package com.atlas.ledger.domain

import com.atlas.ledger.data.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

fun isActualTransaction(transaction: LedgerTransaction): Boolean = transaction.origin == null
fun transactionName(transaction: LedgerTransaction): String = transaction.note.ifBlank { transaction.category }.trim().replace(Regex("\\s+"), " ")
fun parseResidual(value: String): Long {
    if (value.isBlank()) return 0
    require(value.trim().matches(Regex("\\d{1,10}(\\.\\d{1,2})?"))) { "折旧价最多保留两位小数" }
    return BigDecimal(value.trim()).movePointRight(2).longValueExact().also { require(it in 0..MAX_AMOUNT_CENTS) { "折旧价超出范围" } }
}
fun durableCurrentCost(item: Durable, today: LocalDate): BigDecimal {
    val days = usedDays(item, today)
    val cents = if (item.expectedDays != null && days > item.expectedDays) item.amountCents - item.residualCents else item.amountCents
    return dailyCost(cents, days)
}
fun durableExpectedCost(item: Durable, today: LocalDate): BigDecimal = item.expectedDays?.let {
    dailyCost(item.amountCents - item.residualCents, it.toLong())
} ?: durableCurrentCost(item, today)
fun durableExpectedEnd(item: Durable): LocalDate? = item.expectedDays?.let { LocalDate.parse(item.startDate).plusDays(it.toLong() - 1) }

enum class RecordSort(val label: String) {
    PRICE_DESC("金额从高到低"), PRICE_ASC("金额从低到高"), DATE_DESC("日期从新到旧"), DATE_ASC("日期从旧到新"), NAME_ASC("名称顺序"),
}
private fun <T> sortRecords(values: List<T>, sort: RecordSort, amount: (T) -> Long, date: (T) -> String, name: (T) -> String, id: (T) -> String): List<T> {
    val comparator = when (sort) {
        RecordSort.PRICE_DESC -> compareByDescending(amount)
        RecordSort.PRICE_ASC -> compareBy(amount)
        RecordSort.DATE_DESC -> compareByDescending(date)
        RecordSort.DATE_ASC -> compareBy(date)
        RecordSort.NAME_ASC -> compareBy(name)
    }
    return values.sortedWith(comparator.thenBy(id))
}
fun sortedTransactions(values: List<LedgerTransaction>, sort: RecordSort) = sortRecords(values, sort, { it.amountCents }, { it.date }, ::transactionName, { it.id })
fun sortedDurables(values: List<Durable>, sort: RecordSort) = sortRecords(values, sort, { it.amountCents }, { it.purchaseDate }, { it.name }, { it.id })
fun shownPeriod(id: String, periods: List<SubscriptionPeriod>, today: LocalDate): SubscriptionPeriod? {
    val values = periods.filter { it.subscriptionId == id }
    return values.find { activeOn(it, today) } ?: values.filter { it.startDate > today.toString() }.minByOrNull { it.startDate } ?: values.maxByOrNull { it.startDate }
}
fun sortedSubscriptions(values: List<Subscription>, periods: List<SubscriptionPeriod>, today: LocalDate, sort: RecordSort): List<Subscription> {
    val shown = values.associate { it.id to shownPeriod(it.id, periods, today) }
    return sortRecords(values, sort, { shown[it.id]?.amountCents ?: 0 }, { shown[it.id]?.startDate ?: "" }, { it.name }, { it.id })
}
fun sortedRules(values: List<RecurringRule>, sort: RecordSort) = sortRecords(values, sort, { it.amountCents }, { it.startDate }, { it.name }, { it.id })

data class TransactionGroup(val key: String, val name: String, val transactions: List<LedgerTransaction>) {
    val amountCents: Long get() = transactions.sumOf { it.amountCents }
    val date: String get() = transactions.maxOf { it.date }
    val isExpense: Boolean get() = transactions.first().kind == "EXPENSE"
}
fun groupedTransactions(values: List<LedgerTransaction>, sort: RecordSort): List<TransactionGroup> {
    val groups = values.filter(::isActualTransaction).groupBy { if (it.kind == "EXPENSE") "expense:${transactionName(it)}" else "transaction:${it.id}" }
        .map { (key, transactions) -> TransactionGroup(key, transactionName(transactions.first()), transactions) }
    return sortRecords(groups, sort, { it.amountCents }, { it.date }, { it.name }, { it.key })
}

data class ForecastEntry(val ruleId: String, val name: String, val date: String, val amountCents: Long, val category: String, val skipped: Boolean) {
    val key: String get() = "$ruleId:$date"
}
fun forecastMonth(rules: List<RecurringRule>, occurrences: List<RecurringOccurrence>, month: YearMonth): List<ForecastEntry> {
    val skips = occurrences.filter { it.status == "SKIPPED" }.map { it.ruleId to it.date }.toSet()
    return rules.filter { it.enabled }.flatMap { rule ->
        val weekdays = rule.weekdays.split(',').map(String::toInt).toSet()
        (1..month.lengthOfMonth()).map { month.atDay(it) }.filter { day ->
            day.toString() >= rule.startDate && (rule.endDate == null || day.toString() <= rule.endDate) && day.dayOfWeek.value in weekdays
        }.map { day -> ForecastEntry(rule.id, rule.name, day.toString(), rule.amountCents, rule.category, (rule.id to day.toString()) in skips) }
    }
}
fun sortedForecasts(values: List<ForecastEntry>, sort: RecordSort) = sortRecords(values, sort, { it.amountCents }, { it.date }, { it.name }, { it.key })

fun sortedBillCandidates(values: List<BillCandidate>, sort: RecordSort) = sortRecords(values, sort, { it.transaction.amountCents }, { it.transaction.date }, { transactionName(it.transaction) }, { it.fingerprint })
