package com.example.ui.screens.transactions

import com.example.core.local.LocalTransactionEntity
import com.example.core.model.DateFilterPreset
import com.example.core.model.MoneyCalculator
import com.example.core.model.RecurrenceFrequency
import com.example.core.model.RecurrenceScheduleHelper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class TransactionFilterState(
    val searchQuery: String = "",
    val selectedCategory: String? = null,
    val datePreset: DateFilterPreset = DateFilterPreset.ALL,
    val customStartMillis: Long? = null,
    val customEndMillis: Long? = null,
    val selectedMemberId: String? = null
) {
    val hasActiveFilters: Boolean
        get() = searchQuery.isNotBlank() ||
            !selectedCategory.isNullOrBlank() ||
            datePreset != DateFilterPreset.ALL ||
            !selectedMemberId.isNullOrBlank()
}

data class CategoryBreakdownItem(
    val category: String,
    val totalMinor: Long,
    val share: Float,
    val count: Int
)

data class TransactionDateGroup(
    val dateLabel: String,
    val dayTotalMinor: Long,
    val items: List<LocalTransactionEntity>
)

data class TransactionFilterResult(
    val filteredTransactions: List<LocalTransactionEntity>,
    val filteredTotalMinor: Long,
    val thisMonthMinor: Long,
    val activeRecurringCount: Int,
    val recurringTemplates: List<LocalTransactionEntity>,
    val categoryBreakdown: List<CategoryBreakdownItem>,
    val dateGroups: List<TransactionDateGroup>
)

object TransactionFilterEngine {

    private val shortDateFormatter = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.US)

    fun filterAndSummarize(
        transactions: List<LocalTransactionEntity>,
        filterState: TransactionFilterState,
        memberNameLookup: Map<String, String> = emptyMap(),
        currencyCode: String = "USD",
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): TransactionFilterResult {
        val (rangeStart, rangeEnd) = RecurrenceScheduleHelper.dateRangeForPreset(
            preset = filterState.datePreset,
            nowMillis = nowMillis,
            customStartMillis = filterState.customStartMillis,
            customEndMillis = filterState.customEndMillis,
            zoneId = zoneId
        )

        val query = filterState.searchQuery.trim().lowercase(Locale.US)

        val filtered = transactions.filter { tx ->
            val matchesCategory = filterState.selectedCategory.isNullOrBlank() ||
                tx.category.equals(filterState.selectedCategory, ignoreCase = true)

            val matchesMember = filterState.selectedMemberId.isNullOrBlank() ||
                tx.effectiveMemberId() == filterState.selectedMemberId

            val matchesDate = (rangeStart == null || tx.occurredAtMillis >= rangeStart) &&
                (rangeEnd == null || tx.occurredAtMillis <= rangeEnd)

            val matchesQuery = if (query.isEmpty()) {
                true
            } else {
                val memberName = memberNameLookup[tx.effectiveMemberId()].orEmpty()
                val amountStr = MoneyCalculator.minorUnitsToInputString(
                    tx.effectiveAmountMinor(),
                    tx.currency.ifBlank { currencyCode }
                )
                tx.category.lowercase(Locale.US).contains(query) ||
                    tx.description.lowercase(Locale.US).contains(query) ||
                    tx.recordId.lowercase(Locale.US).contains(query) ||
                    tx.paymentMethod.orEmpty().lowercase(Locale.US).contains(query) ||
                    memberName.lowercase(Locale.US).contains(query) ||
                    tx.effectiveMemberId().lowercase(Locale.US).contains(query) ||
                    amountStr.contains(query)
            }

            matchesCategory && matchesMember && matchesDate && matchesQuery
        }.sortedByDescending { it.occurredAtMillis }

        val filteredTotalMinor = MoneyCalculator.sumMinorUnits(
            filtered.map { it.effectiveAmountMinor() }
        )

        val (monthStart, monthEnd) = RecurrenceScheduleHelper.dateRangeForPreset(
            preset = DateFilterPreset.THIS_MONTH,
            nowMillis = nowMillis,
            zoneId = zoneId
        )
        val thisMonthMinor = MoneyCalculator.sumMinorUnits(
            transactions
                .filter { tx ->
                    (monthStart == null || tx.occurredAtMillis >= monthStart) &&
                        (monthEnd == null || tx.occurredAtMillis <= monthEnd)
                }
                .map { it.effectiveAmountMinor() }
        )

        val recurringTemplates = transactions
            .filter {
                it.recurringParentId.isNullOrBlank() &&
                    RecurrenceFrequency.fromRaw(it.recurrenceFrequency) != RecurrenceFrequency.NONE
            }
            .sortedByDescending { it.isRecurringTemplate }

        val activeRecurringCount = recurringTemplates.count { it.isRecurringTemplate }

        val breakdown = filtered
            .groupBy { it.category.ifBlank { "General" } }
            .map { (cat, rows) ->
                val catTotal = MoneyCalculator.sumMinorUnits(rows.map { it.effectiveAmountMinor() })
                CategoryBreakdownItem(
                    category = cat,
                    totalMinor = catTotal,
                    share = MoneyCalculator.percentageShare(catTotal, filteredTotalMinor),
                    count = rows.size
                )
            }
            .sortedByDescending { it.totalMinor }

        val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val yesterday = today.minusDays(1)
        val grouped = filtered
            .groupBy { tx ->
                Instant.ofEpochMilli(tx.occurredAtMillis).atZone(zoneId).toLocalDate()
            }
            .entries
            .sortedByDescending { it.key }
            .map { (date, rows) ->
                val label = when (date) {
                    today -> "Today"
                    yesterday -> "Yesterday"
                    else -> date.format(shortDateFormatter)
                }
                TransactionDateGroup(
                    dateLabel = label,
                    dayTotalMinor = MoneyCalculator.sumMinorUnits(rows.map { it.effectiveAmountMinor() }),
                    items = rows
                )
            }

        return TransactionFilterResult(
            filteredTransactions = filtered,
            filteredTotalMinor = filteredTotalMinor,
            thisMonthMinor = thisMonthMinor,
            activeRecurringCount = activeRecurringCount,
            recurringTemplates = recurringTemplates,
            categoryBreakdown = breakdown,
            dateGroups = grouped
        )
    }

    fun formatDateTime(millis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val formatter = DateTimeFormatter.ofPattern("MMM dd, yyyy • hh:mm a", Locale.US)
        return Instant.ofEpochMilli(millis).atZone(zoneId).format(formatter)
    }

    fun formatDateOnly(millis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val date: LocalDate = Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate()
        return date.format(shortDateFormatter)
    }
}
