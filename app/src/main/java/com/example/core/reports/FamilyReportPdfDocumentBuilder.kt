package com.example.core.reports

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.example.core.analytics.ExpenseCategorySlice
import com.example.core.local.LocalFinancialEventEntity
import com.example.core.model.LoanDirection
import com.example.core.model.MoneyCalculator
import com.example.core.model.TransactionType
import com.example.ui.screens.modules.formatModuleDate
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.max

/**
 * Planned logical page units used before PDF drawing so large reports paginate cleanly
 * with accurate "Page X of Y" footers and repeated table headers.
 */
sealed interface PdfBlock {
    data object HeaderBanner : PdfBlock
    data object SummaryAndModules : PdfBlock
    data object CashFlowChart : PdfBlock
    data object CategoryChart : PdfBlock
    data object MemberExpenseChart : PdfBlock
    data class SectionTitle(val title: String) : PdfBlock
    data class LoanTableChunk(val startIndex: Int, val endExclusive: Int, val isContinuation: Boolean) : PdfBlock
    data class SavingsTableChunk(val startIndex: Int, val endExclusive: Int, val isContinuation: Boolean) : PdfBlock
    data class InvestmentTableChunk(val startIndex: Int, val endExclusive: Int, val isContinuation: Boolean) : PdfBlock
    data class BudgetTableChunk(val startIndex: Int, val endExclusive: Int, val isContinuation: Boolean) : PdfBlock
    data class FinancialEventTableChunk(val startIndex: Int, val endExclusive: Int, val isContinuation: Boolean) : PdfBlock
    data class TransactionTableChunk(val startIndex: Int, val endExclusive: Int, val isContinuation: Boolean) : PdfBlock
    data class EmptyNotice(val message: String) : PdfBlock
}

data class PdfPlannedPage(
    val pageNumber: Int,
    val blocks: List<PdfBlock>
)

data class PdfGenerationResult(
    val bytes: ByteArray,
    val pageCount: Int,
    val transactionRowCount: Int,
    val suggestedFileName: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PdfGenerationResult) return false
        return bytes.contentEquals(other.bytes) &&
            pageCount == other.pageCount &&
            transactionRowCount == other.transactionRowCount &&
            suggestedFileName == other.suggestedFileName
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + pageCount
        result = 31 * result + transactionRowCount
        result = 31 * result + suggestedFileName.hashCode()
        return result
    }
}

/**
 * Generates multi-page A4 vector PDF statements completely on-device via [android.graphics.pdf.PdfDocument],
 * rendering family metadata, financial KPIs, module summaries, vector Canvas charts, and paginated ledgers.
 */
object FamilyReportPdfDocumentBuilder {

    const val PAGE_WIDTH = 595
    const val PAGE_HEIGHT = 842
    private const val MARGIN_H = 36f
    private const val MARGIN_TOP = 34f
    private const val FOOTER_Y = 816f
    private const val CONTENT_WIDTH = PAGE_WIDTH - (MARGIN_H * 2)
    private const val MAX_CONTENT_Y = 786f

    private const val ROW_HEIGHT = 20f
    private const val TABLE_HEADER_HEIGHT = 22f
    private const val SECTION_TITLE_HEIGHT = 22f

    private val BRAND_EMERALD = Color.rgb(11, 110, 79)
    private val BRAND_MINT_BG = Color.rgb(235, 245, 239)
    private val TEXT_DARK = Color.rgb(16, 29, 24)
    private val TEXT_MUTED = Color.rgb(64, 83, 75)
    private val BORDER_GRAY = Color.rgb(189, 208, 198)
    private val ROW_STRIPE = Color.rgb(246, 251, 248)
    private val INCOME_GREEN = Color.rgb(14, 138, 93)
    private val EXPENSE_CORAL = Color.rgb(209, 75, 75)
    private val INFO_TEAL = Color.rgb(12, 126, 140)
    private val WEALTH_GOLD = Color.rgb(184, 125, 30)

    fun suggestedFileName(report: FamilyReportData): String {
        val cleanFam = report.familyName
            .replace(Regex("[^A-Za-z0-9]+"), "_")
            .trim('_')
            .take(28)
            .ifBlank { "Family" }
        val cleanType = report.reportType.shortLabel
            .replace(Regex("[^A-Za-z0-9]+"), "_")
            .trim('_')
        val dateStamp = java.time.Instant.ofEpochMilli(report.generatedAtMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .toString()
        return "FamilyFinance_${cleanFam}_${cleanType}_$dateStamp.pdf"
    }

    /**
     * Deterministically plans page breaks before rendering so large reports paginate cleanly
     * with exact page counts.
     */
    fun planPages(report: FamilyReportData): List<PdfPlannedPage> {
        val pages = mutableListOf<List<PdfBlock>>()
        var currentBlocks = mutableListOf<PdfBlock>()
        var cursorY = MARGIN_TOP

        fun startNewPage() {
            if (currentBlocks.isNotEmpty()) {
                pages += currentBlocks
                currentBlocks = mutableListOf()
            }
            cursorY = MARGIN_TOP + 26f // Space for continuation header
        }

        fun ensureSpace(needed: Float) {
            if (cursorY + needed > MAX_CONTENT_Y && currentBlocks.isNotEmpty()) {
                startNewPage()
            }
        }

        // Page 1: Branded Header + KPI & Module Summaries
        currentBlocks += PdfBlock.HeaderBanner
        cursorY += 104f

        currentBlocks += PdfBlock.SummaryAndModules
        cursorY += 206f

        ensureSpace(176f)
        currentBlocks += PdfBlock.CashFlowChart
        cursorY += 176f

        if (report.expenseCategories.isNotEmpty() || report.incomeCategories.isNotEmpty()) {
            ensureSpace(172f)
            currentBlocks += PdfBlock.CategoryChart
            cursorY += 172f
        }

        if (report.memberExpenses.isNotEmpty()) {
            ensureSpace(172f)
            currentBlocks += PdfBlock.MemberExpenseChart
            cursorY += 172f
        }

        fun <T> paginateTable(
            title: String,
            items: List<T>,
            emptyMessage: String?,
            chunkFactory: (Int, Int, Boolean) -> PdfBlock
        ) {
            if (items.isEmpty()) {
                if (emptyMessage != null) {
                    ensureSpace(SECTION_TITLE_HEIGHT + 28f)
                    currentBlocks += PdfBlock.SectionTitle(title)
                    currentBlocks += PdfBlock.EmptyNotice(emptyMessage)
                    cursorY += SECTION_TITLE_HEIGHT + 28f
                }
                return
            }

            // Ensure header + at least 2 rows fit before starting table
            ensureSpace(SECTION_TITLE_HEIGHT + TABLE_HEADER_HEIGHT + ROW_HEIGHT * 2f)
            currentBlocks += PdfBlock.SectionTitle(title)
            cursorY += SECTION_TITLE_HEIGHT

            var index = 0
            var continuation = false
            while (index < items.size) {
                val available = MAX_CONTENT_Y - cursorY - TABLE_HEADER_HEIGHT - 8f
                val maxRowsHere = (available / ROW_HEIGHT).toInt()
                if (maxRowsHere <= 0) {
                    startNewPage()
                    continue
                }
                val endIndex = minOf(items.size, index + maxRowsHere)
                currentBlocks += chunkFactory(index, endIndex, continuation)
                cursorY += TABLE_HEADER_HEIGHT + (endIndex - index) * ROW_HEIGHT + 10f
                index = endIndex
                continuation = true
                if (index < items.size) {
                    startNewPage()
                }
            }
        }

        if (report.includeLoansSection) {
            paginateTable(
                title = "Loan Ledger & Installments (${report.loans.size})",
                items = report.loans,
                emptyMessage = if (report.reportType == ReportType.LOAN) "No active or historical loans recorded." else null
            ) { s, e, cont -> PdfBlock.LoanTableChunk(s, e, cont) }
        }

        if (report.includeSavingsSection) {
            paginateTable(
                title = "Savings Goals & Progress (${report.savingsGoals.size})",
                items = report.savingsGoals,
                emptyMessage = if (report.reportType == ReportType.SAVINGS) "No savings goals created yet." else null
            ) { s, e, cont -> PdfBlock.SavingsTableChunk(s, e, cont) }
        }

        if (report.includeInvestmentsSection) {
            paginateTable(
                title = "Investment Portfolio (${report.investments.size})",
                items = report.investments,
                emptyMessage = if (report.reportType == ReportType.INVESTMENT) "No investments recorded yet." else null
            ) { s, e, cont -> PdfBlock.InvestmentTableChunk(s, e, cont) }
        }

        if (report.includeBudgetsSection) {
            val budgetRows = listOfNotNull(report.budgetPeriodSummary.overallBudget) +
                report.budgetPeriodSummary.categoryBudgets
            paginateTable(
                title = "Budgets & Limits (${budgetRows.size})",
                items = budgetRows,
                emptyMessage = null
            ) { s, e, cont -> PdfBlock.BudgetTableChunk(s, e, cont) }
        }

        val moduleEvents = filteredModuleEvents(report)
        if ((report.includeLoansSection || report.includeSavingsSection || report.includeInvestmentsSection) &&
            moduleEvents.isNotEmpty()
        ) {
            paginateTable(
                title = "Loan, Savings & Investment Events (${moduleEvents.size})",
                items = moduleEvents,
                emptyMessage = null
            ) { s, e, cont -> PdfBlock.FinancialEventTableChunk(s, e, cont) }
        }

        paginateTable(
            title = "Transaction History (${report.transactions.size})",
            items = report.transactions,
            emptyMessage = "No matching Income/Expense transactions found for the selected filter."
        ) { s, e, cont -> PdfBlock.TransactionTableChunk(s, e, cont) }

        if (currentBlocks.isNotEmpty()) {
            pages += currentBlocks
        }

        return pages.mapIndexed { idx, blocks ->
            PdfPlannedPage(pageNumber = idx + 1, blocks = blocks)
        }
    }

    fun generatePdfBytes(report: FamilyReportData): PdfGenerationResult {
        val baos = ByteArrayOutputStream()
        val pageCount = writePdfToStream(report, baos)
        return PdfGenerationResult(
            bytes = baos.toByteArray(),
            pageCount = pageCount,
            transactionRowCount = report.transactions.size,
            suggestedFileName = suggestedFileName(report)
        )
    }

    fun writePdfToStream(report: FamilyReportData, outputStream: OutputStream): Int {
        val plannedPages = planPages(report)
        val totalPages = plannedPages.size.coerceAtLeast(1)
        val nativeResult = runCatching {
            val document = PdfDocument()
            try {
                val glyphProbePaint = Paint().apply { textSize = 10f }
                val supportsTaka = runCatching { glyphProbePaint.hasGlyph("৳") }.getOrDefault(false)

                plannedPages.forEach { planned ->
                    val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, planned.pageNumber).create()
                    val page = document.startPage(pageInfo)
                    renderPage(
                        canvas = page.canvas,
                        report = report,
                        planned = planned,
                        totalPages = totalPages,
                        supportsTaka = supportsTaka
                    )
                    document.finishPage(page)
                }
                document.writeTo(outputStream)
            } finally {
                runCatching { document.close() }
            }
            totalPages
        }
        return nativeResult.getOrElse {
            PurePdf14FallbackWriter.write(report, plannedPages, outputStream)
        }
    }

    private fun renderPage(
        canvas: Canvas,
        report: FamilyReportData,
        planned: PdfPlannedPage,
        totalPages: Int,
        supportsTaka: Boolean
    ) {
        canvas.drawColor(Color.WHITE)
        val money = { minor: Long, prefix: String ->
            MoneyCalculator.formatForPdf(minor, report.currency, supportsTaka, prefix)
        }
        var y = MARGIN_TOP

        if (planned.pageNumber > 1) {
            drawContinuationHeader(canvas, report)
            y += 26f
        }

        for (block in planned.blocks) {
            when (block) {
                PdfBlock.HeaderBanner -> {
                    drawHeaderBanner(canvas, report, supportsTaka)
                    y += 104f
                }
                PdfBlock.SummaryAndModules -> {
                    drawExecutiveAndModuleSummary(canvas, report, y, money)
                    y += 206f
                }
                PdfBlock.CashFlowChart -> {
                    drawCashFlowChart(canvas, report, y, money)
                    y += 176f
                }
                PdfBlock.CategoryChart -> {
                    drawCategoryChart(canvas, report, y, money)
                    y += 172f
                }
                PdfBlock.MemberExpenseChart -> {
                    drawMemberExpenseChart(canvas, report, y, money)
                    y += 172f
                }
                is PdfBlock.SectionTitle -> {
                    drawSectionTitle(canvas, block.title, y)
                    y += SECTION_TITLE_HEIGHT
                }
                is PdfBlock.EmptyNotice -> {
                    drawEmptyNotice(canvas, block.message, y)
                    y += 28f
                }
                is PdfBlock.LoanTableChunk -> {
                    y = drawLoanTableChunk(canvas, report, block, y, money)
                }
                is PdfBlock.SavingsTableChunk -> {
                    y = drawSavingsTableChunk(canvas, report, block, y, money)
                }
                is PdfBlock.InvestmentTableChunk -> {
                    y = drawInvestmentTableChunk(canvas, report, block, y, money)
                }
                is PdfBlock.BudgetTableChunk -> {
                    y = drawBudgetTableChunk(canvas, report, block, y, money)
                }
                is PdfBlock.FinancialEventTableChunk -> {
                    y = drawFinancialEventTableChunk(canvas, report, block, y, money)
                }
                is PdfBlock.TransactionTableChunk -> {
                    y = drawTransactionTableChunk(canvas, report, block, y, money)
                }
            }
        }

        drawPageFooter(canvas, report, planned.pageNumber, totalPages)
    }

    private fun drawContinuationHeader(canvas: Canvas, report: FamilyReportData) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_MUTED
            textSize = 9f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(
            "${report.familyName} — ${report.reportType.displayName} (${report.periodLabel})",
            MARGIN_H,
            MARGIN_TOP + 10f,
            paint
        )
        val linePaint = Paint().apply { color = BORDER_GRAY; strokeWidth = 1f }
        canvas.drawLine(MARGIN_H, MARGIN_TOP + 16f, PAGE_WIDTH - MARGIN_H, MARGIN_TOP + 16f, linePaint)
    }

    private fun drawHeaderBanner(
        canvas: Canvas,
        report: FamilyReportData,
        supportsTaka: Boolean
    ) {
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BRAND_EMERALD }
        canvas.drawRoundRect(
            RectF(MARGIN_H, MARGIN_TOP, PAGE_WIDTH - MARGIN_H, MARGIN_TOP + 94f),
            8f,
            8f,
            boxPaint
        )

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 17f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(218, 245, 232)
            textSize = 10f
        }
        val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 9.5f
        }

        canvas.drawText(report.familyName.take(44), MARGIN_H + 14f, MARGIN_TOP + 24f, titlePaint)
        canvas.drawText(report.reportType.displayName, MARGIN_H + 14f, MARGIN_TOP + 41f, subtitlePaint)
        canvas.drawText(
            "Report Period: ${report.periodLabel} (${report.dateRangeText})",
            MARGIN_H + 14f,
            MARGIN_TOP + 58f,
            metaPaint
        )
        val curBadge = if (report.currency == "BDT" && supportsTaka) "BDT (৳)" else report.currency
        val catLabel = if (report.selectedCategories.isEmpty()) "All Categories"
        else report.selectedCategories.take(3).joinToString(", ") +
            if (report.selectedCategories.size > 3) " +${report.selectedCategories.size - 3}" else ""
        canvas.drawText(
            "Generated: ${report.generationDateText}  |  Currency: $curBadge  |  Scope: $catLabel",
            MARGIN_H + 14f,
            MARGIN_TOP + 76f,
            metaPaint
        )
    }

    private fun drawExecutiveAndModuleSummary(
        canvas: Canvas,
        report: FamilyReportData,
        topY: Float,
        money: (Long, String) -> String
    ) {
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ROW_STRIPE }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BORDER_GRAY
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_MUTED
            textSize = 8.5f
        }
        val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        // Row 1: 4 primary Cash Flow & Net Worth KPIs
        val cardGap = 8f
        val kpiW = (CONTENT_WIDTH - cardGap * 3f) / 4f
        val kpiH = 52f
        val kpis = listOf(
            Triple("TOTAL INCOME", money(report.totalIncomeMinor, ""), INCOME_GREEN),
            Triple("TOTAL EXPENSE", money(report.totalExpenseMinor, ""), EXPENSE_CORAL),
            Triple(
                "NET CASH FLOW",
                money(report.netCashFlowMinor, if (report.netCashFlowMinor > 0) "+" else ""),
                if (report.netCashFlowMinor >= 0) INCOME_GREEN else EXPENSE_CORAL
            ),
            Triple("HOUSEHOLD NET WORTH", money(report.summary.netWorthMinor, ""), BRAND_EMERALD)
        )
        kpis.forEachIndexed { idx, (lbl, v, col) ->
            val left = MARGIN_H + idx * (kpiW + cardGap)
            val rect = RectF(left, topY, left + kpiW, topY + kpiH)
            canvas.drawRoundRect(rect, 6f, 6f, cardPaint)
            canvas.drawRoundRect(rect, 6f, 6f, borderPaint)
            canvas.drawText(lbl, left + 8f, topY + 17f, labelPaint)
            valPaint.color = col
            canvas.drawText(ellipsize(v, 18), left + 8f, topY + 37f, valPaint)
        }

        // Row 2: Savings Summary, Loan Summary, Investment Summary cards
        val modTop = topY + kpiH + 10f
        val modW = (CONTENT_WIDTH - cardGap * 2f) / 3f
        val modH = 126f
        val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BRAND_EMERALD
            textSize = 9.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_DARK
            textSize = 8.5f
        }

        // Card 1: Savings Summary
        val sRect = RectF(MARGIN_H, modTop, MARGIN_H + modW, modTop + modH)
        canvas.drawRoundRect(sRect, 6f, 6f, Paint().apply { color = BRAND_MINT_BG })
        canvas.drawRoundRect(sRect, 6f, 6f, borderPaint)
        canvas.drawText("SAVINGS SUMMARY", MARGIN_H + 8f, modTop + 16f, headPaint)
        canvas.drawText("Saved: ${money(report.savingsSummary.totalSavedMinor, "")}", MARGIN_H + 8f, modTop + 34f, linePaint)
        canvas.drawText("Target: ${money(report.savingsSummary.totalTargetMinor, "")}", MARGIN_H + 8f, modTop + 50f, linePaint)
        canvas.drawText("Remaining: ${money(report.savingsSummary.remainingToTargetMinor, "")}", MARGIN_H + 8f, modTop + 66f, linePaint)
        canvas.drawText("Goals: ${report.savingsGoals.size} (${report.summary.savingsCompletionPercent}% funded)", MARGIN_H + 8f, modTop + 82f, linePaint)
        canvas.drawText("Cash Balance: ${money(report.summary.totalFamilyBalanceMinor, "")}", MARGIN_H + 8f, modTop + 98f, linePaint)

        // Card 2: Loan Summary
        val lLeft = MARGIN_H + modW + cardGap
        val lRect = RectF(lLeft, modTop, lLeft + modW, modTop + modH)
        canvas.drawRoundRect(lRect, 6f, 6f, cardPaint)
        canvas.drawRoundRect(lRect, 6f, 6f, borderPaint)
        canvas.drawText("LOAN SUMMARY", lLeft + 8f, modTop + 16f, headPaint)
        canvas.drawText("Borrowed Rem.: ${money(report.loansSummary.remainingBorrowedMinor, "")}", lLeft + 8f, modTop + 34f, linePaint)
        canvas.drawText("Lent Recv.: ${money(report.loansSummary.remainingLentMinor, "")}", lLeft + 8f, modTop + 50f, linePaint)
        canvas.drawText("Total Repaid: ${money(report.loansSummary.totalRepaidMinor, "")}", lLeft + 8f, modTop + 66f, linePaint)
        canvas.drawText("Active: ${report.loansSummary.activeCount} | Settled: ${report.loansSummary.settledCount}", lLeft + 8f, modTop + 82f, linePaint)
        canvas.drawText("Overdue Alerts: ${report.loansSummary.overdueCount}", lLeft + 8f, modTop + 98f, linePaint)

        // Card 3: Investment Summary
        val iLeft = lLeft + modW + cardGap
        val iRect = RectF(iLeft, modTop, iLeft + modW, modTop + modH)
        canvas.drawRoundRect(iRect, 6f, 6f, cardPaint)
        canvas.drawRoundRect(iRect, 6f, 6f, borderPaint)
        canvas.drawText("INVESTMENT SUMMARY", iLeft + 8f, modTop + 16f, headPaint)
        canvas.drawText("Principal: ${money(report.investmentsSummary.totalPrincipalMinor, "")}", iLeft + 8f, modTop + 34f, linePaint)
        canvas.drawText("Current Val: ${money(report.investmentsSummary.totalCurrentValueMinor, "")}", iLeft + 8f, modTop + 50f, linePaint)
        val profitPaint = Paint(linePaint).apply { color = INCOME_GREEN }
        val lossPaint = Paint(linePaint).apply { color = EXPENSE_CORAL }
        canvas.drawText("Profit (Sep.): +${money(report.investmentsSummary.totalProfitMinor, "")}", iLeft + 8f, modTop + 66f, profitPaint)
        canvas.drawText("Loss (Sep.): -${money(report.investmentsSummary.totalLossMinor, "")}", iLeft + 8f, modTop + 82f, lossPaint)
        canvas.drawText("Holdings: ${report.investments.size} | Budgets: ${report.budgetPeriodSummary.categoryBudgets.size}", iLeft + 8f, modTop + 98f, linePaint)
    }

    private fun drawCashFlowChart(
        canvas: Canvas,
        report: FamilyReportData,
        topY: Float,
        money: (Long, String) -> String
    ) {
        val boxRect = RectF(MARGIN_H, topY, PAGE_WIDTH - MARGIN_H, topY + 166f)
        canvas.drawRoundRect(boxRect, 6f, 6f, Paint().apply { color = Color.WHITE })
        canvas.drawRoundRect(boxRect, 6f, 6f, Paint().apply {
            color = BORDER_GRAY
            style = Paint.Style.STROKE
            strokeWidth = 1f
        })

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_DARK
            textSize = 10.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = TEXT_MUTED }
        canvas.drawText("Income vs. Expense Cash Flow Chart", MARGIN_H + 10f, topY + 18f, titlePaint)

        // Legend pills
        canvas.drawRect(PAGE_WIDTH - MARGIN_H - 132f, topY + 11f, PAGE_WIDTH - MARGIN_H - 124f, topY + 19f, Paint().apply { color = INCOME_GREEN })
        canvas.drawText("Income", PAGE_WIDTH - MARGIN_H - 120f, topY + 18f, legendPaint)
        canvas.drawRect(PAGE_WIDTH - MARGIN_H - 70f, topY + 11f, PAGE_WIDTH - MARGIN_H - 62f, topY + 19f, Paint().apply { color = EXPENSE_CORAL })
        canvas.drawText("Expense", PAGE_WIDTH - MARGIN_H - 58f, topY + 18f, legendPaint)

        val series = report.monthlySeries
        val chartLeft = MARGIN_H + 14f
        val chartRight = PAGE_WIDTH - MARGIN_H - 14f
        val chartTop = topY + 34f
        val chartBottom = topY + 138f
        val chartHeight = chartBottom - chartTop

        val axisPaint = Paint().apply { color = BORDER_GRAY; strokeWidth = 1f }
        canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, axisPaint)

        val maxVal = max(
            1L,
            series.maxOfOrNull { max(it.incomeMinor, it.expenseMinor) } ?: 1L
        )
        val groupW = (chartRight - chartLeft) / series.size.coerceAtLeast(1)
        val barW = (groupW * 0.34f).coerceIn(5f, 14f)
        val incPaint = Paint().apply { color = INCOME_GREEN }
        val expPaint = Paint().apply { color = EXPENSE_CORAL }
        val lblPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_MUTED
            textSize = 7.5f
            textAlign = Paint.Align.CENTER
        }

        series.forEachIndexed { idx, pt ->
            val centerX = chartLeft + groupW * (idx + 0.5f)
            val incH = (pt.incomeMinor.toFloat() / maxVal.toFloat()) * chartHeight
            val expH = (pt.expenseMinor.toFloat() / maxVal.toFloat()) * chartHeight
            if (incH > 0f) {
                canvas.drawRect(centerX - barW - 1f, chartBottom - incH, centerX - 1f, chartBottom, incPaint)
            }
            if (expH > 0f) {
                canvas.drawRect(centerX + 1f, chartBottom - expH, centerX + 1f + barW, chartBottom, expPaint)
            }
            canvas.drawText(pt.label, centerX, chartBottom + 12f, lblPaint)
        }

        val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_MUTED; textSize = 7.5f }
        canvas.drawText(
            "Peak Month Scale: ${money(maxVal, "")}  |  Period Net Cash Flow: ${money(report.netCashFlowMinor, if (report.netCashFlowMinor > 0) "+" else "")}",
            MARGIN_H + 10f,
            topY + 158f,
            scalePaint
        )
    }

    private fun drawCategoryChart(
        canvas: Canvas,
        report: FamilyReportData,
        topY: Float,
        money: (Long, String) -> String
    ) {
        val useIncome = report.reportType == ReportType.INCOME ||
            (report.expenseCategories.isEmpty() && report.incomeCategories.isNotEmpty())
        val slices: List<ExpenseCategorySlice> =
            if (useIncome) report.incomeCategories.take(6) else report.expenseCategories.take(6)
        val chartTitle = if (useIncome) "Income Category Distribution Chart" else "Expense Category Distribution Chart"
        val barColor = if (useIncome) INCOME_GREEN else INFO_TEAL

        val boxRect = RectF(MARGIN_H, topY, PAGE_WIDTH - MARGIN_H, topY + 162f)
        canvas.drawRoundRect(boxRect, 6f, 6f, Paint().apply { color = Color.WHITE })
        canvas.drawRoundRect(boxRect, 6f, 6f, Paint().apply {
            color = BORDER_GRAY
            style = Paint.Style.STROKE
            strokeWidth = 1f
        })

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_DARK
            textSize = 10.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(chartTitle, MARGIN_H + 10f, topY + 18f, titlePaint)

        val rowTop = topY + 30f
        val barLeft = MARGIN_H + 132f
        val barMaxW = CONTENT_WIDTH - 246f
        val trackPaint = Paint().apply { color = BRAND_MINT_BG }
        val fillPaint = Paint().apply { color = barColor }
        val catPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_DARK; textSize = 8.5f }
        val amtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_MUTED
            textSize = 8.5f
            textAlign = Paint.Align.RIGHT
        }

        slices.forEachIndexed { idx, slice ->
            val yRow = rowTop + idx * 21f
            canvas.drawText(ellipsize(slice.category, 20), MARGIN_H + 10f, yRow + 9f, catPaint)
            canvas.drawRoundRect(RectF(barLeft, yRow + 1f, barLeft + barMaxW, yRow + 10f), 3f, 3f, trackPaint)
            val fillW = (barMaxW * slice.shareFraction.coerceIn(0f, 1f)).coerceAtLeast(3f)
            canvas.drawRoundRect(RectF(barLeft, yRow + 1f, barLeft + fillW, yRow + 10f), 3f, 3f, fillPaint)
            canvas.drawText(
                "${money(slice.amountMinor, "")} (${slice.sharePercent}%)",
                PAGE_WIDTH - MARGIN_H - 10f,
                yRow + 9f,
                amtPaint
            )
        }
    }

    private fun drawMemberExpenseChart(
        canvas: Canvas,
        report: FamilyReportData,
        topY: Float,
        money: (Long, String) -> String
    ) {
        val bars = report.memberExpenses.take(6)
        val boxRect = RectF(MARGIN_H, topY, PAGE_WIDTH - MARGIN_H, topY + 162f)
        canvas.drawRoundRect(boxRect, 6f, 6f, Paint().apply { color = Color.WHITE })
        canvas.drawRoundRect(boxRect, 6f, 6f, Paint().apply {
            color = BORDER_GRAY
            style = Paint.Style.STROKE
            strokeWidth = 1f
        })

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_DARK
            textSize = 10.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("Member-wise Expense Breakdown Chart", MARGIN_H + 10f, topY + 18f, titlePaint)

        val rowTop = topY + 30f
        val barLeft = MARGIN_H + 146f
        val barMaxW = CONTENT_WIDTH - 260f
        val trackPaint = Paint().apply { color = BRAND_MINT_BG }
        val fillPaint = Paint().apply { color = WEALTH_GOLD }
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_DARK; textSize = 8.5f }
        val amtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_MUTED
            textSize = 8.5f
            textAlign = Paint.Align.RIGHT
        }

        bars.forEachIndexed { idx, bar ->
            val yRow = rowTop + idx * 21f
            canvas.drawText(
                ellipsize("${bar.memberName} (${bar.transactionCount})", 22),
                MARGIN_H + 10f,
                yRow + 9f,
                namePaint
            )
            canvas.drawRoundRect(RectF(barLeft, yRow + 1f, barLeft + barMaxW, yRow + 10f), 3f, 3f, trackPaint)
            val fillW = (barMaxW * bar.shareFraction.coerceIn(0f, 1f)).coerceAtLeast(3f)
            canvas.drawRoundRect(RectF(barLeft, yRow + 1f, barLeft + fillW, yRow + 10f), 3f, 3f, fillPaint)
            canvas.drawText(
                "${money(bar.expenseMinor, "")} (${bar.sharePercent}%)",
                PAGE_WIDTH - MARGIN_H - 10f,
                yRow + 9f,
                amtPaint
            )
        }
    }

    private fun drawSectionTitle(canvas: Canvas, title: String, topY: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BRAND_EMERALD
            textSize = 11f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(title, MARGIN_H, topY + 15f, paint)
    }

    private fun drawEmptyNotice(canvas: Canvas, msg: String, topY: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_MUTED; textSize = 9f }
        canvas.drawText(msg, MARGIN_H, topY + 14f, p)
    }

    private fun drawTableHeaderBar(
        canvas: Canvas,
        topY: Float,
        columns: List<Pair<String, Float>>
    ) {
        canvas.drawRect(MARGIN_H, topY, PAGE_WIDTH - MARGIN_H, topY + TABLE_HEADER_HEIGHT, Paint().apply {
            color = BRAND_MINT_BG
        })
        canvas.drawLine(MARGIN_H, topY + TABLE_HEADER_HEIGHT, PAGE_WIDTH - MARGIN_H, topY + TABLE_HEADER_HEIGHT, Paint().apply {
            color = BORDER_GRAY
            strokeWidth = 1f
        })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BRAND_EMERALD
            textSize = 8f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        columns.forEach { (label, x) ->
            canvas.drawText(label, MARGIN_H + x, topY + 14f, p)
        }
    }

    private fun drawTransactionTableChunk(
        canvas: Canvas,
        report: FamilyReportData,
        chunk: PdfBlock.TransactionTableChunk,
        startY: Float,
        money: (Long, String) -> String
    ): Float {
        var y = startY
        drawTableHeaderBar(
            canvas,
            y,
            listOf(
                "Date" to 6f,
                "Type" to 72f,
                "Category" to 124f,
                "Member" to 212f,
                "Description" to 292f,
                "Amount" to 446f
            )
        )
        y += TABLE_HEADER_HEIGHT
        val rowText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_DARK; textSize = 8f }
        val amtText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 8f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val slice = report.transactions.subList(chunk.startIndex, chunk.endExclusive)
        slice.forEachIndexed { idx, row ->
            if (idx % 2 == 1) {
                canvas.drawRect(MARGIN_H, y, PAGE_WIDTH - MARGIN_H, y + ROW_HEIGHT, Paint().apply { color = ROW_STRIPE })
            }
            val isIncome = row.transactionType.equals(TransactionType.INCOME.name, ignoreCase = true)
            canvas.drawText(row.dateText, MARGIN_H + 6f, y + 13f, rowText)
            canvas.drawText(if (isIncome) "INCOME" else "EXPENSE", MARGIN_H + 72f, y + 13f, rowText)
            canvas.drawText(ellipsize(row.category, 15), MARGIN_H + 124f, y + 13f, rowText)
            canvas.drawText(ellipsize(row.memberName, 14), MARGIN_H + 212f, y + 13f, rowText)
            canvas.drawText(ellipsize(row.description, 26), MARGIN_H + 292f, y + 13f, rowText)
            amtText.color = if (isIncome) INCOME_GREEN else EXPENSE_CORAL
            val sign = if (isIncome) "+" else "-"
            canvas.drawText(ellipsize(money(row.amountMinor, sign), 16), MARGIN_H + 446f, y + 13f, amtText)
            y += ROW_HEIGHT
        }
        return y + 10f
    }

    private fun drawLoanTableChunk(
        canvas: Canvas,
        report: FamilyReportData,
        chunk: PdfBlock.LoanTableChunk,
        startY: Float,
        money: (Long, String) -> String
    ): Float {
        var y = startY
        drawTableHeaderBar(
            canvas,
            y,
            listOf(
                "Counterparty" to 6f,
                "Direction" to 146f,
                "Installments" to 224f,
                "Due Date" to 306f,
                "Principal" to 384f,
                "Remaining" to 458f
            )
        )
        y += TABLE_HEADER_HEIGHT
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_DARK; textSize = 8f }
        report.loans.subList(chunk.startIndex, chunk.endExclusive).forEachIndexed { idx, loan ->
            if (idx % 2 == 1) {
                canvas.drawRect(MARGIN_H, y, PAGE_WIDTH - MARGIN_H, y + ROW_HEIGHT, Paint().apply { color = ROW_STRIPE })
            }
            val dueText = loan.entity.dueDateMillis?.let(::formatModuleDate) ?: "Open"
            val dirText = if (loan.direction == LoanDirection.BORROWED) "Borrowed" else "Lent"
            canvas.drawText(ellipsize(loan.entity.counterparty, 22), MARGIN_H + 6f, y + 13f, textPaint)
            canvas.drawText(dirText, MARGIN_H + 146f, y + 13f, textPaint)
            canvas.drawText("${loan.entity.paidInstallments}/${loan.entity.totalInstallments}", MARGIN_H + 224f, y + 13f, textPaint)
            canvas.drawText(dueText, MARGIN_H + 306f, y + 13f, textPaint)
            canvas.drawText(ellipsize(money(loan.principalMinor, ""), 13), MARGIN_H + 384f, y + 13f, textPaint)
            canvas.drawText(ellipsize(money(loan.remainingMinor, ""), 13), MARGIN_H + 458f, y + 13f, textPaint)
            y += ROW_HEIGHT
        }
        return y + 10f
    }

    private fun drawSavingsTableChunk(
        canvas: Canvas,
        report: FamilyReportData,
        chunk: PdfBlock.SavingsTableChunk,
        startY: Float,
        money: (Long, String) -> String
    ): Float {
        var y = startY
        drawTableHeaderBar(
            canvas,
            y,
            listOf(
                "Savings Goal" to 6f,
                "Category" to 166f,
                "Saved" to 272f,
                "Target" to 356f,
                "Progress" to 444f
            )
        )
        y += TABLE_HEADER_HEIGHT
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_DARK; textSize = 8f }
        report.savingsGoals.subList(chunk.startIndex, chunk.endExclusive).forEachIndexed { idx, goal ->
            if (idx % 2 == 1) {
                canvas.drawRect(MARGIN_H, y, PAGE_WIDTH - MARGIN_H, y + ROW_HEIGHT, Paint().apply { color = ROW_STRIPE })
            }
            canvas.drawText(ellipsize(goal.entity.goalName, 26), MARGIN_H + 6f, y + 13f, textPaint)
            canvas.drawText(ellipsize(goal.entity.category, 16), MARGIN_H + 166f, y + 13f, textPaint)
            canvas.drawText(ellipsize(money(goal.currentMinor, ""), 14), MARGIN_H + 272f, y + 13f, textPaint)
            canvas.drawText(ellipsize(money(goal.targetMinor, ""), 14), MARGIN_H + 356f, y + 13f, textPaint)
            canvas.drawText("${goal.progressPercent}%", MARGIN_H + 444f, y + 13f, textPaint)
            y += ROW_HEIGHT
        }
        return y + 10f
    }

    private fun drawInvestmentTableChunk(
        canvas: Canvas,
        report: FamilyReportData,
        chunk: PdfBlock.InvestmentTableChunk,
        startY: Float,
        money: (Long, String) -> String
    ): Float {
        var y = startY
        drawTableHeaderBar(
            canvas,
            y,
            listOf(
                "Asset Name" to 6f,
                "Type" to 146f,
                "Principal" to 218f,
                "Current Value" to 300f,
                "Profit / Loss" to 396f,
                "Return" to 476f
            )
        )
        y += TABLE_HEADER_HEIGHT
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_DARK; textSize = 8f }
        val plPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f }
        report.investments.subList(chunk.startIndex, chunk.endExclusive).forEachIndexed { idx, inv ->
            if (idx % 2 == 1) {
                canvas.drawRect(MARGIN_H, y, PAGE_WIDTH - MARGIN_H, y + ROW_HEIGHT, Paint().apply { color = ROW_STRIPE })
            }
            canvas.drawText(ellipsize(inv.entity.assetName, 22), MARGIN_H + 6f, y + 13f, textPaint)
            canvas.drawText(ellipsize(inv.entity.assetType, 12), MARGIN_H + 146f, y + 13f, textPaint)
            canvas.drawText(ellipsize(money(inv.principalMinor, ""), 14), MARGIN_H + 218f, y + 13f, textPaint)
            canvas.drawText(ellipsize(money(inv.currentValueMinor, ""), 14), MARGIN_H + 300f, y + 13f, textPaint)
            val plText = if (inv.profitMinor > 0L) "+${money(inv.profitMinor, "")}" else if (inv.lossMinor > 0L) "-${money(inv.lossMinor, "")}" else money(0L, "")
            plPaint.color = when {
                inv.profitMinor > 0L -> INCOME_GREEN
                inv.lossMinor > 0L -> EXPENSE_CORAL
                else -> TEXT_MUTED
            }
            canvas.drawText(ellipsize(plText, 14), MARGIN_H + 396f, y + 13f, plPaint)
            canvas.drawText(inv.returnPercentText, MARGIN_H + 476f, y + 13f, plPaint)
            y += ROW_HEIGHT
        }
        return y + 10f
    }

    private fun drawBudgetTableChunk(
        canvas: Canvas,
        report: FamilyReportData,
        chunk: PdfBlock.BudgetTableChunk,
        startY: Float,
        money: (Long, String) -> String
    ): Float {
        val rows = listOfNotNull(report.budgetPeriodSummary.overallBudget) +
            report.budgetPeriodSummary.categoryBudgets
        var y = startY
        drawTableHeaderBar(
            canvas,
            y,
            listOf(
                "Budget Scope / Category" to 6f,
                "Limit" to 198f,
                "Spent" to 288f,
                "Remaining" to 378f,
                "Status" to 462f
            )
        )
        y += TABLE_HEADER_HEIGHT
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_DARK; textSize = 8f }
        rows.subList(chunk.startIndex, chunk.endExclusive).forEachIndexed { idx, bud ->
            if (idx % 2 == 1) {
                canvas.drawRect(MARGIN_H, y, PAGE_WIDTH - MARGIN_H, y + ROW_HEIGHT, Paint().apply { color = ROW_STRIPE })
            }
            canvas.drawText(ellipsize("${bud.entity.category} (${bud.entity.periodKey})", 30), MARGIN_H + 6f, y + 13f, textPaint)
            canvas.drawText(ellipsize(money(bud.limitMinor, ""), 14), MARGIN_H + 198f, y + 13f, textPaint)
            canvas.drawText(ellipsize(money(bud.spentMinor, ""), 14), MARGIN_H + 288f, y + 13f, textPaint)
            canvas.drawText(ellipsize(money(bud.remainingMinor, ""), 14), MARGIN_H + 378f, y + 13f, textPaint)
            canvas.drawText("${bud.usagePercent}% • ${bud.alertLevel.label}", MARGIN_H + 462f, y + 13f, textPaint)
            y += ROW_HEIGHT
        }
        return y + 10f
    }

    private fun drawFinancialEventTableChunk(
        canvas: Canvas,
        report: FamilyReportData,
        chunk: PdfBlock.FinancialEventTableChunk,
        startY: Float,
        money: (Long, String) -> String
    ): Float {
        val events = filteredModuleEvents(report)
        var y = startY
        drawTableHeaderBar(
            canvas,
            y,
            listOf(
                "Date" to 6f,
                "Module" to 80f,
                "Event" to 152f,
                "Amount" to 256f,
                "Balance After" to 348f,
                "Note" to 438f
            )
        )
        y += TABLE_HEADER_HEIGHT
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_DARK; textSize = 8f }
        events.subList(chunk.startIndex, chunk.endExclusive).forEachIndexed { idx, evt ->
            if (idx % 2 == 1) {
                canvas.drawRect(MARGIN_H, y, PAGE_WIDTH - MARGIN_H, y + ROW_HEIGHT, Paint().apply { color = ROW_STRIPE })
            }
            canvas.drawText(formatModuleDate(evt.eventDateMillis), MARGIN_H + 6f, y + 13f, textPaint)
            canvas.drawText(ellipsize(evt.moduleType, 11), MARGIN_H + 80f, y + 13f, textPaint)
            canvas.drawText(ellipsize(evt.eventType.replace('_', ' '), 16), MARGIN_H + 152f, y + 13f, textPaint)
            canvas.drawText(ellipsize(money(evt.amountMinor, ""), 13), MARGIN_H + 256f, y + 13f, textPaint)
            val balText = evt.balanceAfterMinor?.let { money(it, "") } ?: "-"
            canvas.drawText(ellipsize(balText, 13), MARGIN_H + 348f, y + 13f, textPaint)
            canvas.drawText(ellipsize(evt.note.ifBlank { "-" }, 14), MARGIN_H + 438f, y + 13f, textPaint)
            y += ROW_HEIGHT
        }
        return y + 10f
    }

    fun filteredModuleEvents(report: FamilyReportData): List<LocalFinancialEventEntity> {
        val allowedModule = when (report.reportType) {
            ReportType.LOAN -> setOf("LOAN")
            ReportType.SAVINGS -> setOf("SAVINGS")
            ReportType.INVESTMENT -> setOf("INVESTMENT")
            ReportType.COMPLETE_STATEMENT -> setOf("LOAN", "SAVINGS", "INVESTMENT")
            else -> emptySet()
        }
        if (allowedModule.isEmpty()) return emptyList()
        return report.savingsAndLoanEvents.filter { evt ->
            evt.moduleType.uppercase() in allowedModule
        }
    }

    private fun drawPageFooter(
        canvas: Canvas,
        report: FamilyReportData,
        pageNumber: Int,
        totalPages: Int
    ) {
        val linePaint = Paint().apply { color = BORDER_GRAY; strokeWidth = 0.8f }
        canvas.drawLine(MARGIN_H, FOOTER_Y - 12f, PAGE_WIDTH - MARGIN_H, FOOTER_Y - 12f, linePaint)
        val leftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_MUTED; textSize = 8f }
        val rightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_MUTED
            textSize = 8f
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(
            "Family Finance • On-device Statement (${report.generationDateText})",
            MARGIN_H,
            FOOTER_Y,
            leftPaint
        )
        canvas.drawText(
            "Page $pageNumber of $totalPages",
            PAGE_WIDTH - MARGIN_H,
            FOOTER_Y,
            rightPaint
        )
    }

    private fun ellipsize(raw: String, maxChars: Int): String =
        if (raw.length <= maxChars) raw else raw.take((maxChars - 1).coerceAtLeast(1)) + "…"
}
