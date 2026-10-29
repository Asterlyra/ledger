package com.atlas.ledger.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.atlas.ledger.data.*
import com.atlas.ledger.domain.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

private val pagePadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 80.dp)

@Composable fun HomePage(state: LedgerState, today: LocalDate, onAdd: () -> Unit, onTransaction: (LedgerTransaction) -> Unit, onSubscriptions: () -> Unit) {
    val (recentSort, setRecentSort) = rememberRecordSort("home-recent", RecordSort.DATE_DESC)
    val (income, expense) = monthTotals(state.transactions, today)
    val daily = dailyConsumption(state.periods, state.durables, today, state.recurringRules, state.recurringOccurrences)
    val expiring = state.subscriptions.mapNotNull { sub ->
        state.periods.filter { it.subscriptionId == sub.id && activeOn(it, today) }.firstOrNull()?.let { period ->
            val alreadyRenewed = state.periods.any { it.subscriptionId == sub.id && it.startDate == period.endExclusive }
            if (remainingDays(period, today) <= 7 && !alreadyRenewed) sub to period else null
        }
    }
    LazyColumn(contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { PageTitle("日常账本", "${today.monthValue}月${today.dayOfMonth}日 · 把每一笔生活记清楚") }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.Transparent, contentColor = Color.White)) {
                Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(HeroBlue, HeroViolet))).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本月支出", color = Color.White.copy(alpha = .92f))
                    Text(money(expense), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold, color = Color.White)
                    HorizontalDivider(color = Color.White.copy(alpha = .28f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text("本月收入", style = MaterialTheme.typography.bodySmall); Text(money(income), style = MaterialTheme.typography.titleMedium, color = Color(0xFFC1FFE1)) }
                        Column { Text("本月结余", style = MaterialTheme.typography.bodySmall); Text(money(income - expense), style = MaterialTheme.typography.titleMedium, color = Color(0xFFFFE2A0)) }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DailyCard("日均消费", daily.current.toPlainString(), Modifier.weight(1f), SubscriptionFill, Color.White)
                DailyCard("预计日均消费", daily.expected.toPlainString(), Modifier.weight(1f), DurableFill, DurableInk)
            }
            Text("${daily.subscriptions} 项生效订阅 + ${daily.durables} 件使用中物品 + ${daily.fixed} 项固定消费${if (daily.fallbackDurables > 0) "；${daily.fallbackDurables} 件未设预计天数，预计值暂用当前日均" else ""}。仅作成本分析。", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expiring.isNotEmpty()) item {
            Card(Modifier.fillMaxWidth().clickable(onClick = onSubscriptions), colors = CardDefaults.cardColors(containerColor = accentContainer(LedgerAccent.Durable))) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("订阅即将到期", style = MaterialTheme.typography.titleSmall)
                    expiring.sortedBy { it.second.endExclusive }.take(2).forEach { (sub, period) -> Text("${sub.name} · 剩余 ${remainingDays(period, today)} 天", style = MaterialTheme.typography.bodyMedium) }
                    if (expiring.size > 2) Text("还有 ${expiring.size - 2} 项即将到期，点击查看全部", style = MaterialTheme.typography.bodySmall, color = accentColor(LedgerAccent.Durable))
                }
            }
        }
        val categories = state.transactions.filter { isActualTransaction(it) && it.kind == "EXPENSE" && LocalDate.parse(it.date) in today.withDayOfMonth(1)..today }.groupBy { it.category }.mapValues { (_, tx) -> tx.sumOf { it.amountCents } }.toList().sortedByDescending { it.second }
        if (categories.isNotEmpty()) {
            item { SectionTitle("本月支出分布") }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        categories.forEach { (category, amount) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(category); Text(money(amount)) }
                            LinearProgressIndicator(progress = { (amount.toDouble() / expense).toFloat() }, modifier = Modifier.fillMaxWidth(), color = accentColor(categoryAccent(category)), trackColor = accentContainer(categoryAccent(category)))
                        }
                    }
                }
            }
        }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("最近账单", style = MaterialTheme.typography.titleMedium); SortPicker(recentSort, setRecentSort) } }
        if (state.transactions.none(::isActualTransaction)) item { EmptyCard("从第一笔开始", "记录今天的支出或收入，订阅和物品为预期档案，实际消费请手动录入或导入。", "记一笔", onAdd) }
        items(sortedTransactions(state.transactions.filter(::isActualTransaction), recentSort).take(5), key = { it.id }) { TransactionRow(it, onClick = { onTransaction(it) }) }
    }
}

@Composable private fun DailyCard(title: String, amount: String, modifier: Modifier, fill: Color, ink: Color) {
    Card(modifier, shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = fill, contentColor = ink)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text("¥$amount / 天", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable fun TransactionRow(tx: LedgerTransaction, onClick: () -> Unit, onDelete: (() -> Unit)? = null) {
    val accent = when {
        tx.kind == "INCOME" -> LedgerAccent.Income
        tx.kind == "TRANSFER" -> LedgerAccent.Transfer
        tx.origin == "SUBSCRIPTION" -> LedgerAccent.Subscription
        tx.origin == "DURABLE" -> LedgerAccent.Durable
        tx.origin == "RECURRING" -> LedgerAccent.Expense
        else -> LedgerAccent.Expense
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentIcon(when (tx.kind) { "INCOME" -> Icons.AutoMirrored.Outlined.TrendingUp; "TRANSFER" -> Icons.Outlined.SwapHoriz; else -> Icons.AutoMirrored.Outlined.ReceiptLong }, accent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tx.note.ifBlank { tx.category }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${tx.date} · ${tx.category} · ${tx.account}${if (tx.kind == "TRANSFER") " → ${tx.targetAccount}" else ""}${if (tx.origin != null) " · 预期历史" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text((when (tx.kind) { "INCOME" -> "+"; "EXPENSE" -> "−"; else -> "" }) + money(tx.amountCents), style = MaterialTheme.typography.titleSmall, color = accentColor(when (tx.kind) { "INCOME" -> LedgerAccent.Income; "TRANSFER" -> LedgerAccent.Transfer; else -> LedgerAccent.Expense }))
            if (onDelete != null) IconButton(onDelete, Modifier.size(28.dp)) { Icon(Icons.Outlined.DeleteOutline, "删除账单", Modifier.size(20.dp)) }
        }
    }
}

@Composable fun TransactionsPage(state: LedgerState, today: LocalDate, onEdit: (LedgerTransaction) -> Unit, onDelete: (String) -> Unit, onImportBills: () -> Unit = {}) {
    var monthString by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var filter by rememberSaveable { mutableStateOf("全部") }
    var search by rememberSaveable { mutableStateOf("") }
    var groupName by rememberSaveable { mutableStateOf<String?>(null) }
    var allHistory by rememberSaveable { mutableStateOf(false) }
    val (sort, setSort) = rememberRecordSort("bills", RecordSort.DATE_DESC)
    val (detailSort, setDetailSort) = rememberRecordSort("bill-details", RecordSort.DATE_DESC)
    val month = YearMonth.parse(monthString)
    val actual = state.transactions.filter(::isActualTransaction)
    val txs = actual.filter { YearMonth.from(LocalDate.parse(it.date)) == month }
    val filtered = txs.filter { (filter == "全部" || it.kind == when (filter) { "收入" -> "INCOME"; "转账" -> "TRANSFER"; else -> "EXPENSE" }) && "${it.note} ${it.category} ${it.account} ${it.targetAccount}".contains(search.trim(), ignoreCase = true) }
    BackHandler(groupName != null) { groupName = null }
    if (groupName != null) {
        val details = (if (allHistory) actual else txs).filter { it.kind == "EXPENSE" && transactionName(it) == groupName }
        LazyColumn(contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { PageTitle(groupName!!, "${details.size} 笔消费 · 合计 ${money(details.sumOf { it.amountCents })}", LedgerAccent.Expense, action = { TextButton({ groupName = null }) { Text("返回") } }) }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Choices(listOf("本月明细", "全部历史"), if (allHistory) "全部历史" else "本月明细") { allHistory = it == "全部历史" }; SortPicker(detailSort, setDetailSort) } }
            if (details.isEmpty()) item { EmptyCard("当前没有明细", "可以切换全部历史，或返回账单列表。") }
            items(sortedTransactions(details, detailSort), key = { it.id }) { tx -> TransactionRow(tx, { onEdit(tx) }, { onDelete(tx.id) }) }
        }
        return
    }
    LazyColumn(contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { PageTitle("账单", "正式收支 · 仅来自手动记账和文件导入", LedgerAccent.Expense, action = { SortPicker(sort, setSort) }) }
        item { OutlinedButton(onImportBills, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.FileDownload, null); Spacer(Modifier.width(6.dp)); Text("导入支付账单") } }
        item { MonthSelector(month, { monthString = it.toString() }, "收入 ${money(txs.filter { it.kind == "INCOME" }.sumOf { it.amountCents })} · 支出 ${money(txs.filter { it.kind == "EXPENSE" }.sumOf { it.amountCents })}") }
        item { Choices(listOf("全部", "支出", "收入", "转账"), filter) { filter = it } }
        item { TextField(search, { search = it }, "搜索名称、分类或账户") }
        if (filtered.isEmpty()) item { EmptyCard("没有找到记录", "可以切换月份、调整筛选，或点击下方的“记一笔”。") }
        items(groupedTransactions(filtered, sort), key = { it.key }) { group ->
            if (!group.isExpense) TransactionRow(group.transactions.single(), { onEdit(group.transactions.single()) }, { onDelete(group.transactions.single().id) })
            else Card(Modifier.fillMaxWidth().clickable { groupName = group.name; allHistory = false }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccentIcon(Icons.AutoMirrored.Outlined.ReceiptLong, LedgerAccent.Expense)
                    Column(Modifier.weight(1f)) {
                        Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Text("${group.transactions.size} 笔 · 最近 ${group.date} · 点击看明细", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("−${money(group.amountCents)}", style = MaterialTheme.typography.titleSmall, color = accentColor(LedgerAccent.Expense))
                }
            }
        }
    }
}

@Composable private fun MonthSelector(month: YearMonth, onSelect: (YearMonth) -> Unit, summary: String) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton({ onSelect(month.minusMonths(1)) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "上个月") }
        Column(Modifier.weight(1f).semantics { contentDescription = "选择月份，$month" }.clickable { showDatePicker(context, month.atDay(1).toString()) { onSelect(YearMonth.from(LocalDate.parse(it))) } }, horizontalAlignment = Alignment.CenterHorizontally) { Text("${month.year} 年 ${month.monthValue} 月", style = MaterialTheme.typography.titleMedium); Text(summary, style = MaterialTheme.typography.bodySmall) }
        IconButton({ onSelect(month.plusMonths(1)) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "下个月") }
    }
}

@Composable fun RecurringPage(state: LedgerState, today: LocalDate, onBack: () -> Unit, onAdd: () -> Unit, onEdit: (String) -> Unit, onToggle: (RecurringRule, Boolean) -> Unit, onDelete: (String) -> Unit, onSkip: (String, String) -> Unit, onRestore: (String, String) -> Unit) {
    val (sort, setSort) = rememberRecordSort("fixed-plans", RecordSort.PRICE_DESC)
    val (forecastSort, setForecastSort) = rememberRecordSort("fixed-forecast", RecordSort.DATE_DESC)
    var monthString by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    val month = YearMonth.parse(monthString)
    val forecasts = forecastMonth(state.recurringRules, state.recurringOccurrences, month)
    LazyColumn(contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { PageTitle("固定消费", "订阅中的预期安排，不自动生成正式账单", LedgerAccent.Subscription, action = { TextButton(onBack) { Text("返回") } }) }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Button(onAdd) { Text("添加固定消费") }; SortPicker(sort, setSort) } }
        if (state.recurringRules.isEmpty()) item { EmptyCard("安排重复消费", "选择每天或具体星期几，预估公交、地铁等固定费用。实际发生后在账单页手动录入或导入。") }
        items(sortedRules(state.recurringRules, sort), key = { "rule:${it.id}" }) { rule ->
            val weekdays = rule.weekdays.split(',').map { listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[it.toInt() - 1] }.joinToString("、")
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().clickable { onEdit(rule.id) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(rule.name, style = MaterialTheme.typography.titleMedium); Text("${money(rule.amountCents)} / 次 · $weekdays", style = MaterialTheme.typography.bodySmall); Text("${rule.startDate} 起 · ${if (rule.enabled) "启用预期" else "已暂停"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Switch(rule.enabled, { onToggle(rule, it) })
                    IconButton({ onDelete(rule.id) }) { Icon(Icons.Outlined.DeleteOutline, "删除固定消费") }
                }
            }
        }
        item { SectionTitle("预期明细", "可取消未发生的日期") }
        item { MonthSelector(month, { monthString = it.toString() }, "预计 ${forecasts.count { !it.skipped }} 次 · ${money(forecasts.filterNot { it.skipped }.sumOf { it.amountCents })}") }
        item { SortPicker(forecastSort, setForecastSort) }
        if (forecasts.isEmpty()) item { EmptyCard("本月没有预期安排", "可以切换月份，或添加/启用固定消费。") }
        items(sortedForecasts(forecasts, forecastSort), key = { "forecast:${it.key}" }) { forecast ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(forecast.name, style = MaterialTheme.typography.titleSmall); Text("${forecast.date} · ${if (forecast.skipped) "预期已取消" else "预计发生"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(money(forecast.amountCents), style = MaterialTheme.typography.titleSmall)
                    TextButton({ if (forecast.skipped) onRestore(forecast.ruleId, forecast.date) else onSkip(forecast.ruleId, forecast.date) }, Modifier.semantics { contentDescription = "${if (forecast.skipped) "恢复" else "取消"}${forecast.name}在${forecast.date}的预期" }) { Text(if (forecast.skipped) "恢复" else "未发生") }
                }
            }
        }
    }
}

@Composable fun LegacyHistoryPage(state: LedgerState, onBack: () -> Unit) {
    val (sort, setSort) = rememberRecordSort("legacy-history", RecordSort.DATE_DESC)
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val history = state.transactions.filterNot(::isActualTransaction)
    LazyColumn(contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { PageTitle("预期历史", "旧版生成的 ${history.size} 笔记录，保留原值，不计正式收支", LedgerAccent.Subscription, action = { TextButton(onBack) { Text("返回") } }) }
        item { SortPicker(sort, setSort) }
        if (history.isEmpty()) item { EmptyCard("没有旧版预期历史", "新版订阅、物品与固定消费只计算预期，不会创建账单。") }
        items(sortedTransactions(history, sort), key = { it.id }) { tx -> TransactionRow(tx, { selectedId = tx.id }) }
    }
    history.find { it.id == selectedId }?.let { tx ->
        AlertDialog(onDismissRequest = { selectedId = null }, title = { Text(transactionName(tx)) }, text = { Text("日期：${tx.date}\n金额：${money(tx.amountCents)}\n分类：${tx.category}\n原账户：${tx.account}\n来源：${when (tx.origin) { "SUBSCRIPTION" -> "旧版订阅"; "DURABLE" -> "旧版物品"; else -> "旧版固定消费" }}\n\n此记录保留在完整数据导出中，不计入正式收支。") }, confirmButton = { TextButton({ selectedId = null }) { Text("关闭") } })
    }
}

@Composable fun BillImportPage(pending: com.atlas.ledger.PendingBillImport?, busy: Boolean, onBack: () -> Unit, onChoose: () -> Unit, onConfirm: (Set<String>) -> Unit) {
    val (sort, setSort) = rememberRecordSort("import-preview", RecordSort.DATE_DESC)
    var selected by remember(pending) { mutableStateOf(pending?.preview?.candidates?.map { it.fingerprint }?.filterNot { it in pending.duplicateFingerprints || it in pending.suspectedFingerprints }?.toSet().orEmpty()) }
    LazyColumn(contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { PageTitle("导入支付账单", "选择支付宝或微信导出的 CSV / XLSX", LedgerAccent.Income, action = { Row { TextButton(onBack) { Text("返回") }; SortPicker(sort, setSort) } }) }
        if (pending == null) item {
            EmptyCard("先选择一份账单文件", "文件会先在本机解析并预览。你可以取消不需要的记录；相同来源中已导入过的交易会自动跳过。", "选择账单文件", onChoose)
        } else {
            val preview = pending.preview
            item {
                Card(colors = CardDefaults.cardColors(containerColor = accentContainer(LedgerAccent.Income))) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(if (preview.source == "WECHAT") "微信账单" else "支付宝账单", style = MaterialTheme.typography.titleMedium)
                        Text("识别 ${preview.candidates.size} 笔 · 跳过 ${preview.skippedRows} 行 · 已导入过 ${pending.duplicateFingerprints.size} 笔", style = MaterialTheme.typography.bodySmall)
                        if (pending.suspectedFingerprints.isNotEmpty()) Text("另有 ${pending.suspectedFingerprints.size} 笔与本机同日、同金额、同类型记录可能重复，默认不勾选，请核对后决定。", style = MaterialTheme.typography.bodySmall)
                        if (pending.conflictingFingerprints.isNotEmpty()) Text("${pending.conflictingFingerprints.size} 笔已有交易的原始内容发生变化，已跳过。请核对后在账单中手动修改。", style = MaterialTheme.typography.bodySmall)
                        preview.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ selected = preview.candidates.map { it.fingerprint }.filterNot { it in pending.duplicateFingerprints }.toSet() }) { Text("全选未导入") }
                    OutlinedButton({ selected = emptySet() }) { Text("全不选") }
                    Button({ onConfirm(selected) }, enabled = !busy && selected.isNotEmpty()) { Text(if (busy) "导入中…" else "导入 ${selected.size} 笔") }
                }
            }
            items(sortedBillCandidates(preview.candidates, sort), key = { it.fingerprint }) { candidate ->
                val duplicate = candidate.fingerprint in pending.duplicateFingerprints
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.fillMaxWidth().clickable(enabled = !duplicate) { selected = if (candidate.fingerprint in selected) selected - candidate.fingerprint else selected + candidate.fingerprint }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(candidate.fingerprint in selected, onCheckedChange = { checked -> selected = if (checked) selected + candidate.fingerprint else selected - candidate.fingerprint }, enabled = !duplicate)
                        Column(Modifier.weight(1f)) {
                            Text(candidate.transaction.note.ifBlank { candidate.transaction.category }, maxLines = 1, style = MaterialTheme.typography.titleSmall)
                            Text("${candidate.transaction.date} · ${candidate.transaction.category} · ${candidate.transaction.account}${if (candidate.fingerprint in pending.conflictingFingerprints) " · 原内容已变化" else if (duplicate) " · 已导入" else if (candidate.fingerprint in pending.suspectedFingerprints) " · 可能重复" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text((if (candidate.transaction.kind == "INCOME") "+" else "−") + money(candidate.transaction.amountCents), color = accentColor(if (candidate.transaction.kind == "INCOME") LedgerAccent.Income else LedgerAccent.Expense), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
    }
}

@Composable fun SubscriptionsPage(state: LedgerState, today: LocalDate, onAdd: () -> Unit, onEditPeriod: (String) -> Unit, onRenew: (String) -> Unit, onDelete: (String) -> Unit, onFixed: () -> Unit) {
    val (sort, setSort) = rememberRecordSort("subscriptions", RecordSort.PRICE_DESC)
    LazyColumn(contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { PageTitle("订阅", "预期费用，实际消费请在账单页记录", LedgerAccent.Subscription, action = { Row { TextButton(onFixed) { Text("固定消费") }; SortPicker(sort, setSort) } }) }
        if (state.subscriptions.isEmpty()) item { EmptyCard("管理你的第一项订阅", "记录预期价格和有效期，查看每天成本与到期情况。正式消费由手动记账或导入产生。", "添加订阅", onAdd) }
        items(sortedSubscriptions(state.subscriptions, state.periods, today, sort), key = { it.id }) { sub ->
            var expanded by rememberSaveable(sub.id) { mutableStateOf(false) }
            val periods = state.periods.filter { it.subscriptionId == sub.id }.sortedByDescending { it.startDate }
            val active = periods.find { activeOn(it, today) }
            val next = periods.filter { LocalDate.parse(it.startDate) > today }.minByOrNull { it.startDate }
            val shown = active ?: next ?: periods.firstOrNull()
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Box(Modifier.fillMaxWidth().height(3.dp).background(SubscriptionFill))
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AccentIcon(Icons.Outlined.EventRepeat, LedgerAccent.Subscription)
                        Column(Modifier.weight(1f)) { Text(sub.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${sub.category} · ${sub.billingCycle}${if (sub.autoRenew) " · 自动续费" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        shown?.let { period ->
                            val statusAccent = when { active != null -> LedgerAccent.Income; next != null -> LedgerAccent.Primary; else -> LedgerAccent.Expense }
                            Surface(shape = RoundedCornerShape(50.dp), color = accentContainer(statusAccent)) {
                                Text(when { active != null -> "剩余 ${remainingDays(period, today)} 天"; next != null -> "尚未生效"; else -> "已到期" }, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = accentColor(statusAccent))
                            }
                        }
                        IconButton({ onDelete(sub.id) }) { Icon(Icons.Outlined.DeleteOutline, "删除订阅") }
                    }
                    shown?.let { period ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${dailyMoney(period.amountCents, periodDays(period))} / 天", style = MaterialTheme.typography.titleMedium, color = accentColor(LedgerAccent.Subscription))
                            Text("${period.startDate} 至 ${LocalDate.parse(period.endExclusive).minusDays(1)} · ${money(period.amountCents)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (sub.note.isNotBlank()) Text(sub.note, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ onRenew(sub.id) }, colors = ButtonDefaults.buttonColors(containerColor = SubscriptionFill, contentColor = Color.White)) { Text("添加周期") }
                        TextButton({ expanded = !expanded }, colors = ButtonDefaults.textButtonColors(contentColor = accentColor(LedgerAccent.Subscription))) { Text(if (expanded) "收起历史" else "周期记录 (${periods.size})") }
                    }
                    if (expanded) periods.forEach { period ->
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth().clickable { onEditPeriod(period.id) }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column { Text("${period.startDate} — ${LocalDate.parse(period.endExclusive).minusDays(1)}", style = MaterialTheme.typography.bodySmall); Text("${periodDays(period)} 天 · 点击编辑", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Text(money(period.amountCents), style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable fun DurablesPage(state: LedgerState, today: LocalDate, onAdd: () -> Unit, onEdit: (String) -> Unit, onDelete: (String) -> Unit) {
    val (sort, setSort) = rememberRecordSort("durables", RecordSort.PRICE_DESC)
    LazyColumn(contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { PageTitle("耐用品", "价格、折旧与日均成本预期", LedgerAccent.Durable, action = { SortPicker(sort, setSort) }) }
        if (state.durables.isEmpty()) item { EmptyCard("一件物品，一段使用记录", "添加手机、电脑、家电或家具，查看当前与预计日均成本。不会生成正式消费账单。", "添加物品", onAdd) }
        items(sortedDurables(state.durables, sort), key = { it.id }) { item ->
            val days = usedDays(item, today)
            Card(Modifier.fillMaxWidth().clickable { onEdit(item.id) }, shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Box(Modifier.fillMaxWidth().height(3.dp).background(DurableFill))
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AccentIcon(Icons.Outlined.Inventory2, LedgerAccent.Durable)
                        Column(Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${item.category} · ${when { item.stoppedDate != null && LocalDate.parse(item.stoppedDate) <= today -> "已停用"; days == 0L -> "尚未使用"; else -> "已使用 $days 天" }}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton({ onDelete(item.id) }) { Icon(Icons.Outlined.DeleteOutline, "删除物品") }
                    }
                    Surface(shape = RoundedCornerShape(14.dp), color = accentContainer(LedgerAccent.Durable)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column { Text("当前日均", style = MaterialTheme.typography.labelMedium); Text(if (days == 0L) "—" else "¥${durableCurrentCost(item, today).toPlainString()} / 天", style = MaterialTheme.typography.titleMedium, color = accentColor(LedgerAccent.Durable)) }
                            Column { Text("预计日均", style = MaterialTheme.typography.labelMedium); Text(item.expectedDays?.let { "¥${durableExpectedCost(item, today).toPlainString()} / 天" } ?: "未设置", style = MaterialTheme.typography.titleMedium, color = accentColor(LedgerAccent.Primary)) }
                        }
                    }
                    Text("购买 ${money(item.amountCents)} · 折旧 ${money(item.residualCents)} · ${item.purchaseDate}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    durableExpectedEnd(item)?.let { Text("预计 $it 到期", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item.stoppedDate?.let { Text("${it} 停用 · 使用天数 ${days} 天", style = MaterialTheme.typography.bodySmall) }
                    if (item.note.isNotBlank()) Text(item.note, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable fun SettingsPage(state: LedgerState, busy: Boolean, onExport: () -> Unit, onImport: () -> Unit, onHistory: () -> Unit) {
    LazyColumn(contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { PageTitle("我的账本", "本机保存 · 无需账号 · 离线可用", LedgerAccent.Income) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = accentContainer(LedgerAccent.Income))) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${state.transactions.count(::isActualTransaction)} 笔正式账单", style = MaterialTheme.typography.headlineSmall, color = accentColor(LedgerAccent.Income))
                    Text("${state.subscriptions.size} 项订阅 · ${state.periods.size} 个预期周期 · ${state.durables.size} 件物品", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${state.recurringRules.size} 条固定消费预期 · ${state.transactions.count { !isActualTransaction(it) }} 笔旧版预期历史", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { OutlinedButton(onHistory, Modifier.fillMaxWidth()) { Text("查看旧版预期历史") } }
        item { SectionTitle("数据导出与恢复") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("导出全部 App 数据：正式账单、订阅周期、物品折旧、固定消费预期、取消记录、旧版预期历史及导入凭据。保存为 JSON 文件，可用于备份和换机恢复。", style = MaterialTheme.typography.bodyMedium)
                    Button(onExport, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.FileUpload, null); Spacer(Modifier.width(8.dp)); Text("导出 App 数据") }
                    OutlinedButton(onImport, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("导入备份") }
                    Text("备份为可读的 JSON 文件，包含你的收支信息。导入采用合并方式，不会清空本机账本。卸载前请先导出备份。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { SectionTitle("计算与记录规则") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("• 金额以人民币计，精确到分。\n• 转账不计入收入或支出。\n• 订阅有效期包含生效日和到期日。\n• 耐用品开始使用当天算第 1 天，停用后冻结天数。\n• 订阅、物品和固定消费只计算预期。\n• 正式消费仅通过手动录入或导入产生。\n• 旧版关联账单保留为预期历史。\n• 本月结余是本月收入减支出，不是账户余额。", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item { Text("日常账本 0.3.0\n固定消费已移入订阅，只用于预期安排；不自动记账或扣款。支付宝和微信账单可从本机文件导入。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
