package com.atlas.ledger.domain

import com.atlas.ledger.data.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

const val MAX_AMOUNT_CENTS = 99_999_999_999L
val expenseCategories = listOf("餐饮", "交通", "购物", "居住", "娱乐", "医疗", "学习", "订阅", "耐用品", "其他")
val incomeCategories = listOf("工资", "奖金", "兼职", "退款", "其他")
val accounts = listOf("微信", "支付宝", "银行卡", "现金")

fun parseAmount(value: String): Long {
    require(value.trim().matches(Regex("\\d{1,10}(\\.\\d{1,2})?"))) { "请输入金额，最多保留两位小数" }
    val cents = BigDecimal(value.trim()).movePointRight(2).longValueExact()
    require(cents in 1..MAX_AMOUNT_CENTS) { "金额必须大于 0 且不超过 999,999,999.99 元" }
    return cents
}

fun amountInput(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()
fun money(cents: Long): String = "¥" + amountInput(cents)
fun dailyCost(cents: Long, days: Long): BigDecimal = if (days <= 0) BigDecimal.ZERO.setScale(2)
    else BigDecimal.valueOf(cents, 2).divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP)
fun dailyMoney(cents: Long, days: Long): String = "¥${dailyCost(cents, days).toPlainString()}"

fun periodDays(period: SubscriptionPeriod): Long = ChronoUnit.DAYS.between(LocalDate.parse(period.startDate), LocalDate.parse(period.endExclusive))
fun activeOn(period: SubscriptionPeriod, today: LocalDate): Boolean = today >= LocalDate.parse(period.startDate) && today < LocalDate.parse(period.endExclusive)
fun remainingDays(period: SubscriptionPeriod, today: LocalDate): Long = ChronoUnit.DAYS.between(today, LocalDate.parse(period.endExclusive)).coerceAtLeast(0)
fun usedDays(item: Durable, today: LocalDate): Long {
    val start = LocalDate.parse(item.startDate)
    val end = item.stoppedDate?.let(LocalDate::parse)?.coerceAtMost(today) ?: today
    return if (end < start) 0 else ChronoUnit.DAYS.between(start, end) + 1
}

data class DailyConsumptionTotals(
    val current: BigDecimal,
    val expected: BigDecimal,
    val subscriptions: Int,
    val durables: Int,
    val fallbackDurables: Int,
    val fixed: Int = 0,
)

/** Analysis only: active service and item costs never create additional transactions. */
fun dailyConsumption(periods: List<SubscriptionPeriod>, durables: List<Durable>, today: LocalDate, rules: List<RecurringRule> = emptyList(), occurrences: List<RecurringOccurrence> = emptyList()): DailyConsumptionTotals {
    val active = periods.filter { activeOn(it, today) }
    val used = durables.map { it to usedDays(it, today) }.filter { (item, days) ->
        days > 0 && (item.stoppedDate == null || LocalDate.parse(item.stoppedDate) >= today)
    }
    val subscriptions = active.fold(BigDecimal.ZERO) { total, period -> total + dailyCost(period.amountCents, periodDays(period)) }
    val current = used.fold(subscriptions) { total, (item, _) -> total + durableCurrentCost(item, today) }
    val expected = used.fold(subscriptions) { total, (item, _) -> total + durableExpectedCost(item, today) }
    val fixed = rules.filter { it.enabled && it.startDate <= today.toString() && (it.endDate == null || it.endDate >= today.toString()) }
    val fixedToday = fixed.filter { today.dayOfWeek.value in it.weekdays.split(',').map(String::toInt) && occurrences.none { occurrence -> occurrence.ruleId == it.id && occurrence.date == today.toString() && occurrence.status == "SKIPPED" } }.sumOf { it.amountCents }
    val fixedAverage = fixed.fold(BigDecimal.ZERO) { sum, rule -> sum + dailyCost(rule.amountCents * rule.weekdays.split(',').size, 7) }
    return DailyConsumptionTotals((current + BigDecimal.valueOf(fixedToday, 2)).setScale(2), (expected + fixedAverage).setScale(2), active.size, used.size, used.count { it.first.expectedDays == null }, fixed.size)
}

fun monthTotals(transactions: List<LedgerTransaction>, today: LocalDate): Pair<Long, Long> {
    val month = transactions.filter { isActualTransaction(it) && LocalDate.parse(it.date) in today.withDayOfMonth(1)..today }
    return month.filter { it.kind == "INCOME" }.sumOf { it.amountCents } to
        month.filter { it.kind == "EXPENSE" }.sumOf { it.amountCents }
}

/** Full graph validation is also applied before an import writes any rows. */
fun validateBackup(backup: LedgerBackup) {
    require(backup.format == "daily-ledger" && backup.version in 1..3) { "不支持的备份格式或版本" }
    require(backup.transactions.size + backup.subscriptions.size + backup.periods.size + backup.durables.size + backup.recurringRules.size + backup.recurringOccurrences.size + backup.importReceipts.size <= 50_000) { "备份记录过多" }
    fun unique(ids: List<String>) {
        require(ids.all { it.isNotBlank() && it.length <= 100 } && ids.distinct().size == ids.size) { "备份包含空白或重复的记录 ID" }
    }
    fun nonBlank(value: String) { require(value.isNotBlank() && value.length <= 100) { "名称、分类和账户不能为空，最多 100 个字" } }
    fun amount(value: Long) { require(value in 1..MAX_AMOUNT_CENTS) { "金额超出范围" } }
    fun date(value: String): LocalDate {
        val parsed = runCatching { LocalDate.parse(value) }.getOrElse { throw IllegalArgumentException("日期格式错误") }
        require(parsed.year in 1900..2200 && parsed.toString() == value) { "日期必须在 1900 至 2200 年之间" }
        return parsed
    }
    unique(backup.transactions.map { it.id }); unique(backup.subscriptions.map { it.id })
    unique(backup.periods.map { it.id }); unique(backup.durables.map { it.id })
    unique(backup.recurringRules.map { it.id }); unique(backup.recurringOccurrences.map { it.id }); unique(backup.importReceipts.map { it.id })
    val subscriptionIds = backup.subscriptions.map { it.id }.toSet()
    val periodById = backup.periods.associateBy { it.id }
    val durableById = backup.durables.associateBy { it.id }
    val ruleById = backup.recurringRules.associateBy { it.id }
    backup.subscriptions.forEach { nonBlank(it.name); nonBlank(it.category); require(it.billingCycle in listOf("周付", "月付", "季付", "年付", "自定义")) { "订阅周期不正确" }; require(it.note.length <= 1000) { "备注最多 1000 个字" } }
    backup.periods.forEach {
        require(it.subscriptionId in subscriptionIds) { "订阅周期缺少对应订阅" }
        amount(it.amountCents); require(date(it.endExclusive) > date(it.startDate)) { "订阅到期日期不能早于生效日期" }
    }
    backup.periods.groupBy { it.subscriptionId }.values.forEach { periods ->
        periods.sortedBy { it.startDate }.zipWithNext().forEach { (a, b) ->
            require(a.endExclusive <= b.startDate) { "同一订阅的有效期不能重叠" }
        }
    }
    backup.subscriptions.forEach { sub -> require(backup.periods.any { it.subscriptionId == sub.id }) { "订阅缺少付费周期" } }
    backup.durables.forEach {
        nonBlank(it.name); nonBlank(it.category); amount(it.amountCents)
        require(date(it.startDate) >= date(it.purchaseDate)) { "开始使用日期不能早于购入日期" }
        require(it.residualCents in 0..it.amountCents) { "折旧价应在 0 与购买价之间" }
        require(it.expectedDays == null || it.expectedDays in 1..36500) { "预计使用天数应为 1 至 36500" }
        require(it.stoppedDate == null || date(it.stoppedDate) >= date(it.startDate)) { "停用日期不能早于开始使用日期" }
        require(it.note.length <= 1000) { "备注最多 1000 个字" }
    }
    backup.recurringRules.forEach { rule ->
        nonBlank(rule.name); nonBlank(rule.category); nonBlank(rule.account); amount(rule.amountCents)
        val days = rule.weekdays.split(',').mapNotNull { it.toIntOrNull() }
        require(days.isNotEmpty() && days.size == rule.weekdays.split(',').size && days.all { it in 1..7 } && days.distinct().size == days.size) { "固定记账的星期设置不正确" }
        require(runCatching { java.time.LocalTime.parse(rule.time) }.isSuccess) { "固定记账时间不正确" }
        require(date(rule.nextDate) >= date(rule.startDate)) { "固定记账执行日期不正确" }
        require(rule.endDate == null || date(rule.endDate) >= date(rule.startDate)) { "固定记账结束日期不正确" }
    }
    backup.recurringOccurrences.forEach { occurrence ->
        require(occurrence.ruleId in ruleById) { "自动记账记录缺少规则" }
        date(occurrence.date); require(occurrence.status in listOf("BOOKED", "SKIPPED")) { "自动记账状态不正确" }
        nonBlank(occurrence.name); nonBlank(occurrence.category); nonBlank(occurrence.account); amount(occurrence.amountCents)
    }
    require(backup.recurringOccurrences.map { it.ruleId to it.date }.distinct().size == backup.recurringOccurrences.size) { "同一固定规则的同一天不能重复" }
    backup.importReceipts.forEach {
        require(it.source in listOf("ALIPAY", "WECHAT")) { "导入账单来源不正确" }
        require(it.fingerprint.isNotBlank() && it.fingerprint.length <= 160) { "导入账单标识不正确" }
        require(it.transactionId == null || it.transactionId.length <= 100) { "导入账单关联不正确" }
        require(it.contentHash.isEmpty() || it.contentHash.matches(Regex("[0-9a-f]{64}"))) { "导入账单内容标识不正确" }
    }
    backup.transactions.forEach { transaction ->
        require(transaction.kind in listOf("EXPENSE", "INCOME", "TRANSFER")) { "账单类型不正确" }
        amount(transaction.amountCents); date(transaction.date); nonBlank(transaction.category); nonBlank(transaction.account)
        require(transaction.note.length <= 1000) { "备注最多 1000 个字" }
        if (transaction.kind == "TRANSFER") { nonBlank(transaction.targetAccount); require(transaction.targetAccount != transaction.account) { "转出和转入账户不能相同" } }
        when (transaction.origin) {
            null -> require(transaction.originId == null) { "账单关联不正确" }
            "SUBSCRIPTION", "DURABLE", "RECURRING" -> {
                require(transaction.kind == "EXPENSE" && (!transaction.originId.isNullOrBlank() && transaction.originId.length <= 100)) { "旧版预期历史的来源不正确" }
                if (backup.version < 3) {
                    when (transaction.origin) {
                        "SUBSCRIPTION" -> {
                            val period = periodById[transaction.originId] ?: throw IllegalArgumentException("账单缺少对应订阅周期")
                            require(transaction.amountCents == period.amountCents) { "订阅账单与周期金额不一致" }
                        }
                        "DURABLE" -> {
                            val item = durableById[transaction.originId] ?: throw IllegalArgumentException("账单缺少对应物品")
                            require(transaction.amountCents == item.amountCents && transaction.date == item.purchaseDate) { "物品账单与购入信息不一致" }
                        }
                        "RECURRING" -> {
                            val occurrence = backup.recurringOccurrences.find { it.id == transaction.originId } ?: throw IllegalArgumentException("账单缺少自动记录")
                            require(occurrence.status == "BOOKED" && transaction.amountCents == occurrence.amountCents && transaction.date == occurrence.date) { "自动账单与记录不一致" }
                        }
                    }
                }
            }
            else -> throw IllegalArgumentException("未知的账单关联类型")
        }
    }
    if (backup.version < 3) {
        val links = backup.transactions.filter { it.origin != null }.groupBy { it.origin to it.originId }
        backup.periods.forEach { require(links["SUBSCRIPTION" to it.id]?.size == 1) { "订阅周期必须对应一笔支出" } }
        backup.durables.forEach { require(links["DURABLE" to it.id]?.size == 1) { "物品必须对应一笔购入支出" } }
        backup.recurringOccurrences.forEach { occurrence ->
            val linked = links["RECURRING" to occurrence.id].orEmpty()
            if (occurrence.status == "BOOKED") require(linked.size == 1) { "已记自动账单必须对应一笔支出" }
            else require(linked.isEmpty()) { "已取消的自动账单不能保留支出" }
        }
    }
    val receiptKeys = backup.importReceipts.map { it.source to it.fingerprint }
    require(receiptKeys.distinct().size == receiptKeys.size) { "导入凭据重复" }
}

/** Merge stable IDs; conflicts are rejected, never silently overwritten. */
fun mergeBackup(current: LedgerBackup, incoming: LedgerBackup): LedgerBackup {
    validateBackup(current); validateBackup(incoming)
    fun <T> merge(a: List<T>, b: List<T>, id: (T) -> String): List<T> {
        val result = a.associateBy(id).toMutableMap()
        b.forEach { item ->
            val previous = result[id(item)]
            require(previous == null || previous == item) { "备份与本机的同一条记录内容不同；为保护数据，已取消整个导入" }
            result[id(item)] = item
        }
        return result.values.toList()
    }
    return current.copy(
        transactions = merge(current.transactions, incoming.transactions) { it.id },
        subscriptions = merge(current.subscriptions, incoming.subscriptions) { it.id },
        periods = merge(current.periods, incoming.periods) { it.id },
        durables = merge(current.durables, incoming.durables) { it.id },
        recurringRules = merge(current.recurringRules, incoming.recurringRules) { it.id },
        recurringOccurrences = merge(current.recurringOccurrences, incoming.recurringOccurrences) { it.id },
        importReceipts = merge(current.importReceipts, incoming.importReceipts) { it.id },
    ).also(::validateBackup)
}
