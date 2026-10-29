package com.atlas.ledger.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.atlas.ledger.PendingBillImport
import com.atlas.ledger.data.LedgerTransaction
import com.atlas.ledger.domain.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportPreviewUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun previewDefaultsExcludeDuplicatesAndSuspectsButAllowsExplicitChoice() {
        fun bill(id: String, note: String) = BillCandidate(id, LedgerTransaction(id, "EXPENSE", 250, "2026-10-13", "交通", "微信", note = note))
        val pending = PendingBillImport(BillPreview("WECHAT", listOf(bill("a", "QA新公交"), bill("b", "QA已导入"), bill("c", "QA可能重复")), 0, emptyList()), setOf("b"), setOf("c"))
        var selected: Set<String>? = null
        compose.setContent { LedgerTheme { BillImportPage(pending, false, {}, {}, { selected = it }) } }
        compose.onNodeWithText("导入 1 笔").assertExists()
        compose.onNodeWithText("QA可能重复").performScrollTo().performClick()
        compose.onNodeWithText("导入 2 笔").performScrollTo().performClick()
        assertEquals(setOf("a", "c"), selected)
    }
}
