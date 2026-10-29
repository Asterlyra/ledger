package com.atlas.ledger.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "transactions", indices = [Index(value = ["origin", "originId"], unique = true)])
data class LedgerTransaction(
    @PrimaryKey val id: String,
    val kind: String,
    val amountCents: Long,
    val date: String,
    val category: String,
    val account: String,
    val targetAccount: String = "",
    val note: String = "",
    val origin: String? = null,
    val originId: String? = null,
)

@Serializable
@Entity(tableName = "subscriptions")
data class Subscription(
    @PrimaryKey val id: String,
    val name: String,
    val category: String = "数字服务",
    val billingCycle: String = "月付",
    val autoRenew: Boolean = false,
    val note: String = "",
)

@Serializable
@Entity(
    tableName = "subscription_periods",
    foreignKeys = [ForeignKey(entity = Subscription::class, parentColumns = ["id"], childColumns = ["subscriptionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("subscriptionId")],
)
data class SubscriptionPeriod(
    @PrimaryKey val id: String,
    val subscriptionId: String,
    val amountCents: Long,
    val startDate: String,
    // Half-open interval: startDate is included, endExclusive is not.
    val endExclusive: String,
)

@Serializable
@Entity(tableName = "durables")
data class Durable(
    @PrimaryKey val id: String,
    val name: String,
    val category: String = "数码",
    val amountCents: Long,
    val purchaseDate: String,
    val startDate: String,
    val expectedDays: Int?,
    val stoppedDate: String? = null,
    val note: String = "",
    @androidx.room.ColumnInfo(defaultValue = "0") val residualCents: Long = 0,
)

@Serializable
@Entity(tableName = "recurring_rules")
data class RecurringRule(
    @PrimaryKey val id: String,
    val name: String,
    val amountCents: Long,
    val category: String = "交通",
    val account: String = "现金",
    /** ISO weekdays, for example "1,2,3,4,5". */
    val weekdays: String,
    val time: String = "20:00",
    val startDate: String,
    val endDate: String? = null,
    val nextDate: String = startDate,
    val enabled: Boolean = true,
)

@Serializable
@Entity(
    tableName = "recurring_occurrences",
    foreignKeys = [ForeignKey(entity = RecurringRule::class, parentColumns = ["id"], childColumns = ["ruleId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("ruleId"), Index(value = ["ruleId", "date"], unique = true)],
)
data class RecurringOccurrence(
    @PrimaryKey val id: String,
    val ruleId: String,
    val date: String,
    val status: String = "BOOKED", // BOOKED or SKIPPED; skipped rows are tombstones.
    val name: String,
    val amountCents: Long,
    val category: String,
    val account: String,
)

@Serializable
@Entity(tableName = "import_receipts", indices = [Index(value = ["source", "fingerprint"], unique = true)])
data class ImportReceipt(
    @PrimaryKey val id: String,
    val source: String,
    val fingerprint: String,
    val transactionId: String?,
    val importedAt: String,
    val contentHash: String = "",
)

@Serializable
data class LedgerBackup(
    val format: String = "daily-ledger",
    val version: Int = 3,
    val exportedAt: String = "",
    val transactions: List<LedgerTransaction> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val periods: List<SubscriptionPeriod> = emptyList(),
    val durables: List<Durable> = emptyList(),
    val recurringRules: List<RecurringRule> = emptyList(),
    val recurringOccurrences: List<RecurringOccurrence> = emptyList(),
    val importReceipts: List<ImportReceipt> = emptyList(),
)

data class LedgerState(
    val transactions: List<LedgerTransaction> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val periods: List<SubscriptionPeriod> = emptyList(),
    val durables: List<Durable> = emptyList(),
    val recurringRules: List<RecurringRule> = emptyList(),
    val recurringOccurrences: List<RecurringOccurrence> = emptyList(),
    val importReceipts: List<ImportReceipt> = emptyList(),
    val loaded: Boolean = false,
)
