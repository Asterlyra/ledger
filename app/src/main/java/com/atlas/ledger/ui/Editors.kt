package com.atlas.ledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atlas.ledger.LedgerViewModel
import com.atlas.ledger.data.*
import com.atlas.ledger.domain.*
import java.time.LocalDate

@Composable fun LedgerEditor(key: String, state: LedgerState, today: LocalDate, vm: LedgerViewModel, onDismiss: () -> Unit, onDeletePeriod: (String) -> Unit) {
    val type = key.substringBefore(':')
    val id = key.substringAfter(':')
    when (type) {
        "transaction" -> TransactionEditor(state.transactions.find { it.id == id }, today, vm, onDismiss)
        "subscription" -> SubscriptionEditor(null, null, state, today, vm, onDismiss, onDeletePeriod)
        "renew" -> SubscriptionEditor(state.subscriptions.find { it.id == id }, null, state, today, vm, onDismiss, onDeletePeriod)
        "period" -> {
            val period = state.periods.find { it.id == id }
            if (period != null) SubscriptionEditor(state.subscriptions.find { it.id == period.subscriptionId }, period, state, today, vm, onDismiss, onDeletePeriod)
        }
        "durable" -> DurableEditor(state.durables.find { it.id == id }, state, today, vm, onDismiss)
        "recurring" -> RecurringEditor(state.recurringRules.find { it.id == id }, today, vm, onDismiss)
        "skip-rule" -> SkipDateEditor(id, today, vm, onDismiss)

    }
}

@Composable private fun SkipDateEditor(ruleId: String, today: LocalDate, vm: LedgerViewModel, onDismiss: () -> Unit) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    var date by rememberSaveable { mutableStateOf(today.toString()) }
    FormDialog("跳过一天", busy, null, onDismiss, onSave = {
        vm.skipRecurringDate(ruleId, date, onDismiss)
    }) {
        DateField("跳过日期", date) { date = it }
        Text("仅取消这一天的预期，不删除旧版历史，也不改变正式账单。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun RecurringEditor(existing: RecurringRule?, today: LocalDate, vm: LedgerViewModel, onDismiss: () -> Unit) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val id = rememberSaveable { existing?.id ?: LedgerRepository.newId() }
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var amount by rememberSaveable { mutableStateOf(existing?.let { amountInput(it.amountCents) } ?: "") }
    var category by rememberSaveable { mutableStateOf(existing?.category ?: "交通") }
    var weekdays by rememberSaveable { mutableStateOf(existing?.weekdays?.split(',')?.mapNotNull(String::toIntOrNull)?.toSet() ?: (1..7).toSet()) }
    var start by rememberSaveable { mutableStateOf(existing?.startDate ?: today.toString()) }
    var hasEnd by rememberSaveable { mutableStateOf(existing?.endDate != null) }
    var end by rememberSaveable { mutableStateOf(existing?.endDate ?: today.plusMonths(1).toString()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val dayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    FormDialog(if (existing == null) "添加固定消费预期" else "编辑固定消费预期", busy, error, onDismiss, onSave = {
        runCatching {
            require(name.isNotBlank()) { "请输入内容名称，例如公交、地铁" }
            require(weekdays.isNotEmpty()) { "请选择至少一个星期" }
            val startDate = LocalDate.parse(start).toString()
            val rule = RecurringRule(id, name.trim(), parseAmount(amount), category, existing?.account ?: "现金", weekdays.sorted().joinToString(","), existing?.time ?: "00:00", startDate, if (hasEnd) end else null, maxOf(existing?.nextDate ?: startDate, startDate), existing?.enabled ?: true)
            error = null
            vm.saveRecurringRule(rule, onSuccess = onDismiss)
        }.onFailure { error = it.message ?: "请检查输入" }
    }) {
        TextField(name, { name = it }, "内容，例如公交、地铁")
        TextField(amount, { amount = it }, "每次金额（元）", numeric = true)
        Text("预期发生星期", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(weekdays.size == 7, { weekdays = (1..7).toSet() }, label = { Text("每天") })
            FilterChip(weekdays == (1..5).toSet(), { weekdays = (1..5).toSet() }, label = { Text("工作日") })
        }
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..7).forEach { day -> FilterChip(day in weekdays, { weekdays = if (day in weekdays) weekdays - day else weekdays + day }, label = { Text(dayNames[day - 1]) }) }
        }
        Text("分类", style = MaterialTheme.typography.labelLarge)
        Choices((expenseCategories + category).distinct(), category) { category = it }
        DateField("开始日期", start) { start = it }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("设置结束日期"); Switch(hasEnd, { hasEnd = it }) }
        if (hasEnd) DateField("结束日期（含当天）", end) { end = it }
        Text("仅生成可取消的预期安排。实际发生的消费请在账单页手动记录或导入。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun paymentDate(value: String, today: LocalDate): String {
    val parsed = runCatching { LocalDate.parse(value) }.getOrElse { throw IllegalArgumentException("请选择有效的支付日期") }
    require(parsed <= today) { "支付日期不能在未来，请在实际收付款后记账" }
    return parsed.toString()
}

@Composable private fun TransactionEditor(existing: LedgerTransaction?, today: LocalDate, vm: LedgerViewModel, onDismiss: () -> Unit) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    var kind by rememberSaveable { mutableStateOf(existing?.kind ?: "EXPENSE") }
    var amount by rememberSaveable { mutableStateOf(existing?.let { amountInput(it.amountCents) } ?: "") }
    var date by rememberSaveable { mutableStateOf(existing?.date ?: today.toString()) }
    var category by rememberSaveable { mutableStateOf(existing?.category ?: "餐饮") }
    var account by rememberSaveable { mutableStateOf(existing?.account ?: accounts.first()) }
    var targetAccount by rememberSaveable { mutableStateOf(existing?.targetAccount?.ifBlank { "银行卡" } ?: "银行卡") }
    var note by rememberSaveable { mutableStateOf(existing?.note ?: "") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val id = rememberSaveable { existing?.id ?: LedgerRepository.newId() }
    val categories = if (kind == "INCOME") incomeCategories else expenseCategories
    FormDialog(if (existing == null) "记一笔" else "编辑账单", busy, error, onDismiss, onSave = {
        runCatching {
            val value = LedgerTransaction(id, kind, parseAmount(amount), paymentDate(date, today), if (kind == "TRANSFER") "转账" else category, account, if (kind == "TRANSFER") targetAccount else "", note.trim())
            require(kind != "TRANSFER" || account != targetAccount) { "转出和转入账户不能相同" }
            error = null
            vm.perform(onSuccess = onDismiss) { vm.repository.saveTransaction(value) }
        }.onFailure { error = it.message }
    }) {
        Choices(listOf("支出", "收入", "转账"), when (kind) { "INCOME" -> "收入"; "TRANSFER" -> "转账"; else -> "支出" }) {
            kind = when (it) { "收入" -> "INCOME"; "转账" -> "TRANSFER"; else -> "EXPENSE" }
            category = if (kind == "INCOME") incomeCategories.first() else expenseCategories.first()
        }
        TextField(amount, { amount = it }, "金额（元）", numeric = true)
        DateField("日期", date) { date = it }
        if (kind != "TRANSFER") {
            Text("分类", style = MaterialTheme.typography.labelLarge)
            Choices((categories + category).distinct(), category) { category = it }
        }
        Text(if (kind == "TRANSFER") "转出账户" else "账户", style = MaterialTheme.typography.labelLarge)
        Choices((accounts + account).distinct(), account) { account = it }
        if (kind == "TRANSFER") {
            Text("转入账户", style = MaterialTheme.typography.labelLarge)
            Choices((accounts + targetAccount).distinct(), targetAccount) { targetAccount = it }
        }
        TextField(note, { note = it }, "备注（可选）", multiline = true)
        Text("备注作为消费名称，用于同名消费分组；留空时使用分类名称。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun cycleEnd(start: LocalDate, cycle: String): LocalDate = when (cycle) {
    "周付" -> start.plusWeeks(1).minusDays(1)
    "年付" -> start.plusYears(1).minusDays(1)
    "季付" -> start.plusMonths(3).minusDays(1)
    else -> start.plusMonths(1).minusDays(1)
}

@Composable private fun SubscriptionEditor(existing: Subscription?, existingPeriod: SubscriptionPeriod?, state: LedgerState, today: LocalDate, vm: LedgerViewModel, onDismiss: () -> Unit, onDeletePeriod: (String) -> Unit) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val previousPeriods = state.periods.filter { it.subscriptionId == existing?.id }
    val suggestedStart = previousPeriods.maxOfOrNull { LocalDate.parse(it.endExclusive) }?.coerceAtLeast(today) ?: today
    val subscriptionId = rememberSaveable { existing?.id ?: LedgerRepository.newId() }
    val periodId = rememberSaveable { existingPeriod?.id ?: LedgerRepository.newId() }
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var category by rememberSaveable { mutableStateOf(existing?.category ?: "数字服务") }
    var cycle by rememberSaveable { mutableStateOf(existing?.billingCycle ?: "月付") }
    var autoRenew by rememberSaveable { mutableStateOf(existing?.autoRenew ?: false) }
    var amount by rememberSaveable { mutableStateOf(existingPeriod?.let { amountInput(it.amountCents) } ?: previousPeriods.maxByOrNull { it.startDate }?.let { amountInput(it.amountCents) } ?: "") }
    var start by rememberSaveable { mutableStateOf(existingPeriod?.startDate ?: suggestedStart.toString()) }
    var end by rememberSaveable { mutableStateOf(existingPeriod?.endExclusive?.let { LocalDate.parse(it).minusDays(1).toString() } ?: cycleEnd(suggestedStart, cycle).toString()) }
    var note by rememberSaveable { mutableStateOf(existing?.note ?: "") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val title = when { existingPeriod != null -> "编辑订阅周期"; existing != null -> "添加周期"; else -> "添加订阅" }
    FormDialog(title, busy, error, onDismiss, onSave = {
        runCatching {
            val cents = parseAmount(amount)
            val startDate = LocalDate.parse(start)
            val endExclusive = LocalDate.parse(end).plusDays(1)
            require(endExclusive > startDate) { "到期日期不能早于生效日期" }
            require(name.isNotBlank()) { "请输入订阅名称" }
            val sub = Subscription(subscriptionId, name.trim(), category, cycle, autoRenew, note.trim())
            val period = SubscriptionPeriod(periodId, subscriptionId, cents, startDate.toString(), endExclusive.toString())
            error = null
            vm.perform(onSuccess = onDismiss) { vm.repository.saveSubscription(sub, period) }
        }.onFailure { error = it.message ?: "请检查输入" }
    }) {
        TextField(name, { name = it }, "订阅名称，例如视频、云存储")
        Text("服务分类", style = MaterialTheme.typography.labelLarge)
        Choices((listOf("数字服务", "影音", "软件", "云存储", "学习", "生活服务", "其他") + category).distinct(), category) { category = it }
        Text("付费周期", style = MaterialTheme.typography.labelLarge)
        Choices(listOf("周付", "月付", "季付", "年付", "自定义"), cycle) { cycle = it; if (it != "自定义") end = cycleEnd(LocalDate.parse(start), it).toString() }
        TextField(amount, { amount = it }, "本周期价格（元）", numeric = true)
        DateField("生效日期", start) { start = it; if (cycle != "自定义") end = cycleEnd(LocalDate.parse(it), cycle).toString() }
        DateField("到期日期（含当天）", end) { end = it }
        val days = runCatching { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(end)) + 1 }.getOrDefault(0)
        if (days > 0) Text("有效期 $days 天${runCatching { " · ${dailyMoney(parseAmount(amount), days)} / 天" }.getOrDefault("")}", color = accentColor(LedgerAccent.Subscription), style = MaterialTheme.typography.bodyMedium)
        Text("订阅仅用于预期成本，不会生成正式消费账单。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) { Text("已开启自动续费"); Text("仅作记录，不会自动生成账单", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Switch(autoRenew, { autoRenew = it })
        }
        TextField(note, { note = it }, "订阅备注（可选）", multiline = true)
        Text("名称、服务分类和备注应用于整个订阅；周期金额独立保存，旧版预期历史保持原值。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (existingPeriod != null) TextButton({ onDeletePeriod(existingPeriod.id) }, enabled = !busy) { Text("删除这个预期周期", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable private fun DurableEditor(existing: Durable?, state: LedgerState, today: LocalDate, vm: LedgerViewModel, onDismiss: () -> Unit) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val id = rememberSaveable { existing?.id ?: LedgerRepository.newId() }
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var category by rememberSaveable { mutableStateOf(existing?.category ?: "数码") }
    var amount by rememberSaveable { mutableStateOf(existing?.let { amountInput(it.amountCents) } ?: "") }
    var purchase by rememberSaveable { mutableStateOf(existing?.purchaseDate ?: today.toString()) }
    var residual by rememberSaveable { mutableStateOf(existing?.let { amountInput(it.residualCents) } ?: "0") }
    var expected by rememberSaveable { mutableStateOf(existing?.expectedDays?.toString() ?: "") }
    var stopped by rememberSaveable { mutableStateOf(existing?.stoppedDate != null) }
    var stoppedDate by rememberSaveable { mutableStateOf(existing?.stoppedDate ?: today.toString()) }
    var note by rememberSaveable { mutableStateOf(existing?.note ?: "") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    FormDialog(if (existing == null) "添加耐用品" else "编辑耐用品", busy, error, onDismiss, onSave = {
        runCatching {
            val cents = parseAmount(amount)
            require(name.isNotBlank()) { "请输入物品名称" }
            val expectedDays = if (expected.isBlank()) null else expected.toIntOrNull() ?: throw IllegalArgumentException("预计使用天数请输入整数")
            if (stopped) require(LocalDate.parse(stoppedDate) <= today) { "停用日期不能在未来" }
            val purchaseDate = LocalDate.parse(purchase).toString()
            val useDate = existing?.startDate?.takeIf { existing.purchaseDate == purchaseDate } ?: purchaseDate
            val item = Durable(id, name.trim(), category, cents, purchaseDate, useDate, expectedDays, if (stopped) stoppedDate else null, note.trim(), parseResidual(residual))
            error = null
            vm.perform(onSuccess = onDismiss) { vm.repository.saveDurable(item) }
        }.onFailure { error = it.message ?: "请检查输入" }
    }) {
        TextField(name, { name = it }, "物品名称，例如手机、电脑")
        Text("物品分类", style = MaterialTheme.typography.labelLarge)
        Choices((listOf("数码", "家电", "家具", "衣物", "运动", "其他") + category).distinct(), category) { category = it }
        TextField(amount, { amount = it }, "购买价格（元）", numeric = true)
        DateField("购入日期", purchase) { purchase = it }
        Text("购入日期同时作为开始使用日期，用来计算使用天数和日均成本。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (existing != null && existing.startDate != existing.purchaseDate) Text("此旧记录的历史使用日期会保留；修改购入日期时，同步更新使用日期。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextField(residual, { residual = it }, "折旧价（预计到期残值，元）", numeric = true)
        TextField(expected, { expected = it }, "预计使用总天数（可选）", numeric = true)
        val expectedDays = expected.toLongOrNull()
        if (expectedDays != null && expectedDays > 0) runCatching { val price = parseAmount(amount); val value = parseResidual(residual); require(value <= price) { "折旧价不能高于购买价" }; dailyMoney(price - value, expectedDays) }.getOrNull()?.let { Text("预计 $it / 天", color = accentColor(LedgerAccent.Durable)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("已停止使用"); Switch(stopped, { stopped = it }) }
        if (stopped) DateField("停用日期（含当天）", stoppedDate) { stoppedDate = it }
        TextField(note, { note = it }, "备注（可选）", multiline = true)
        Text("预计日均按购买价减折旧价计算；当前日均在预计到期后的次日起扣除折旧价。不产生正式账单，旧版历史保留。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
