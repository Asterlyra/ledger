package com.atlas.ledger.ui

import android.graphics.Bitmap
import android.view.View
import android.widget.DatePicker
import android.widget.NumberPicker
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.ViewAction
import androidx.test.espresso.UiController
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.hamcrest.Matcher
import java.time.LocalDate
import com.atlas.ledger.data.*
import com.atlas.ledger.domain.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.ledger.LedgerViewModel
import com.atlas.ledger.data.LedgerDatabase
import com.atlas.ledger.data.LedgerRepository
import androidx.room.Room
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.After
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** In-memory storage keeps UI fixtures isolated from the user's real ledger. */
@RunWith(AndroidJUnit4::class)
class LedgerFlowTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: LedgerDatabase
    private val store = ViewModelStore()
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java).build()
        val vm = LedgerViewModel(ApplicationProvider.getApplicationContext(), LedgerRepository(db))
        store.put("ui-qa", vm)
        compose.setContent { LedgerTheme { LedgerApp(vm) } }
    }
    @After fun cleanup() { compose.runOnIdle { store.clear() }; db.close() }
    private fun matching(text: String) = hasText(text) or hasContentDescription(text)
    private fun click(text: String) { waitFor(text); compose.onAllNodes(matching(text)).onLast().performClick() }
    private fun enter(label: String, text: String) { compose.onNodeWithText(label).performScrollTo().performTextInput(text) }
    private fun waitFor(text: String) { compose.waitUntil(10_000) { compose.onAllNodes(matching(text)).fetchSemanticsNodes().isNotEmpty() } }
    private fun waitForRecord(note: String) {
        compose.waitUntil(10_000) { runBlocking { db.dao().transactions().any { it.note == note } } }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot().useBitmap { bitmap ->
            File(instrumentation.targetContext.filesDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    private fun pickDate(label: String, current: LocalDate, date: LocalDate) {
        compose.onNodeWithContentDescription("选择$label，$current").performScrollTo().performClick()
        onView(isAssignableFrom(DatePicker::class.java)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(DatePicker::class.java)
            override fun getDescription() = "set native date picker to $date"
            override fun perform(uiController: UiController, view: View) {
                (view as DatePicker).updateDate(date.year, date.monthValue - 1, date.dayOfMonth)
                uiController.loopMainThreadUntilIdle()
            }
        })
        onView(withText(android.R.string.ok)).perform(androidx.test.espresso.action.ViewActions.click())
        compose.waitForIdle()
    }
    private fun Bitmap.useBitmap(block: (Bitmap) -> Unit) { try { block(this) } finally { recycle() } }

    @Test fun recordIncomeExpenseTransferSubscriptionAndDurable() {
        waitFor("日常账本")
        waitFor("记一笔")
        screenshot("01-empty-home")
        click("记一笔")
        enter("金额（元）", "12.50")
        enter("备注（可选）", "QA午餐")
        click("保存")
        waitForRecord("QA午餐")

        click("记一笔")
        click("收入")
        enter("金额（元）", "8000")
        enter("备注（可选）", "QA工资")
        click("保存")
        waitForRecord("QA工资")

        click("记一笔")
        click("转账")
        enter("金额（元）", "500")
        enter("备注（可选）", "QA账户转账")
        click("保存")
        waitForRecord("QA账户转账")
        compose.onAllNodesWithText("¥12.50").onFirst().assertExists()
        compose.onAllNodesWithText("¥8000.00").onFirst().assertExists()

        click("订阅")
        click("添加订阅")
        enter("订阅名称，例如视频、云存储", "QA云存储")
        click("年付")
        enter("本周期价格（元）", "365")
        val today = LocalDate.now()
        pickDate("生效日期", today, today.minusYears(1))
        click("保存")
        waitFor("周期记录 (1)")
        compose.onNodeWithText("¥1.00 / 天").assertExists()
        screenshot("02-subscriptions")

        click("添加周期")
        // Pre-filled next period must create a second row, not replace the first.
        click("保存")
        waitFor("周期记录 (2)")
        click("周期记录 (2)")
        screenshot("03-subscription-history")

        click("物品")
        click("添加物品")
        enter("物品名称，例如手机、电脑", "QA手机")
        enter("购买价格（元）", "3000")
        enter("预计使用总天数（可选）", "1000")
        compose.onNodeWithText("开始使用日期", useUnmergedTree = true).assertDoesNotExist()
        click("保存")
        waitFor("¥3000.00 / 天")
        val savedItem = runBlocking { db.dao().durables().single() }
        org.junit.Assert.assertEquals(savedItem.purchaseDate, savedItem.startDate)
        compose.onNodeWithText("¥3.00 / 天").assertExists()
        compose.onNodeWithText("¥3000.00 / 天").assertExists()

        click("QA手机")
        compose.onNodeWithText("购买价格（元）").performScrollTo().performTextClearance()
        enter("购买价格（元）", "2000")
        click("保存")
        waitFor("¥2.00 / 天")
        screenshot("04-durables")

        click("首页")
        // Only manual spending is real; subscription and durable records are forecasts.
        waitFor("¥12.50")
        compose.onNodeWithText("¥7987.50").assertExists()
        compose.onNodeWithText("日均消费").assertExists()
        compose.onNodeWithText("预计日均消费").assertExists()
        compose.onNodeWithText("¥2001.00 / 天").assertExists()
        compose.onNodeWithText("¥3.00 / 天").assertExists()
        screenshot("05-home-with-data")

        click("我的")
        compose.onNodeWithText("3 笔正式账单").assertExists()
        compose.onNodeWithText("1 项订阅 · 2 个预期周期 · 1 件物品").assertExists()
        screenshot("06-backup-page")
    }
    @Test fun fixedForecastCanBeCreatedSkippedAndRestoredWithoutBills() {
        val today = LocalDate.now()
        click("订阅"); click("固定消费"); click("添加固定消费")
        enter("内容，例如公交、地铁", "QA固定公交"); enter("每次金额（元）", "2.50"); click("保存")
        compose.waitUntil(10_000) { runBlocking { db.dao().recurringRules().size == 1 } }
        org.junit.Assert.assertTrue(runBlocking { db.dao().transactions().isEmpty() })
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("${today} · 预计发生"))
        click("取消QA固定公交在${today}的预期")
        compose.waitUntil(10_000) { runBlocking { db.dao().recurringOccurrences().singleOrNull()?.status == "SKIPPED" } }
        org.junit.Assert.assertTrue(runBlocking { db.dao().transactions().isEmpty() })
        screenshot("07-fixed-forecast-canceled")
        click("恢复QA固定公交在${today}的预期")
        compose.waitUntil(10_000) { runBlocking { db.dao().recurringOccurrences().isEmpty() } }
        org.junit.Assert.assertTrue(runBlocking { db.dao().transactions().isEmpty() })
    }

    @Test fun weeklySubscriptionUsesSevenDaysAndSingleDate() {
        click("订阅"); click("添加订阅")
        enter("订阅名称，例如视频、云存储", "QA周订阅")
        click("周付"); enter("本周期价格（元）", "7")
        compose.onNodeWithText("实际支付日期", useUnmergedTree = true).assertDoesNotExist()
        screenshot("09-weekly-subscription-form")
        click("保存"); waitFor("周期记录 (1)")
        val period = runBlocking { db.dao().periods().single() }
        org.junit.Assert.assertEquals("周付", runBlocking { db.dao().subscriptions().single().billingCycle })
        org.junit.Assert.assertEquals(7L, periodDays(period))
        org.junit.Assert.assertTrue(runBlocking { db.dao().transactions().isEmpty() })
        compose.onNodeWithText("¥1.00 / 天").assertExists()
        screenshot("10-weekly-subscription")
    }
    @Test fun oldDifferentDatesAreKeptUntilPrimaryDateChanges() {
        val today = LocalDate.now()
        val start = today.minusDays(14)
        val sub = Subscription("legacy-s", "QA历史订阅", billingCycle = "周付")
        val period = SubscriptionPeriod("legacy-p", sub.id, 700, start.toString(), start.plusDays(7).toString())
        val payment = LedgerTransaction("legacy-st", "EXPENSE", 700, start.minusDays(1).toString(), "订阅", "微信", note = sub.name, origin = "SUBSCRIPTION", originId = period.id)
        val purchase = today.minusDays(20)
        val item = Durable("legacy-d", "QA历史物品", amountCents = 10000, purchaseDate = purchase.toString(), startDate = purchase.plusDays(5).toString(), expectedDays = 100)
        val itemPayment = LedgerTransaction("legacy-dt", "EXPENSE", 10000, purchase.toString(), "耐用品", "微信", note = item.name, origin = "DURABLE", originId = item.id)
        runBlocking { LedgerRepository(db).importJson(encodeBackup(LedgerBackup(transactions = listOf(payment, itemPayment), subscriptions = listOf(sub), periods = listOf(period), durables = listOf(item)))) }
        click("订阅"); click("周期记录 (1)")
        val range = "$start — ${start.plusDays(6)}"
        click(range); enter("订阅备注（可选）", "保留历史日期"); click("保存")
        compose.waitUntil(10_000) { runBlocking { db.dao().subscriptions().single().note.isNotEmpty() } }
        org.junit.Assert.assertEquals(payment.date, runBlocking { db.dao().transactions().single { it.id == payment.id }.date })
        click(range); pickDate("生效日期", start, start.plusDays(1)); click("保存")
        compose.waitUntil(10_000) { runBlocking { db.dao().periods().single().startDate == start.plusDays(1).toString() } }
        org.junit.Assert.assertEquals(payment.date, runBlocking { db.dao().transactions().single { it.id == payment.id }.date })
        click("物品"); click(item.name); enter("备注（可选）", "保留使用日期"); click("保存")
        compose.waitUntil(10_000) { runBlocking { db.dao().durables().single().note.isNotEmpty() } }
        org.junit.Assert.assertEquals(item.startDate, runBlocking { db.dao().durables().single().startDate })
        click(item.name); pickDate("购入日期", purchase, purchase.plusDays(1)); click("保存")
        compose.waitUntil(10_000) { runBlocking { db.dao().durables().single().purchaseDate == purchase.plusDays(1).toString() } }
        org.junit.Assert.assertEquals(purchase.plusDays(1).toString(), runBlocking { db.dao().durables().single().startDate })
        org.junit.Assert.assertEquals(2, runBlocking { db.dao().transactions().size })
    }

    @Test fun datePickerHasDirectYearSelection() {
        val today = LocalDate.now()
        click("记一笔"); enter("金额（元）", "1")
        compose.onNodeWithContentDescription("选择日期，$today").performScrollTo().performClick()
        onView(withText("选择年份")).perform(androidx.test.espresso.action.ViewActions.click())
        onView(isAssignableFrom(NumberPicker::class.java)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(NumberPicker::class.java)
            override fun getDescription() = "choose year 2020"
            override fun perform(uiController: UiController, view: View) { (view as NumberPicker).value = 2020; uiController.loopMainThreadUntilIdle() }
        })
        screenshot("11-year-picker")
        onView(withText("确定")).perform(androidx.test.espresso.action.ViewActions.click())
        onView(withText(android.R.string.ok)).perform(androidx.test.espresso.action.ViewActions.click())
        click("保存")
        compose.waitUntil(10_000) { runBlocking { db.dao().transactions().size == 1 } }
        org.junit.Assert.assertTrue(runBlocking { db.dao().transactions().single().date.startsWith("2020-") })
    }

    @Test fun sameExpenseNameOpensDateDetailsAndAllHistoryWithoutMergingRows() {
        val today = LocalDate.now(); val yesterday = today.minusDays(1)
        val rows = listOf(
            LedgerTransaction("a", "EXPENSE", 200, yesterday.toString(), "交通", "微信", note = "QA公交"),
            LedgerTransaction("b", "EXPENSE", 300, today.toString(), "交通", "支付宝", note = "QA公交"),
            LedgerTransaction("c", "EXPENSE", 900, today.minusMonths(1).toString(), "交通", "微信", note = "QA公交"),
            LedgerTransaction("income", "INCOME", 1000, today.toString(), "工资", "微信", note = "QA工资"),
            LedgerTransaction("legacy", "EXPENSE", 999, today.toString(), "交通", "微信", note = "QA公交", origin = "RECURRING", originId = "old")
        )
        runBlocking { LedgerRepository(db).importJson(encodeBackup(LedgerBackup(transactions = rows))) }
        click("账单"); waitFor("QA公交"); compose.onNodeWithText("−¥5.00").assertExists()
        click("QA公交"); waitFor("本月明细")
        compose.onNodeWithText("${today} · 交通 · 支付宝").assertExists()
        compose.onNodeWithText("${yesterday} · 交通 · 微信").assertExists()
        click("全部历史"); compose.onNodeWithText("${today.minusMonths(1)} · 交通 · 微信").assertExists()
        click("${yesterday} · 交通 · 微信")
        compose.onNodeWithText("金额（元）").performScrollTo().performTextReplacement("3.50")
        click("保存"); compose.waitUntil(10_000) { runBlocking { db.dao().transactions().single { it.id == "a" }.amountCents == 350L } }
        org.junit.Assert.assertEquals(5, runBlocking { db.dao().transactions().size })
        org.junit.Assert.assertEquals(rows.last(), runBlocking { db.dao().transactions().single { it.id == "legacy" } })
        screenshot("12-grouped-bill-details")
    }

    @Test fun purchasePriceDefaultsDescendingAndSortingCanBeChanged() {
        val today = LocalDate.now()
        val cheap = Durable("cheap", "QA低价物品", amountCents = 10000, purchaseDate = today.toString(), startDate = today.toString(), expectedDays = 100)
        val expensive = cheap.copy(id = "expensive", name = "QA高价物品", amountCents = 30000, residualCents = 10000)
        runBlocking { LedgerRepository(db).saveDurable(cheap); LedgerRepository(db).saveDurable(expensive) }
        click("物品"); waitFor("QA高价物品")
        org.junit.Assert.assertTrue(compose.onNodeWithText("QA高价物品").getUnclippedBoundsInRoot().top < compose.onNodeWithText("QA低价物品").getUnclippedBoundsInRoot().top)
        click("排序，金额从高到低"); click("金额从低到高")
        org.junit.Assert.assertTrue(compose.onNodeWithText("QA低价物品").getUnclippedBoundsInRoot().top < compose.onNodeWithText("QA高价物品").getUnclippedBoundsInRoot().top)
        click("QA高价物品")
        compose.onNodeWithText("折旧价（预计到期残值，元）").performScrollTo().assertExists()
        screenshot("13-residual-item-form"); click("取消")
        click("排序，金额从低到高"); click("金额从高到低")
    }

}
