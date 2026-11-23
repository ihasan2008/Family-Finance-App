package com.example.ui.screens.modules

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.core.model.MoneyCalculator
import com.example.core.model.TransactionType
import com.example.core.reports.FamilyReportData
import com.example.core.reports.FamilyReportPdfDocumentBuilder
import com.example.core.reports.PdfBlock
import com.example.core.reports.PdfExportHelper
import com.example.core.reports.PdfPlannedPage

@Composable
fun ReportsPdfPreviewDialog(
    report: FamilyReportData,
    onDismiss: () -> Unit,
    onDownloadPdf: () -> Unit,
    onPrintPdf: () -> Unit,
    onSharePdf: () -> Unit = {}
) {
    val context = LocalContext.current
    val plannedPages = remember(report) { FamilyReportPdfDocumentBuilder.planPages(report) }
    var bitmaps by remember(report) { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var isRendering by remember(report) { mutableStateOf(true) }
    var pageIndex by remember(report) { mutableIntStateOf(0) }

    LaunchedEffect(report) {
        isRendering = true
        bitmaps = PdfExportHelper.renderPreviewBitmaps(context, report)
        isRendering = false
    }

    val totalPages = maxOf(plannedPages.size, bitmaps.size).coerceAtLeast(1)
    val safeIndex = pageIndex.coerceIn(0, totalPages - 1)
    val fileName = remember(report) { FamilyReportPdfDocumentBuilder.suggestedFileName(report) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
                .testTag("pdf_preview_dialog"),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
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
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text(
                                text = "PDF Statement Preview",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "$fileName • Page ${safeIndex + 1} of $totalPages",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("close_pdf_preview_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.cd_close_dialog)
                        )
                    }
                }

                // A4 Page Viewport
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    val bitmap = bitmaps.getOrNull(safeIndex)
                    when {
                        isRendering -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                        bitmap != null -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = "PDF preview page ${safeIndex + 1} of $totalPages",
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(595f / 842f)
                                        .background(Color.White)
                                )
                            }
                        }
                        else -> {
                            val planned = plannedPages.getOrNull(safeIndex)
                            StructuredA4SheetFallback(
                                report = report,
                                planned = planned,
                                totalPages = totalPages,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp)
                            )
                        }
                    }
                }

                // Page Stepper Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { if (safeIndex > 0) pageIndex = safeIndex - 1 },
                        enabled = safeIndex > 0,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("preview_prev_page")
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Prev")
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = "Page ${safeIndex + 1} / $totalPages",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("preview_page_indicator")
                    )
                    Spacer(Modifier.width(14.dp))
                    OutlinedButton(
                        onClick = { if (safeIndex < totalPages - 1) pageIndex = safeIndex + 1 },
                        enabled = safeIndex < totalPages - 1,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("preview_next_page")
                    ) {
                        Text("Next")
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }

                // Export Actions Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onPrintPdf,
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("preview_print_pdf_button")
                    ) {
                        Icon(Icons.Outlined.Print, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Print", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onSharePdf,
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("preview_share_pdf_button")
                    ) {
                        Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Share", maxLines = 1)
                    }
                    Button(
                        onClick = onDownloadPdf,
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("preview_download_pdf_button")
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save PDF", maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun StructuredA4SheetFallback(
    report: FamilyReportData,
    planned: PdfPlannedPage?,
    totalPages: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color.White,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (planned == null || planned.pageNumber == 1) {
                Surface(color = Color(0xFF0B6E4F), shape = RoundedCornerShape(6.dp)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(report.familyName, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(report.reportType.displayName, color = Color(0xFFDAF5E8), style = MaterialTheme.typography.bodySmall)
                        Text("Period: ${report.periodLabel} (${report.dateRangeText})", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Text("Generated: ${report.generationDateText} • ${report.currency}", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            } else {
                Text(
                    text = "${report.familyName} — ${report.reportType.displayName} (Page ${planned.pageNumber})",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF40534B)
                )
            }

            planned?.blocks?.forEach { block ->
                when (block) {
                    PdfBlock.SummaryAndModules -> {
                        val c = report.currency
                        Text("Income: ${MoneyCalculator.formatMinorUnits(report.totalIncomeMinor, c)} | Expense: ${MoneyCalculator.formatMinorUnits(report.totalExpenseMinor, c)}", color = Color.Black, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        Text("Net Cash Flow: ${MoneyCalculator.formatMinorUnits(report.netCashFlowMinor, c)} | Net Worth: ${MoneyCalculator.formatMinorUnits(report.summary.netWorthMinor, c)}", color = Color.Black, style = MaterialTheme.typography.bodySmall)
                        Text("Savings: ${MoneyCalculator.formatMinorUnits(report.savingsSummary.totalSavedMinor, c)} / ${MoneyCalculator.formatMinorUnits(report.savingsSummary.totalTargetMinor, c)}", color = Color(0xFF0B6E4F), style = MaterialTheme.typography.labelSmall)
                        Text("Loans: Borrowed ${MoneyCalculator.formatMinorUnits(report.loansSummary.remainingBorrowedMinor, c)} | Lent ${MoneyCalculator.formatMinorUnits(report.loansSummary.remainingLentMinor, c)}", color = Color.Black, style = MaterialTheme.typography.labelSmall)
                        Text("Investments: Principal ${MoneyCalculator.formatMinorUnits(report.investmentsSummary.totalPrincipalMinor, c)} | Profit +${MoneyCalculator.formatMinorUnits(report.investmentsSummary.totalProfitMinor, c)} | Loss -${MoneyCalculator.formatMinorUnits(report.investmentsSummary.totalLossMinor, c)}", color = Color.Black, style = MaterialTheme.typography.labelSmall)
                    }
                    PdfBlock.CashFlowChart -> {
                        Text("Vector Chart: Monthly Income vs. Expense", color = Color(0xFF0B6E4F), style = MaterialTheme.typography.labelMedium)
                    }
                    PdfBlock.CategoryChart -> {
                        Text("Vector Chart: Category Distribution Breakdown", color = Color(0xFF0B6E4F), style = MaterialTheme.typography.labelMedium)
                    }
                    PdfBlock.MemberExpenseChart -> {
                        Text("Vector Chart: Member-wise Expense Breakdown", color = Color(0xFF0B6E4F), style = MaterialTheme.typography.labelMedium)
                        report.memberExpenses.take(4).forEach { bar ->
                            Text(
                                text = "${bar.memberName}: ${MoneyCalculator.formatMinorUnits(bar.expenseMinor, report.currency)} (${bar.sharePercent}%)",
                                color = Color.Black,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    is PdfBlock.SectionTitle -> {
                        HorizontalDivider()
                        Text(block.title, color = Color(0xFF0B6E4F), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                    is PdfBlock.TransactionTableChunk -> {
                        report.transactions.subList(block.startIndex, block.endExclusive).forEach { row ->
                            val sign = if (row.transactionType.equals(TransactionType.INCOME.name, ignoreCase = true)) "+" else "-"
                            Text(
                                text = "${row.dateText} • ${row.category} (${row.memberName}): ${MoneyCalculator.formatMinorUnits(row.amountMinor, row.currency, sign)}",
                                color = Color.Black,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    is PdfBlock.LoanTableChunk -> {
                        report.loans.subList(block.startIndex, block.endExclusive).forEach { loan ->
                            Text("${loan.entity.counterparty}: ${MoneyCalculator.formatMinorUnits(loan.remainingMinor, report.currency)}", color = Color.Black, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    is PdfBlock.SavingsTableChunk -> {
                        report.savingsGoals.subList(block.startIndex, block.endExclusive).forEach { goal ->
                            Text("${goal.entity.goalName}: ${goal.progressPercent}% (${MoneyCalculator.formatMinorUnits(goal.currentMinor, report.currency)})", color = Color.Black, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    is PdfBlock.InvestmentTableChunk -> {
                        report.investments.subList(block.startIndex, block.endExclusive).forEach { inv ->
                            Text("${inv.entity.assetName}: ${MoneyCalculator.formatMinorUnits(inv.currentValueMinor, report.currency)} (${inv.returnPercentText})", color = Color.Black, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    is PdfBlock.BudgetTableChunk -> {
                        val rows = listOfNotNull(report.budgetPeriodSummary.overallBudget) +
                            report.budgetPeriodSummary.categoryBudgets
                        rows.subList(block.startIndex, block.endExclusive).forEach { bud ->
                            Text(
                                text = "${bud.entity.category} (${bud.entity.periodKey}): ${MoneyCalculator.formatMinorUnits(bud.spentMinor, report.currency)} / ${MoneyCalculator.formatMinorUnits(bud.limitMinor, report.currency)} (${bud.usagePercent}%)",
                                color = Color.Black,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    is PdfBlock.FinancialEventTableChunk -> {
                        val events = FamilyReportPdfDocumentBuilder.filteredModuleEvents(report)
                        events.subList(block.startIndex, block.endExclusive).forEach { evt ->
                            Text(
                                text = "${formatModuleDate(evt.eventDateMillis)} • ${evt.moduleType} • ${evt.eventType}: ${MoneyCalculator.formatMinorUnits(evt.amountMinor, evt.currency)}",
                                color = Color.Black,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    is PdfBlock.EmptyNotice -> {
                        Text(block.message, color = Color(0xFF40534B), style = MaterialTheme.typography.labelSmall)
                    }
                    PdfBlock.HeaderBanner -> Unit
                }
            }
            HorizontalDivider()
            Text(
                text = "Page ${planned?.pageNumber ?: 1} of $totalPages",
                color = Color(0xFF40534B),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
