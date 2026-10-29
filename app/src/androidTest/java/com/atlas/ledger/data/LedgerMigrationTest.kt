package com.atlas.ledger.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.atlas.ledger.domain.parseBillFile
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LedgerDatabase::class.java)
    private val name = "ledger-migration-qa.db"
    @After fun cleanup() { InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name) }
    @Test fun versionOneUpgradePreservesFourExistingTablesAndCanUseNewFeatures() = runBlocking {
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO subscriptions VALUES ('s', 'QA订阅', '数字服务', '月付', 0, '')")
            execSQL("INSERT INTO subscription_periods VALUES ('p', 's', 3100, '2026-10-01', '2026-11-01')")
            execSQL("INSERT INTO durables VALUES ('d', 'QA手机', '数码', 300000, '2026-10-01', '2026-10-05', 1000, NULL, '')")
            execSQL("INSERT INTO transactions VALUES ('st', 'EXPENSE', 3100, '2026-09-30', '订阅', '微信', '', 'QA订阅', 'SUBSCRIPTION', 'p')")
            execSQL("INSERT INTO transactions VALUES ('dt', 'EXPENSE', 300000, '2026-10-01', '耐用品', '银行卡', '', 'QA手机', 'DURABLE', 'd')")
            close()
        }
        helper.runMigrationsAndValidate(name, 3, true, LedgerDatabase.MIGRATION_1_2, LedgerDatabase.MIGRATION_2_3).close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).addMigrations(LedgerDatabase.MIGRATION_1_2, LedgerDatabase.MIGRATION_2_3).build()
        try {
            assertEquals(2, db.dao().transactions().size); assertEquals(1, db.dao().subscriptions().size); assertEquals(1, db.dao().periods().size); assertEquals(1, db.dao().durables().size)
            assertEquals("2026-09-30", db.dao().transactions().single { it.id == "st" }.date)
            assertEquals("2026-10-05", db.dao().durables().single().startDate)
            val repo = LedgerRepository(db)
            repo.saveRecurringRule(RecurringRule("r", "QA公交", 200, weekdays = "1,2,3,4,5,6,7", time = "20:00", startDate = "2026-10-13"))
            assertEquals(2, db.dao().transactions().size)
            assertEquals(0L, db.dao().durables().single().residualCents)
            assertTrue(repo.exportJson().contains("recurringOccurrences"))
        } finally { db.close() }
    }
    @Test fun xlsxParserWorksWithAndroidXmlImplementation() {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("alipay-sample.xlsx").use { it.readBytes() }
        val preview = parseBillFile("支付宝.xlsx", bytes)
        assertEquals("ALIPAY", preview.source); assertEquals(350L, preview.candidates.single().transaction.amountCents)
    }
}
