package com.atlas.ledger.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.atlas.ledger.LedgerViewModel
import com.atlas.ledger.domain.isActualTransaction
import java.time.LocalDate
import kotlinx.coroutines.delay

private val tabs = listOf("首页", "账单", "订阅", "物品", "我的")
private val tabAccents = listOf(LedgerAccent.Primary, LedgerAccent.Expense, LedgerAccent.Subscription, LedgerAccent.Durable, LedgerAccent.Income)

@Composable fun LedgerApp(vm: LedgerViewModel = viewModel(), recurringRequest: Int = 0) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val pendingImport by vm.pendingImport.collectAsStateWithLifecycle()
    val pendingBills by vm.pendingBills.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var toolPage by rememberSaveable { mutableStateOf<String?>(null) }
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(recurringRequest) { if (recurringRequest > 0) { tab = 2; toolPage = "recurring" } }
    var deleteRequest by rememberSaveable { mutableStateOf<String?>(null) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    val snackbar = remember { SnackbarHostState() }
    BackHandler(toolPage != null && editor == null) { toolPage = null; vm.dismissBills() }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::exportBackup) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::previewImport) }
    val billLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::previewBills) }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { while (true) { today = LocalDate.now(); delay(30_000) } }
    val editTransaction: (com.atlas.ledger.data.LedgerTransaction) -> Unit = { tx ->
        editor = "transaction:${tx.id}"
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (toolPage == null || toolPage == "recurring") {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                val icons = listOf(Icons.Outlined.Home, Icons.AutoMirrored.Outlined.ReceiptLong, Icons.Outlined.EventRepeat, Icons.Outlined.Inventory2, Icons.Outlined.PersonOutline)
                tabs.forEachIndexed { index, label -> NavigationBarItem(
                    selected = tab == index, onClick = { tab = index; toolPage = null }, icon = { Icon(icons[index], label) }, label = { Text(label) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = accentColor(tabAccents[index]), selectedTextColor = accentColor(tabAccents[index]), indicatorColor = accentContainer(tabAccents[index])),
                ) }
            }
            }
        },
        floatingActionButton = {
            if (toolPage == null && tab != 4 && state.loaded) ExtendedFloatingActionButton(
                modifier = Modifier.height(48.dp).semantics { contentDescription = when (tab) { 2 -> "添加订阅"; 3 -> "添加物品"; else -> "记一笔" } },
                onClick = { editor = when (tab) { 2 -> "subscription:new"; 3 -> "durable:new"; else -> "transaction:new" } },
                icon = { Icon(Icons.Outlined.Add, null) }, text = { Text(when (tab) { 2 -> "添加订阅"; 3 -> "添加物品"; else -> "记一笔" }) },
                containerColor = tabAccents[tab].light, contentColor = androidx.compose.ui.graphics.Color.White,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (!state.loaded) CircularProgressIndicator(Modifier.padding(32.dp))
            else if (toolPage == "recurring") RecurringPage(
                state, today, { toolPage = null }, { editor = "recurring:new" }, { editor = "recurring:$it" },
                { rule, enabled -> vm.saveRecurringRule(rule.copy(enabled = enabled), if (enabled) "已启用固定消费预期" else "已暂停固定消费预期") },
                { deleteRequest = "rule:$it" }, { rule, date -> vm.skipRecurringDate(rule, date) }, vm::restoreSkippedDate,
            )
            else if (toolPage == "legacy-history") LegacyHistoryPage(state, { toolPage = null })
            else if (toolPage == "bill-import") BillImportPage(
                pendingBills, busy, { toolPage = null; vm.dismissBills() },
                { billLauncher.launch(arrayOf("text/*", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream")) },
                vm::confirmBills,
            )
            else when (tab) {
                0 -> HomePage(state, today, onAdd = { editor = "transaction:new" }, onTransaction = editTransaction, onSubscriptions = { tab = 2 })
                1 -> TransactionsPage(state, today, onEdit = editTransaction, onDelete = { deleteRequest = "transaction:$it" }, onImportBills = { toolPage = "bill-import" })
                2 -> SubscriptionsPage(state, today, onAdd = { editor = "subscription:new" }, onEditPeriod = { editor = "period:$it" }, onRenew = { editor = "renew:$it" }, onDelete = { deleteRequest = "subscription:$it" }, onFixed = { toolPage = "recurring" })
                3 -> DurablesPage(state, today, onAdd = { editor = "durable:new" }, onEdit = { editor = "durable:$it" }, onDelete = { deleteRequest = "durable:$it" })
                4 -> SettingsPage(state, busy, onExport = { exportLauncher.launch("日常账本备份-$today.json") }, onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, onHistory = { toolPage = "legacy-history" })
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    editor?.let { key ->
        key(key) { LedgerEditor(key, state, today, vm, onDismiss = { editor = null }, onDeletePeriod = { deleteRequest = "period:$it" }) }
    }
    deleteRequest?.let { key ->
        val type = key.substringBefore(':'); val id = key.substringAfter(':')
        val explanation = when (type) {
            "subscription" -> "这会删除该订阅与全部预期周期。旧版预期历史和正式账单保留。"
            "period" -> "这会删除本次预期周期。最后一个周期删除后也会删除空订阅；旧版历史与正式账单保留。"
            "durable" -> "这会删除物品预期档案。旧版预期历史与正式账单保留；停止使用可编辑停用日期。"
            "rule" -> "这会删除固定消费预期和跳过日期，旧版预期历史与正式账单保留。"
            else -> "这笔账单将从本机账本中删除。"
        }
        AlertDialog(onDismissRequest = { if (!busy) deleteRequest = null }, title = { Text("确认删除？") }, text = { Text(explanation) },
            dismissButton = { TextButton({ deleteRequest = null }, enabled = !busy) { Text("取消") } },
            confirmButton = { TextButton({ vm.perform("已删除", onSuccess = { deleteRequest = null; editor = null }) {
                when (type) { "subscription" -> vm.repository.deleteSubscription(id); "period" -> vm.repository.deletePeriod(id); "durable" -> vm.repository.deleteDurable(id); "rule" -> vm.repository.deleteRecurringRule(id); else -> vm.repository.deleteTransaction(id) }
            } }, enabled = !busy) { Text("删除", color = MaterialTheme.colorScheme.error) } })
    }
    pendingImport?.let { pending ->
        AlertDialog(onDismissRequest = { if (!busy) vm.dismissImport() }, title = { Text("导入备份") },
            text = { Text("备份包含 ${pending.backup.transactions.size} 笔记录（${pending.backup.transactions.count(::isActualTransaction)} 笔正式账单）、${pending.backup.subscriptions.size} 项订阅、${pending.backup.durables.size} 件物品。\n\n将与本机数据合并，相同记录跳过。如同一条记录的内容不同，会取消整个导入，保留本机数据。") },
            dismissButton = { TextButton(vm::dismissImport, enabled = !busy) { Text("取消") } },
            confirmButton = { Button(vm::confirmImport, enabled = !busy) { Text("合并导入") } })
    }
}
