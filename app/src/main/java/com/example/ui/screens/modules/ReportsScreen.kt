package com.example.ui.screens.modules

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.local.LocalBudgetEntity
import com.example.core.local.LocalCachedReportEntity
import com.example.core.local.LocalFamilyEntity
import com.example.core.local.LocalFamilyMemberEntity
import com.example.core.local.LocalFinancialEventEntity
import com.example.core.local.LocalInvestmentEntity
import com.example.core.local.LocalLoanEntity
import com.example.core.local.LocalSavingsEntity
import com.example.core.local.LocalTransactionEntity
import com.example.core.model.MoneyCalculator
import com.example.core.reports.FamilyReportData
import com.example.core.reports.FamilyReportPdfDocumentBuilder
import com.example.core.reports.FinancialReportEngine
import com.example.core.reports.PdfExportHelper
import com.example.core.reports.ReportFilterSpec
import com.example.core.reports.ReportPeriodScope
import com.example.core.reports.ReportType
import com.example.core.sync.CachedReportBuilder
import com.example.ui.theme.ExpenseCoral
import com.example.ui.theme.IncomeGreen
import com.example.ui.theme.InfoTeal
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportsScreen(
    activeFamily: LocalFamilyEntity?,
    cachedReports: List<LocalCachedReportEntity>,
    transactions: List<LocalTransactionEntity>,
    loans: List<LocalLoanEntity>,
    savings: List<LocalSavingsEntity>,
    investments: List<LocalInvestmentEntity>,
    budgets: List<LocalBudgetEntity>,
    syncStatusLabel: String,
    onRefreshReport: (String) -> Unit,
    onNotifyMessage: (String) -> Unit,
    onNavigateToFamilyManagement: () -> Unit,
    modifier: Modifier = Modifier,
    members: List<LocalFamilyMemberEntity> = emptyList(),
    financialEvents: List<LocalFinancialEventEntity> = emptyList()
) {
    if (activeFamily == null) {
        ModuleNoFamilyState(
            moduleTitle = "Financial Reports & PDF Export",
            moduleSubtitle = "Create or join a family workspace to generate financial statements and export on-device PDFs.",
            onNavigateToFamilyManagement = onNavigateToFamilyManagement,
            modifier = modifier
        )
        return
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val zoneId = remember { ZoneId.systemDefault() }

    var selectedReportType by remember(activeFamily.familyId) {
        mutableStateOf(ReportType.MONTHLY)
    }
    var selectedPeriodScope by remember(activeFamily.familyId) {
        mutableStateOf(ReportPeriodScope.THIS_MONTH)
    }
    var anchorMillis by remember(activeFamily.familyId) {
        mutableLongStateOf(System.currentTimeMillis())
    }
    var customStartMillis by remember(activeFamily.familyId) {
        val defaultStart = Instant.ofEpochMilli(System.currentTimeMillis())
            .atZone(zoneId)
            .toLocalDate()
            .withDayOfMonth(1)
            .atTime(12, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        mutableStateOf<Long?>(defaultStart)
    }
    var customEndMillis by remember(activeFamily.familyId) {
        mutableStateOf<Long?>(System.currentTimeMillis())
    }
    var selectedCategories by remember(activeFamily.familyId) {
        mutableStateOf<Set<String>>(emptySet())
    }
    var showPdfPreview by remember(activeFamily.familyId) { mutableStateOf(false) }
    var isExportingPdf by remember(activeFamily.familyId) { mutableStateOf(false) }

    val filterSpec = remember(
        selectedReportType,
        selectedPeriodScope,
        anchorMillis,
        customStartMillis,
        customEndMillis,
        selectedCategories
    ) {
        ReportFilterSpec(
            reportType = selectedReportType,
            periodScope = selectedPeriodScope,
            anchorMillis = anchorMillis,
            customStartMillis = customStartMillis,
            customEndMillis = customEndMillis,
            selectedCategories = selectedCategories
        )
    }

    val report = remember(
        activeFamily,
        members,
        transactions,
        loans,
        savings,
        investments,
        budgets,
        financialEvents,
        filterSpec
    ) {
        FinancialReportEngine.buildReport(
            family = activeFamily,
            members = members,
            transactions = transactions,
            loans = loans,
            savings = savings,
            investments = investments,
            budgets = budgets,
            financialEvents = financialEvents,
            filterSpec = filterSpec,
            zoneId = zoneId
        )
    }

    val plannedPages = remember(report) { FamilyReportPdfDocumentBuilder.planPages(report) }
    val cachePeriodKey = remember(report.periodScope, anchorMillis) {
        if (report.periodScope == ReportPeriodScope.THIS_MONTH) {
            BudgetSummaryCalculator.periodKeyForMillis(anchorMillis)
        } else {
            CachedReportBuilder.PERIOD_ALL
        }
    }
    val cachedEntity = remember(cachedReports, cachePeriodKey) {
        cachedReports.firstOrNull { it.periodKey == cachePeriodKey }
    }

    val createPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            isExportingPdf = true
            PdfExportHelper.writePdfToUri(context, uri, report)
                .onSuccess { res ->
                    onNotifyMessage("Saved ${res.suggestedFileName} (${res.pageCount} page(s)).")
                }
                .onFailure { err ->
                    onNotifyMessage("PDF export failed: ${err.message ?: "Unable to write file."}")
                }
            isExportingPdf = false
        }
    }

    val currency = report.currency
    val symbol = MoneyCalculator.currencySymbol(currency)

    Box(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 860.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header & Export Controls Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = report.reportType.displayName,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${report.familyName} • ${report.periodLabel} ($currency $symbol)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.PictureAsPdf,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "${plannedPages.size} page(s)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }

                    Text(
                        text = buildString {
                            append("Period: ${report.dateRangeText} • Generated ${report.generationDateText} • $syncStatusLabel")
                            cachedEntity?.let { append(" • AES-256-GCM cached") }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // PDF Preview / Download / Print Actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showPdfPreview = true },
                            enabled = report.filterError == null,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("preview_pdf_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Visibility,
                                contentDescription = stringResource(R.string.cd_preview_pdf),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Preview", maxLines = 1)
                        }
                        Button(
                            onClick = {
                                createPdfLauncher.launch(FamilyReportPdfDocumentBuilder.suggestedFileName(report))
                            },
                            enabled = report.filterError == null && !isExportingPdf,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("download_pdf_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Download,
                                contentDescription = stringResource(R.string.cd_download_pdf),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (isExportingPdf) "Saving..." else "Save PDF", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = {
                                PdfExportHelper.printReportPdf(context, report) { err ->
                                    onNotifyMessage(err)
                                }
                            },
                            enabled = report.filterError == null,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("print_pdf_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Print,
                                contentDescription = stringResource(R.string.cd_print_pdf),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Print", maxLines = 1)
                        }
                    }

                    // Offline Snapshot Refresh, Share PDF & Plaintext Copy Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onRefreshReport(cachePeriodKey) },
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("refresh_cached_report_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = stringResource(R.string.cd_refresh_report),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Refresh", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    isExportingPdf = true
                                    PdfExportHelper.shareReportPdf(context, report)
                                        .onFailure { err ->
                                            onNotifyMessage("Share PDF failed: ${err.message ?: "Unable to share file."}")
                                        }
                                    isExportingPdf = false
                                }
                            },
                            enabled = report.filterError == null && !isExportingPdf,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("share_pdf_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Share,
                                contentDescription = stringResource(R.string.cd_share_pdf),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Share PDF", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = {
                                val summaryText = buildPlaintextReportSummary(report)
                                val clipboard = context.getSystemService(ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(ClipData.newPlainText("Family Finance Statement", summaryText))
                                onNotifyMessage("Copied ${report.reportType.shortLabel} statement summary.")
                            },
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("copy_report_summary_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = stringResource(R.string.cd_copy_report),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Copy Text", maxLines = 1)
                        }
                    }
                }
            }

            // Report Type, Date Range & Category Selection Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Report Type",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    LazyRow(
                        modifier = Modifier.testTag("report_type_row"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(ReportType.entries) { type ->
                            FilterChip(
                                selected = selectedReportType == type,
                                onClick = {
                                    selectedReportType = type
                                    if (type.isTimePreset) {
                                        selectedPeriodScope = FinancialReportEngine.resolvePeriodScopeForReportType(
                                            type,
                                            selectedPeriodScope
                                        )
                                    }
                                },
                                label = { Text(type.shortLabel) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("report_type_${type.name.lowercase(Locale.US)}")
                            )
                        }
                    }
                    Text(
                        text = selectedReportType.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Text(
                        text = "Date Range",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (!selectedReportType.isTimePreset) {
                        LazyRow(
                            modifier = Modifier.testTag("report_scope_row"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(ReportPeriodScope.entries) { scope ->
                                FilterChip(
                                    selected = selectedPeriodScope == scope,
                                    onClick = { selectedPeriodScope = scope },
                                    label = { Text(scope.label) },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("report_scope_${scope.name.lowercase(Locale.US)}")
                                )
                            }
                        }
                    }

                    when (report.periodScope) {
                        ReportPeriodScope.CUSTOM -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ModuleDatePickerRow(
                                    label = "Start Date",
                                    selectedMillis = customStartMillis,
                                    allowClear = false,
                                    testTag = "report_custom_start_picker",
                                    onDateSelected = { customStartMillis = it }
                                )
                                ModuleDatePickerRow(
                                    label = "End Date",
                                    selectedMillis = customEndMillis,
                                    allowClear = false,
                                    testTag = "report_custom_end_picker",
                                    onDateSelected = { customEndMillis = it }
                                )
                            }
                        }
                        ReportPeriodScope.ALL_TIME -> {
                            Text(
                                text = "Showing all recorded history across synced & offline queued entries.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        else -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        anchorMillis = FinancialReportEngine.stepAnchorMillis(
                                            report.periodScope,
                                            anchorMillis,
                                            -1,
                                            zoneId
                                        )
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("report_step_prev")
                                ) {
                                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "Previous period")
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = report.periodLabel,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = report.dateRangeText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(
                                        onClick = { anchorMillis = System.currentTimeMillis() },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Text("Current")
                                    }
                                    IconButton(
                                        onClick = {
                                            anchorMillis = FinancialReportEngine.stepAnchorMillis(
                                                report.periodScope,
                                                anchorMillis,
                                                1,
                                                zoneId
                                            )
                                        },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("report_step_next")
                                    ) {
                                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Next period")
                                    }
                                }
                            }
                        }
                    }

                    report.filterError?.let { err ->
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (selectedCategories.isEmpty()) {
                                "Financial Categories (All)"
                            } else {
                                "Financial Categories (${selectedCategories.size} selected)"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (selectedCategories.isNotEmpty()) {
                            TextButton(
                                onClick = { selectedCategories = emptySet() },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Text("Reset")
                            }
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = selectedCategories.isEmpty(),
                            onClick = { selectedCategories = emptySet() },
                            label = { Text("All Categories") },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("report_cat_all")
                        )
                        report.allAvailableCategories.forEach { cat ->
                            val isSelected = cat in selectedCategories
                            val tag = "report_cat_${cat.lowercase(Locale.US).replace(' ', '_')}"
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selectedCategories = if (isSelected) {
                                        selectedCategories - cat
                                    } else {
                                        selectedCategories + cat
                                    }
                                },
                                label = { Text(cat) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag(tag)
                            )
                        }
                    }
                }
            }

            ModuleFinancialRuleBanner(
                text = "Totals match Dashboard KPIs verbatim: loan principal & savings transfers stay out of Income/Expense totals, and investment profit & loss are computed separately."
            )

            // Dashboard-matched Cash Flow & Net Worth KPI Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ModuleKpiTile(
                    label = "Total Income",
                    value = MoneyCalculator.formatMinorUnits(report.totalIncomeMinor, currency),
                    supporting = "${report.summary.incomeCount} entry(s)",
                    accentColor = IncomeGreen,
                    modifier = Modifier.weight(1f)
                )
                ModuleKpiTile(
                    label = "Total Expense",
                    value = MoneyCalculator.formatMinorUnits(report.totalExpenseMinor, currency),
                    supporting = "${report.summary.expenseCount} entry(s)",
                    accentColor = ExpenseCoral,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ModuleKpiTile(
                    label = "Net Cash Flow",
                    value = MoneyCalculator.formatMinorUnits(
                        report.netCashFlowMinor,
                        currency,
                        if (report.netCashFlowMinor > 0L) "+" else ""
                    ),
                    supporting = "Savings rate ${report.summary.savingsRatePercent}%",
                    accentColor = if (report.netCashFlowMinor >= 0L) IncomeGreen else ExpenseCoral,
                    modifier = Modifier.weight(1f)
                )
                ModuleKpiTile(
                    label = "Household Net Worth",
                    value = MoneyCalculator.formatMinorUnits(report.summary.netWorthMinor, currency),
                    supporting = "Cash ${MoneyCalculator.formatMinorUnits(report.summary.totalFamilyBalanceMinor, currency)}",
                    accentColor = InfoTeal,
                    modifier = Modifier.weight(1f)
                )
            }

            ReportModuleSummariesCard(report = report)
            ReportVisualChartsCard(report = report)
            ReportPaginatedLedgerCard(report = report)

            // Offline Security Badge Footer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "PDF statements are rendered offline on-device using your encrypted local SQLite & synchronized family ledger.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showPdfPreview) {
        ReportsPdfPreviewDialog(
            report = report,
            onDismiss = { showPdfPreview = false },
            onDownloadPdf = {
                showPdfPreview = false
                createPdfLauncher.launch(FamilyReportPdfDocumentBuilder.suggestedFileName(report))
            },
            onPrintPdf = {
                showPdfPreview = false
                PdfExportHelper.printReportPdf(context, report) { err ->
                    onNotifyMessage(err)
                }
            },
            onSharePdf = {
                showPdfPreview = false
                coroutineScope.launch {
                    isExportingPdf = true
                    PdfExportHelper.shareReportPdf(context, report)
                        .onFailure { err ->
                            onNotifyMessage("Share PDF failed: ${err.message ?: "Unable to share file."}")
                        }
                    isExportingPdf = false
                }
            }
        )
    }
}

private fun buildPlaintextReportSummary(report: FamilyReportData): String {
    val cur = report.currency
    return buildString {
        appendLine("${report.familyName} — ${report.reportType.displayName}")
        appendLine("Period: ${report.periodLabel} (${report.dateRangeText})")
        appendLine("Generated: ${report.generationDateText}")
        appendLine("Income: ${MoneyCalculator.formatMinorUnits(report.totalIncomeMinor, cur)}")
        appendLine("Expense: ${MoneyCalculator.formatMinorUnits(report.totalExpenseMinor, cur)}")
        appendLine("Net Cash Flow: ${MoneyCalculator.formatMinorUnits(report.netCashFlowMinor, cur)} (${report.summary.savingsRatePercent}%)")
        appendLine("Net Worth: ${MoneyCalculator.formatMinorUnits(report.summary.netWorthMinor, cur)}")
        appendLine("Savings: ${MoneyCalculator.formatMinorUnits(report.savingsSummary.totalSavedMinor, cur)} / ${MoneyCalculator.formatMinorUnits(report.savingsSummary.totalTargetMinor, cur)}")
        appendLine("Loans: Borrowed ${MoneyCalculator.formatMinorUnits(report.loansSummary.remainingBorrowedMinor, cur)} | Lent ${MoneyCalculator.formatMinorUnits(report.loansSummary.remainingLentMinor, cur)}")
        appendLine("Investments: Principal ${MoneyCalculator.formatMinorUnits(report.investmentsSummary.totalPrincipalMinor, cur)} | Value ${MoneyCalculator.formatMinorUnits(report.investmentsSummary.totalCurrentValueMinor, cur)} | Profit +${MoneyCalculator.formatMinorUnits(report.investmentsSummary.totalProfitMinor, cur)} | Loss -${MoneyCalculator.formatMinorUnits(report.investmentsSummary.totalLossMinor, cur)}")
        appendLine("Transactions included: ${report.transactions.size}")
    }
}
