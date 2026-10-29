package com.atlas.ledger.domain

import com.atlas.ledger.data.LedgerBackup
import kotlinx.serialization.json.*

private val backupJson = Json { prettyPrint = true; encodeDefaults = true }
fun encodeBackup(backup: LedgerBackup): String = backupJson.encodeToString(LedgerBackup.serializer(), backup)
fun decodeBackup(text: String): LedgerBackup {
    val root = runCatching { backupJson.parseToJsonElement(text).jsonObject }.getOrElse { throw IllegalArgumentException("不是有效的账本备份文件") }
    require(root.keys.containsAll(listOf("format", "version", "transactions", "subscriptions", "periods", "durables"))) { "备份缺少格式标记或完整数据" }
    val version = root["version"]?.jsonPrimitive?.intOrNull ?: throw IllegalArgumentException("备份缺少有效版本号")
    if (version >= 2) require(root.keys.containsAll(listOf("recurringRules", "recurringOccurrences", "importReceipts"))) { "新版备份缺少固定记账或导入凭据数据" }
    val backup = runCatching { backupJson.decodeFromString(LedgerBackup.serializer(), text) }.getOrElse { throw IllegalArgumentException("备份数据格式不正确或版本不受支持") }
    if (version >= 3) require(root["durables"]!!.jsonArray.all { "residualCents" in it.jsonObject }) { "新版备份缺少物品折旧价" }
    validateBackup(backup)
    return backup
}
