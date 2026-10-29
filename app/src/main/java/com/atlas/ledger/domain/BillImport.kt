package com.atlas.ledger.domain

import com.atlas.ledger.data.LedgerTransaction
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader

/** A row ready for review. Receipts are written only after the user confirms the preview. */
data class BillCandidate(val fingerprint: String, val transaction: LedgerTransaction, val contentHash: String = sha256(listOf(transaction.kind, transaction.date, transaction.amountCents.toString(), transaction.note).joinToString("|")))
data class BillPreview(val source: String, val candidates: List<BillCandidate>, val skippedRows: Int, val warnings: List<String>)

fun parseBillFile(fileName: String, bytes: ByteArray): BillPreview {
    require(bytes.size <= 10 * 1024 * 1024) { "账单文件不能超过 10 MB" }
    require(!fileName.lowercase(Locale.ROOT).endsWith(".zip")) { "请先解压账单压缩包，再选择 CSV 或 XLSX 文件" }
    require(!fileName.lowercase(Locale.ROOT).endsWith(".xls")) { "旧版 XLS 请先另存为 XLSX 或 CSV 文件" }
    val rows = if (fileName.lowercase(Locale.ROOT).endsWith(".xlsx")) readXlsx(bytes) else readCsv(decodeText(bytes))
    return interpretRows(rows, fileName)
}

private fun decodeText(bytes: ByteArray): String {
    val content = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) bytes.copyOfRange(3, bytes.size) else bytes
    for (charset in listOf(Charsets.UTF_8, Charset.forName("GB18030"))) {
        val decoded = runCatching { charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString() }.getOrNull()
        if (decoded != null) return decoded
    }
    throw IllegalArgumentException("文件编码无法识别，请另存为 UTF-8 CSV")
}

/** CSV with quoted commas, escaped quotes, CRLF and multiline cells. */
private fun readCsv(text: String): List<List<String>> {
    val delimiter = text.lineSequence().take(30).maxByOrNull { it.count { c -> c == '\t' || c == ',' } }?.let { if (it.count { c -> c == '\t' } > it.count { c -> c == ',' }) '\t' else ',' } ?: ','
    val rows = mutableListOf<List<String>>()
    val row = mutableListOf<String>(); val cell = StringBuilder()
    var quoted = false; var i = 0
    while (i < text.length) {
        val ch = text[i]
        when {
            quoted && ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
            ch == '"' -> quoted = !quoted
            !quoted && ch == delimiter -> { row += cell.toString().trim(); cell.setLength(0) }
            !quoted && (ch == '\n' || ch == '\r') -> {
                row += cell.toString().trim(); cell.setLength(0)
                if (row.any(String::isNotBlank)) rows += row.toList()
                row.clear(); if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
            }
            else -> cell.append(ch)
        }
        i++
    }
    require(!quoted) { "CSV 文件引号不完整" }
    row += cell.toString().trim(); if (row.any(String::isNotBlank)) rows += row.toList()
    require(rows.size <= 20_001) { "账单行数超过 20,000 行" }
    return rows
}

private fun readXlsx(bytes: ByteArray): List<List<String>> {
    val entries = mutableMapOf<String, ByteArray>()
    var total = 0L; var count = 0
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            count++; require(count <= 100) { "Excel 文件包含过多内容" }
            val needed = entry.name in setOf("xl/sharedStrings.xml", "xl/styles.xml", "xl/workbook.xml", "xl/_rels/workbook.xml.rels") || entry.name.matches(Regex("xl/worksheets/sheet[0-9]+\\.xml"))
            val output = if (needed) java.io.ByteArrayOutputStream() else null
            val buffer = ByteArray(8192)
            while (true) {
                val n = zip.read(buffer); if (n < 0) break
                total += n; require(total <= 30L * 1024 * 1024) { "Excel 解压后超过 30 MB" }
                output?.write(buffer, 0, n)
            }
            if (output != null) entries[entry.name] = output.toByteArray()
        }
    }
    fun document(data: ByteArray): org.w3c.dom.Document {
        // Read as UTF-8 text so a UTF-16 declaration cannot evade the DTD check.
        val raw = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString().removePrefix("\uFEFF")
        require(!raw.contains("<!DOCTYPE", ignoreCase = true) && !raw.contains("<!ENTITY", ignoreCase = true)) { "Excel XML 不允许包含 DTD 或实体声明" }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isValidating = false }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw org.xml.sax.SAXException("不允许读取外部 XML 内容") }
        return builder.parse(InputSource(StringReader(raw)))
    }
    val workbook = entries["xl/workbook.xml"]?.let(::document)
    val firstSheet = workbook?.getElementsByTagNameNS("*", "sheet")?.item(0) as? Element
    val relationId = firstSheet?.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id").orEmpty()
    var sheetPath = "xl/worksheets/sheet1.xml"
    entries["xl/_rels/workbook.xml.rels"]?.let { data ->
        val nodes = document(data).getElementsByTagNameNS("*", "Relationship")
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as Element
            if (node.getAttribute("Id") == relationId && relationId.isNotEmpty()) {
                require(node.getAttribute("TargetMode") != "External") { "Excel 工作表不能来自外部链接" }
                val target = node.getAttribute("Target")
                sheetPath = if (target.startsWith('/')) target.removePrefix("/") else java.net.URI("xl/").resolve(target).normalize().toString()
            }
        }
    }
    val sheet = entries[sheetPath] ?: throw IllegalArgumentException("Excel 中没有可读取的第一个工作表")
    val date1904 = (workbook?.getElementsByTagNameNS("*", "workbookPr")?.item(0) as? Element)?.getAttribute("date1904") in setOf("1", "true")
    val dateStyles = mutableSetOf<Int>()
    entries["xl/styles.xml"]?.let { data ->
        val styles = document(data)
        val formats = styles.getElementsByTagNameNS("*", "numFmt")
        val dateFormats = (14..22).toMutableSet().apply { addAll(45..47) }
        for (i in 0 until formats.length) {
            val f = formats.item(i) as Element
            val code = f.getAttribute("formatCode").lowercase(Locale.ROOT).replace(Regex("\"[^\"]*\""), "").replace(Regex("\\[(?!h+\\]|m+\\]|s+\\])[^]]*]"), "")
            if (code.any { it in "ydhs" }) f.getAttribute("numFmtId").toIntOrNull()?.let(dateFormats::add)
        }
        val xfs = (styles.getElementsByTagNameNS("*", "cellXfs").item(0) as? Element)?.getElementsByTagNameNS("*", "xf")
        if (xfs != null) for (i in 0 until xfs.length) if ((xfs.item(i) as Element).getAttribute("numFmtId").toIntOrNull() in dateFormats) dateStyles += i
    }
    val shared = entries["xl/sharedStrings.xml"]?.let { document(it).getElementsByTagNameNS("*", "si").let { nodes -> (0 until nodes.length).map { idx ->
        val si = nodes.item(idx) as Element; val ts = si.getElementsByTagNameNS("*", "t"); (0 until ts.length).joinToString("") { ts.item(it).textContent }
    } } } ?: emptyList()
    val rowNodes = document(sheet).getElementsByTagNameNS("*", "row")
    require(rowNodes.length <= 20_001) { "Excel 行数超过 20,000 行" }
    return (0 until rowNodes.length).map { ri ->
        val row = rowNodes.item(ri) as Element; val cells = row.getElementsByTagNameNS("*", "c"); val values = mutableMapOf<Int, String>()
        require(cells.length <= 100) { "Excel 列数过多" }
        for (ci in 0 until cells.length) {
            val cell = cells.item(ci) as Element
            val letters = cell.getAttribute("r").takeWhile(Char::isLetter)
            require(letters.length in 1..3) { "Excel 单元格坐标不正确" }
            val col = letters.fold(0) { a, c -> a * 26 + (c.uppercaseChar() - 'A' + 1) } - 1
            require(col in 0..99) { "账单只支持前 100 列" }
            val type = cell.getAttribute("t"); val value = cell.getElementsByTagNameNS("*", "v").item(0)?.textContent.orEmpty()
            val text = when (type) {
                "s" -> shared.getOrNull(value.toIntOrNull() ?: -1).orEmpty()
                "inlineStr" -> cell.getElementsByTagNameNS("*", "t").let { ts -> (0 until ts.length).joinToString("") { ts.item(it).textContent } }
                "b" -> if (value == "1") "TRUE" else "FALSE"
                else -> if (cell.getAttribute("s").toIntOrNull() in dateStyles) excelDate(value, date1904)?.format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")).orEmpty() else value
            }
            values[col] = text
        }
        (0..(values.keys.maxOrNull() ?: -1)).map { values[it].orEmpty() }
    }
}

private fun excelDate(value: String, date1904: Boolean = false): LocalDateTime? = runCatching {
    val serial = value.toBigDecimal()
    require(serial >= BigDecimal.ZERO && serial < BigDecimal("2958466"))
    val days = serial.toLong()
    val nanos = serial.subtract(BigDecimal.valueOf(days)).multiply(BigDecimal.valueOf(86_400_000_000_000L)).toLong()
    val base = if (date1904) java.time.LocalDate.of(1904, 1, 1) else java.time.LocalDate.of(1899, 12, 30)
    base.plusDays(days).atStartOfDay().plusNanos(nanos)
}.getOrNull()

private fun normalizeHeader(value: String): String = value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

private fun interpretRows(rows: List<List<String>>, fileName: String): BillPreview {
    require(rows.isNotEmpty()) { "文件中没有账单行" }
    val headerAt = rows.take(40).indexOfFirst { row ->
        val h = row.map(::normalizeHeader).toSet()
        h.any { it in setOf("收支", "收支类型", "收付款") } && h.any { it.contains("金额") } && h.any { it.contains("时间") }
    }
    require(headerAt >= 0) { "没找到支付宝或微信账单表头。请导出 CSV 或 XLSX 格式账单" }
    val header = rows[headerAt].map(::normalizeHeader)
    fun index(vararg aliases: String): Int {
        for (alias in aliases) { val exact = header.indexOf(normalizeHeader(alias)); if (exact >= 0) return exact }
        return -1
    }
    val timeCol = index("付款时间", "支付时间", "交易时间", "交易创建时间")
    val kindCol = index("收支", "收支类型", "交易类型")
    val amountCol = index("金额（元）", "金额(元)", "金额")
    val statusCol = index("当前状态", "交易状态", "状态")
    val idCol = index("交易单号", "支付宝交易号", "交易号", "交易订单号", "商户订单号", "商家订单号")
    val nameCol = index("商品", "商品名称", "商品说明", "交易对方", "对方")
    val noteCol = index("备注")
    require(timeCol >= 0 && kindCol >= 0 && amountCol >= 0) { "账单缺少交易时间、收支或金额列" }
    val wechat = when {
        header.contains("交易单号") || header.contains("商户单号") -> true
        header.contains("支付宝交易号") || header.contains("商家订单号") || header.contains("交易号") -> false
        fileName.contains("微信") -> true
        fileName.contains("支付宝") -> false
        else -> throw IllegalArgumentException("无法判断账单来源，请使用支付宝或微信导出的原始文件")
    }
    val source = if (wechat) "WECHAT" else "ALIPAY"
    var skipped = 0; var refundedExpenses = 0; var missingIds = 0; val warnings = mutableListOf<String>(); val output = mutableListOf<BillCandidate>()
    val fallbackOccurrences = mutableMapOf<String, Int>()
    for (row in rows.drop(headerAt + 1)) {
        if (row.all(String::isBlank)) continue
        fun value(i: Int) = row.getOrNull(i)?.trim().orEmpty()
        val status = value(statusCol).lowercase(Locale.ROOT)
        val completed = listOf("成功", "已完成", "支付成功", "退款成功", "已全额退款", "部分退款").any(status::contains)
        if (status.isNotEmpty() && !completed) { skipped++; continue }
        val rawAmount = value(amountCol).replace("¥", "").replace("￥", "").replace(",", "").trim()
        val amount = runCatching { BigDecimal(rawAmount).abs().movePointRight(2).longValueExact() }.getOrNull()
        val dateTime = parseDate(value(timeCol))
        if (amount == null || amount !in 1..MAX_AMOUNT_CENTS || dateTime == null || dateTime.year !in 1900..2200) { skipped++; continue }
        val label = value(kindCol)
        val refund = label.contains("退款") || status.contains("退款")
        val kind = when { label.contains("收入") || label == "收" || label == "退款" -> "INCOME"; label.contains("支出") || label == "支" || label.contains("付款") -> "EXPENSE"; else -> { skipped++; continue } }
        if (kind == "EXPENSE" && status.contains("退款")) { skipped++; refundedExpenses++; continue }
        val title = value(nameCol).ifBlank { if (refund) "退款" else "导入账单" }.take(100)
        val rawId = value(idCol).removePrefix("\'").trim(); if (rawId.isBlank()) missingIds++
        val canonical = if (rawId.isNotBlank()) rawId else listOf(dateTime.toString(), kind, amount.toString(), title, value(noteCol)).joinToString("|")
        val fallbackIndex = if (rawId.isBlank()) fallbackOccurrences.merge(canonical, 1, Int::plus) ?: 1 else 0
        val fingerprint = sha256(if (rawId.isBlank()) "$canonical|same-content-row=$fallbackIndex" else canonical)
        val category = if (refund) "退款" else guessCategory(title)
        val account = if (source == "WECHAT") "微信" else "支付宝"
        val tx = LedgerTransaction(
            id = "bill-$fingerprint", kind = kind, amountCents = amount, date = dateTime.toLocalDate().toString(),
            category = category, account = account, note = (title + value(noteCol).takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()).take(1000),
        )
        output += BillCandidate(fingerprint, tx)
    }
    if (refundedExpenses > 0) warnings += "已跳过 $refundedExpenses 笔带退款状态的原支出，请核对原消费和实际退款后手动补记。明确标为收入的退款可导入。"
    if (missingIds > 0) warnings += "$missingIds 笔记录没有交易编号，将按时间、金额和内容识别重复，请核对重叠文件。"
    if (skipped > 0) warnings += "失败、关闭、不计收支和无法识别的行已跳过。"
    if (output.isEmpty()) warnings += "没有找到可导入的成功收支记录。"
    require(output.groupBy { it.fingerprint }.values.none { group -> group.map { it.contentHash }.distinct().size > 1 }) { "同一交易编号对应不同内容，请核对账单文件" }
    return BillPreview(source, output.distinctBy { it.fingerprint }, skipped, warnings)
}

private fun parseDate(value: String): LocalDateTime? {
    val clean = value.trim().removeSuffix(" CST")
    if (clean.toBigDecimalOrNull() != null) return excelDate(clean)
    val patterns = listOf("uuuu-MM-dd HH:mm:ss", "uuuu-MM-dd HH:mm", "uuuu/MM/dd HH:mm:ss", "uuuu/MM/dd HH:mm", "uuuu-MM-dd'T'HH:mm:ss", "uuuu-MM-dd'T'HH:mm:ssXXX", "uuuu-MM-dd")
    for (pattern in patterns) {
        try {
            val formatter = DateTimeFormatter.ofPattern(pattern, Locale.ROOT).withResolverStyle(java.time.format.ResolverStyle.STRICT)
            return if (pattern.contains("XXX")) java.time.OffsetDateTime.parse(clean, formatter).toLocalDateTime() else if (pattern == "uuuu-MM-dd") java.time.LocalDate.parse(clean, formatter).atStartOfDay() else LocalDateTime.parse(clean, formatter)
        } catch (_: DateTimeParseException) { }
    }
    return null
}

private fun guessCategory(title: String): String {
    val text = title.lowercase(Locale.ROOT)
    return when {
        listOf("地铁", "公交", "交通", "打车", "滴滴", "高铁", "铁路").any(text::contains) -> "交通"
        listOf("外卖", "餐", "咖啡", "奶茶", "食堂").any(text::contains) -> "餐饮"
        listOf("会员", "vip", "订阅", "视频", "音乐").any(text::contains) -> "订阅"
        else -> "其他"
    }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
