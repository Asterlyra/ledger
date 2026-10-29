package com.atlas.ledger

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.atlas.ledger.data.*
import com.atlas.ledger.domain.decodeBackup
import com.atlas.ledger.domain.parseBillFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PendingImport(val text: String, val backup: LedgerBackup)
data class PendingBillImport(val preview: com.atlas.ledger.domain.BillPreview, val duplicateFingerprints: Set<String>, val suspectedFingerprints: Set<String> = emptySet(), val conflictingFingerprints: Set<String> = emptySet())

class LedgerViewModel(application: Application, val repository: LedgerRepository) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, LedgerRepository(LedgerDatabase.get(application)))
    val state = repository.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LedgerState())
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val messages = _messages.asSharedFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _pendingImport = MutableStateFlow<PendingImport?>(null)
    val pendingImport = _pendingImport.asStateFlow()
    private val _pendingBills = MutableStateFlow<PendingBillImport?>(null)
    val pendingBills = _pendingBills.asStateFlow()
    fun saveRecurringRule(rule: RecurringRule, message: String = "固定消费预期已保存", onSuccess: () -> Unit = {}) = perform(message, onSuccess) {
        repository.saveRecurringRule(rule)
    }
    fun skipRecurringDate(ruleId: String, date: String, onSuccess: () -> Unit = {}) = perform("该日期的预期已取消", onSuccess) {
        repository.skipRecurringDate(ruleId, date)
    }
    fun restoreSkippedDate(ruleId: String, date: String) = perform("该日期的预期已恢复") {
        repository.restoreSkippedDate(ruleId, date)
    }

    fun perform(successMessage: String = "已保存", onSuccess: () -> Unit = {}, action: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                onSuccess()
                _messages.emit(successMessage)
            } catch (e: Exception) {
                _messages.emit(e.message?.take(180) ?: "操作失败，请重试")
            } finally { _busy.value = false }
        }
    }
    fun exportBackup(uri: Uri) = perform("App 数据已导出") {
        val bytes = repository.exportJson().toByteArray(Charsets.UTF_8)
        getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: error("无法写入所选文件")
    }
    fun previewImport(uri: Uri) = perform("已读取备份，请确认导入") {
        val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                require(buffer.size() + count <= 10 * 1024 * 1024) { "备份文件不能超过 10 MB" }
                buffer.write(chunk, 0, count)
            }
            buffer.toByteArray()
        } ?: error("无法读取所选文件")
        val text = bytes.toString(Charsets.UTF_8)
        val backup = decodeBackup(text)
        _pendingImport.value = PendingImport(text, backup)
    }
    fun dismissImport() { _pendingImport.value = null }
    fun confirmImport() {
        val pending = _pendingImport.value ?: return
        perform("备份已合并，重复记录已跳过", onSuccess = ::dismissImport) { repository.importJson(pending.text) }
    }
    fun previewBills(uri: Uri) = perform("账单已解析，请检查后导入") {
        val app = getApplication<Application>()
        val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
            while (true) { val count = input.read(chunk); if (count < 0) break; require(output.size() + count <= 10 * 1024 * 1024) { "账单文件不能超过 10 MB" }; output.write(chunk, 0, count) }
            output.toByteArray()
        } ?: error("无法读取所选文件")
        val name = app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "账单.csv" } ?: "账单.csv"
        val preview = parseBillFile(name, bytes)
        val duplicates = repository.existingImportFingerprints(preview.source, preview.candidates.map { it.fingerprint }.distinct())
        _pendingBills.value = PendingBillImport(preview, duplicates, repository.possibleDuplicateBills(preview.candidates) - duplicates, repository.conflictingImportBills(preview.source, preview.candidates))
    }
    fun dismissBills() { _pendingBills.value = null }
    fun confirmBills(selectedFingerprints: Set<String>) {
        val pending = _pendingBills.value ?: return
        val selected = pending.preview.candidates.filter { it.fingerprint in selectedFingerprints && it.fingerprint !in pending.duplicateFingerprints }
        perform("账单导入完成", onSuccess = ::dismissBills) { repository.importBills(pending.preview.source, selected) }
    }
}
