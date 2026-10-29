package com.atlas.ledger.ui

import android.app.DatePickerDialog
import android.app.AlertDialog
import android.widget.NumberPicker
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import com.atlas.ledger.domain.RecordSort

@Composable fun rememberRecordSort(key: String, default: RecordSort): Pair<RecordSort, (RecordSort) -> Unit> {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("ledger-display", android.content.Context.MODE_PRIVATE) }
    var name by rememberSaveable(key) { mutableStateOf(preferences.getString(key, default.name) ?: default.name) }
    val selected = RecordSort.entries.find { it.name == name } ?: default
    return selected to { next -> name = next.name; preferences.edit().putString(key, next.name).apply() }
}

@Composable fun SortPicker(selected: RecordSort, onSelect: (RecordSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton({ expanded = true }, Modifier.semantics { contentDescription = "排序，${selected.label}" }) { Text("排序 ▾") }
        DropdownMenu(expanded, { expanded = false }) {
            RecordSort.entries.forEach { choice -> DropdownMenuItem(text = { Text("${if (choice == selected) "✓ " else ""}${choice.label}") }, onClick = { onSelect(choice); expanded = false }) }
        }
    }
}

@Composable fun PageTitle(title: String, subtitle: String, accent: LedgerAccent = LedgerAccent.Primary, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(4.dp).height(22.dp).background(accentColor(accent), RoundedCornerShape(4.dp)))
                Text(title, style = MaterialTheme.typography.headlineMedium, color = accentColor(accent))
            }
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.invoke()
    }
}

@Composable fun AccentIcon(icon: ImageVector, accent: LedgerAccent) {
    Surface(shape = RoundedCornerShape(10.dp), color = accentContainer(accent)) {
        Icon(icon, null, Modifier.padding(6.dp).size(20.dp), tint = accentColor(accent))
    }
}

@Composable fun SectionTitle(title: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        trailing?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable fun EmptyCard(title: String, description: String, button: String? = null, onClick: () -> Unit = {}) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            button?.let { OutlinedButton(onClick) { Text(it) } }
        }
    }
}

@Composable fun Choices(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option -> FilterChip(selected == option, { onSelect(option) }, label = { Text(option) }) }
    }
}

@Composable fun TextField(value: String, onChange: (String) -> Unit, label: String, numeric: Boolean = false, multiline: Boolean = false) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = !multiline,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text),
        shape = RoundedCornerShape(12.dp))
}

fun showDatePicker(context: android.content.Context, value: String, onChange: (String) -> Unit) {
    val date = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
    DatePickerDialog(context, { _, year, month, day -> onChange(LocalDate.of(year, month + 1, day).toString()) }, date.year, date.monthValue - 1, date.dayOfMonth).also { dialog ->
        dialog.datePicker.minDate = LocalDate.of(1900, 1, 1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        dialog.datePicker.maxDate = LocalDate.of(2200, 12, 31).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "选择年份") { _, _ -> }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val picker = NumberPicker(context).apply { minValue = 1900; maxValue = 2200; this.value = dialog.datePicker.year; wrapSelectorWheel = false; contentDescription = "年份" }
                AlertDialog.Builder(context).setTitle("选择年份（可直接输入）").setView(picker)
                    .setNegativeButton("取消", null).setPositiveButton("确定") { _, _ ->
                        picker.clearFocus()
                        val month = dialog.datePicker.month + 1
                        val first = LocalDate.of(picker.value, month, 1)
                        dialog.datePicker.updateDate(picker.value, month - 1, dialog.datePicker.dayOfMonth.coerceAtMost(first.lengthOfMonth()))
                    }.show()
            }
        }
    }.show()
}

@Composable fun DateField(label: String, value: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val openPicker = { showDatePicker(context, value, onChange) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(value, {}, Modifier.fillMaxWidth(), readOnly = true, label = { Text(label) },
            trailingIcon = { IconButton(openPicker) { Icon(Icons.Outlined.DateRange, "选择$label") } }, shape = RoundedCornerShape(12.dp))
        Box(Modifier.matchParentSize().semantics { contentDescription = "选择$label，$value" }.clickable(onClick = openPicker))
    }
}

@Composable fun FormDialog(title: String, busy: Boolean, error: String?, onDismiss: () -> Unit, onSave: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(max = 760.dp).imePadding(), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss, enabled = !busy) { Text("取消") }
                    Button(onSave, enabled = !busy) { Text(if (busy) "保存中…" else "保存") }
                }
            }
        }
    }
}
