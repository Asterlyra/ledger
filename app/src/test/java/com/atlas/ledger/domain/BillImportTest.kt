package com.atlas.ledger.domain

import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.Charset
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BillImportTest {
    private val header = "交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注"
    private fun parse(vararg rows: String) = parseBillFile("微信.csv", (listOf(header) + rows).joinToString("\r\n").toByteArray())
    @Test fun csvHandlesBomQuotesMultilineAndSuccessOnly() {
        val text = "\uFEFF微信支付账单\n$header\n2026-10-13 08:00:00,消费,公交公司,\"公交,早班\",支出,￥2.50,零钱,支付成功,w1,m1,\"两行\n备注\"\n2026-10-13 08:01:00,消费,店铺,购物,支出,10,零钱,交易关闭,w2,m2,"
        val result = parseBillFile("微信.csv", text.toByteArray())
        assertEquals("WECHAT", result.source); assertEquals(1, result.candidates.size); assertEquals(1, result.skippedRows)
        val tx = result.candidates.single().transaction
        assertEquals(250L, tx.amountCents); assertEquals("交通", tx.category); assertTrue(tx.note.contains("公交,早班")); assertTrue(tx.note.contains("两行\n备注"))
    }
    @Test fun alipayGbkPrefersPaymentDateAndTradeIdOverMerchantId() {
        val text = "支付宝交易记录\n交易号,商家订单号,交易创建时间,付款时间,交易对方,商品名称,金额（元）,收/支,交易状态,备注\na1,m1,2026-10-12 23:59:00,2026-10-13 00:01:00,餐厅,午餐,12.30,支出,交易成功,"
        val result = parseBillFile("支付宝.csv", text.toByteArray(Charset.forName("GB18030")))
        assertEquals("ALIPAY", result.source); assertEquals("2026-10-13", result.candidates.single().transaction.date)
        assertEquals(1230L, result.candidates.single().transaction.amountCents)
    }
    @Test fun refundedOriginalExpenseIsNotConvertedIntoIncome() {
        val result = parse("2026-10-13 08:00:00,消费,商店,购物,支出,100,零钱,已全额退款,p1,m1,", "2026-10-13 09:00:00,退款,商店,退款,收入,100,零钱,退款成功,r1,m1,")
        assertEquals(1, result.candidates.size); assertEquals("INCOME", result.candidates.single().transaction.kind); assertEquals("退款", result.candidates.single().transaction.category)
        assertEquals(1, result.skippedRows); assertTrue(result.warnings.any { it.contains("原支出") })
    }
    @Test fun missingIdsKeepTwoIdenticalPaymentsAndStableFingerprints() {
        val row = "2026-10-13 08:00:00,消费,公交公司,公交,支出,2,零钱,支付成功,,,"
        val first = parse(row, row); val second = parse(row, row)
        assertEquals(2, first.candidates.size); assertEquals(2, first.candidates.map { it.fingerprint }.distinct().size)
        assertEquals(first.candidates.map { it.fingerprint }, second.candidates.map { it.fingerprint })
    }
    @Test fun invalidCalendarDatesPrecisionAndNeutralRowsAreSkipped() {
        val result = parse("2026-02-30 08:00:00,消费,公司,公交,支出,2,零钱,支付成功,b1,m1,", "2026-10-13 08:00:00,消费,公司,公交,支出,2.001,零钱,支付成功,b2,m2,", "2026-10-13 08:00:00,转账,朋友,转账,不计收支,2,零钱,支付成功,b3,m3,")
        assertTrue(result.candidates.isEmpty()); assertEquals(3, result.skippedRows)
    }
    @Test fun conflictingRowsWithSameTradeNumberAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { parse("2026-10-13 08:00:00,消费,公司,公交,支出,2,零钱,支付成功,same,m1,", "2026-10-13 08:00:00,消费,公司,公交,支出,3,零钱,支付成功,same,m1,") }
    }
    @Test fun excelReadsSharedStringsRelocatedSheetAndNumericDate() {
        val result = parseBillFile("支付宝.xlsx", xlsx(false))
        assertEquals("ALIPAY", result.source); assertEquals("2026-10-13", result.candidates.single().transaction.date); assertEquals(350L, result.candidates.single().transaction.amountCents)
    }
    @Test fun excelSupports1904DateSystem() {
        val result = parseBillFile("支付宝.xlsx", xlsx(true))
        assertEquals("2026-10-13", result.candidates.single().transaction.date)
    }
    @Test fun unknownTableAndUnsafeXmlAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { parseBillFile("bank.csv", "日期,金额\n2026-10-13,100".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { parseBillFile("支付宝.xlsx", xlsx(false, true)) }
    }
    private fun xlsx(date1904: Boolean, unsafe: Boolean = false): ByteArray {
        val headers = listOf("交易时间", "交易分类", "交易对方", "商品说明", "收/支", "金额", "交易状态", "交易订单号", "商家订单号")
        val strings = headers + listOf("消费", "地铁公司", "地铁", "支出", "交易成功", "trade-001", "merchant-001")
        val serial = java.time.temporal.ChronoUnit.DAYS.between(if (date1904) java.time.LocalDate.of(1904, 1, 1) else java.time.LocalDate.of(1899, 12, 30), java.time.LocalDate.of(2026, 10, 13))
        val ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        val sheet = "<worksheet xmlns=\"$ns\"><sheetData><row r=\"1\">" + headers.indices.joinToString("") { "<c r=\"${('A'.code + it).toChar()}1\" t=\"s\"><v>$it</v></c>" } + "</row><row r=\"2\"><c r=\"A2\" s=\"1\"><v>$serial.5</v></c>" + listOf("B" to 9, "C" to 10, "D" to 11, "E" to 12).joinToString("") { (col, index) -> "<c r=\"${col}2\" t=\"s\"><v>$index</v></c>" } + "<c r=\"F2\"><v>3.50</v></c>" + listOf("G" to 13, "H" to 14, "I" to 15).joinToString("") { (col, index) -> "<c r=\"${col}2\" t=\"s\"><v>$index</v></c>" } + "</row></sheetData></worksheet>"
        val contents = linkedMapOf(
            "xl/workbook.xml" to "<workbook xmlns=\"$ns\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><workbookPr date1904=\"${if (date1904) 1 else 0}\"/><sheets><sheet name=\"账单\" sheetId=\"2\" r:id=\"rId2\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId2\" Target=\"worksheets/sheet2.xml\"/></Relationships>",
            "xl/sharedStrings.xml" to (if (unsafe) "<!DOCTYPE sst [<!ENTITY unsafe SYSTEM \"file:///nothing\">]>" else "") + "<sst xmlns=\"$ns\">" + strings.joinToString("") { "<si><t>$it</t></si>" } + "</sst>",
            "xl/styles.xml" to "<styleSheet xmlns=\"$ns\"><cellXfs><xf numFmtId=\"0\"/><xf numFmtId=\"22\"/></cellXfs></styleSheet>",
            "xl/worksheets/sheet2.xml" to sheet,
        )
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> contents.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() } }
        return output.toByteArray()
    }
}
