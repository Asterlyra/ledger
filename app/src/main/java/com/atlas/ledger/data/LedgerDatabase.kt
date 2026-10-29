package com.atlas.ledger.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {
    @Query("SELECT * FROM transactions ORDER BY date DESC, id DESC") fun observeTransactions(): Flow<List<LedgerTransaction>>
    @Query("SELECT * FROM subscriptions ORDER BY name") fun observeSubscriptions(): Flow<List<Subscription>>
    @Query("SELECT * FROM subscription_periods ORDER BY startDate DESC") fun observePeriods(): Flow<List<SubscriptionPeriod>>
    @Query("SELECT * FROM durables ORDER BY purchaseDate DESC") fun observeDurables(): Flow<List<Durable>>
    @Query("SELECT * FROM transactions") suspend fun transactions(): List<LedgerTransaction>
    @Query("SELECT * FROM subscriptions") suspend fun subscriptions(): List<Subscription>
    @Query("SELECT * FROM subscription_periods") suspend fun periods(): List<SubscriptionPeriod>
    @Query("SELECT * FROM durables") suspend fun durables(): List<Durable>
    @Query("SELECT * FROM recurring_rules ORDER BY enabled DESC, name") fun observeRecurringRules(): Flow<List<RecurringRule>>
    @Query("SELECT * FROM recurring_occurrences ORDER BY date DESC, id DESC") fun observeRecurringOccurrences(): Flow<List<RecurringOccurrence>>
    @Query("SELECT * FROM import_receipts") suspend fun importReceipts(): List<ImportReceipt>
    @Query("SELECT * FROM import_receipts ORDER BY importedAt DESC") fun observeImportReceipts(): Flow<List<ImportReceipt>>
    @Query("SELECT * FROM recurring_rules") suspend fun recurringRules(): List<RecurringRule>
    @Query("SELECT * FROM recurring_occurrences") suspend fun recurringOccurrences(): List<RecurringOccurrence>
    @Upsert suspend fun saveTransaction(value: LedgerTransaction)
    @Upsert suspend fun saveSubscription(value: Subscription)
    @Upsert suspend fun savePeriod(value: SubscriptionPeriod)
    @Upsert suspend fun saveDurable(value: Durable)
    @Upsert suspend fun saveRecurringRule(value: RecurringRule)
    @Upsert suspend fun saveRecurringOccurrence(value: RecurringOccurrence)
    @Upsert suspend fun saveImportReceipt(value: ImportReceipt)
    @Query("SELECT * FROM recurring_rules WHERE id = :id") suspend fun recurringRule(id: String): RecurringRule?
    @Query("SELECT * FROM recurring_occurrences WHERE ruleId = :ruleId AND date = :date LIMIT 1") suspend fun recurringOccurrence(ruleId: String, date: String): RecurringOccurrence?
    @Query("SELECT * FROM import_receipts WHERE source = :source AND fingerprint = :fingerprint LIMIT 1") suspend fun importReceipt(source: String, fingerprint: String): ImportReceipt?
    @Query("SELECT fingerprint FROM import_receipts WHERE source = :source AND fingerprint IN (:fingerprints)") suspend fun existingImportFingerprints(source: String, fingerprints: List<String>): List<String>
    @Query("DELETE FROM recurring_rules WHERE id = :id") suspend fun deleteRecurringRule(id: String)
    @Query("DELETE FROM recurring_occurrences WHERE id = :id AND status = 'SKIPPED'") suspend fun deleteSkippedOccurrence(id: String)
    @Query("DELETE FROM import_receipts WHERE id = :id") suspend fun deleteImportReceipt(id: String)
    @Query("DELETE FROM transactions WHERE id = :id AND origin IS NULL") suspend fun deleteManualTransaction(id: String)
    @Query("DELETE FROM subscriptions WHERE id = :id") suspend fun deleteSubscription(id: String)
    @Query("DELETE FROM subscription_periods WHERE id = :id") suspend fun deletePeriod(id: String)
    @Query("DELETE FROM durables WHERE id = :id") suspend fun deleteDurable(id: String)
}

@Database(entities = [LedgerTransaction::class, Subscription::class, SubscriptionPeriod::class, Durable::class, RecurringRule::class, RecurringOccurrence::class, ImportReceipt::class], version = 3, exportSchema = true)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao
    companion object {
        @Volatile private var instance: LedgerDatabase? = null
        fun get(context: Context): LedgerDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, "daily-ledger.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { instance = it }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `durables` ADD COLUMN `residualCents` INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `recurring_rules` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, `category` TEXT NOT NULL, `account` TEXT NOT NULL, `weekdays` TEXT NOT NULL, `time` TEXT NOT NULL, `startDate` TEXT NOT NULL, `endDate` TEXT, `nextDate` TEXT NOT NULL, `enabled` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `recurring_occurrences` (`id` TEXT NOT NULL, `ruleId` TEXT NOT NULL, `date` TEXT NOT NULL, `status` TEXT NOT NULL, `name` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, `category` TEXT NOT NULL, `account` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`ruleId`) REFERENCES `recurring_rules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_occurrences_ruleId` ON `recurring_occurrences` (`ruleId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_recurring_occurrences_ruleId_date` ON `recurring_occurrences` (`ruleId`, `date`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `import_receipts` (`id` TEXT NOT NULL, `source` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `transactionId` TEXT, `importedAt` TEXT NOT NULL, `contentHash` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_import_receipts_source_fingerprint` ON `import_receipts` (`source`, `fingerprint`)")
            }
        }
    }
}
