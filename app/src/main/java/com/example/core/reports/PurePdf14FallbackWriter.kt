package com.example.core.reports

import com.example.core.model.MoneyCalculator
import com.example.core.model.TransactionType
import com.example.ui.screens.modules.formatModuleDate
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.max

/**
 * Pure-Kotlin PDF 1.4 vector fallback writer used automatically when Android's native
 * [android.graphics.pdf.PdfDocument] is unavailable (such as headless JVM test environments).
 * Produces valid multi-page A4 (`595x842`) PDF streams matching [FamilyReportPdfDocumentBuilder.planPages].
 */
internal object PurePdf14FallbackWriter {

    private const val PAGE_W = FamilyReportPdfDocumentBuilder.PAGE_WIDTH
    private const val PAGE_H = FamilyReportPdfDocumentBuilder.PAGE_HEIGHT
    private const val MARGIN_L = 36f
    private const val CONTENT_W = PAGE_W - MARGIN_L * 2f

    fun write(
        report: FamilyReportData,
        plannedPages: List<PdfPlannedPage>,
        outputStream: OutputStream
    ): Int {
        val pages = plannedPages.ifEmpty {
            listOf(PdfPlannedPage(1, listOf(PdfBlock.HeaderBanner, PdfBlock.SummaryAndModules)))
        }
        val totalPages = pages.size
        val contentStreams = pages.map { page -> buildPageContentStream(report, page, totalPages) }

        val objects = mutableListOf<String>()
        // Obj 1: Catalog
        objects += "<< /Type /Catalog /Pages 2 0 R >>"
        // Obj 2: Pages
        val firstPageObjNum = 5
        val kidsRefs = pages.indices.joinToString(" ") { idx -> "${firstPageObjNum + idx * 2} 0 R" }
        objects += "<< /Type /Pages /Kids [ $kidsRefs ] /Count $totalPages >>"
        // Obj 3: Helvetica Regular
        objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        // Obj 4: Helvetica Bold
        objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>"

        // Page + Content stream pairs
        contentStreams.forEachIndexed { idx, streamText ->
            val contentObjNum = firstPageObjNum + idx * 2 + 1
            objects += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $PAGE_W $PAGE_H] " +
                "/Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents $contentObjNum 0 R >>"
            val streamBytes = streamText.toByteArray(Charsets.ISO_8859_1)
            objects += "<< /Length ${streamBytes.size} >>\nstream\n$streamText\nendstream"
        }

        val baos = ByteArrayOutputStream()
        fun emit(text: String) = baos.write(text.toByteArray(Charsets.ISO_8859_1))

        emit("%PDF-1.4\n")
        val offsets = mutableListOf<Int>()
        objects.forEachIndexed { idx, body ->
            offsets += baos.size()
            emit("${idx + 1} 0 obj\n$body\nendobj\n")
        }
        val xrefOffset = baos.size()
        emit("xref\n0 ${objects.size + 1}\n")
        emit("0000000000 65535 f \n")
        offsets.forEach { offset ->
            emit(String.format("%010d 00000 n \n", offset))
        }
        emit("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n")

        val bytes = baos.toByteArray()
        outputStream.write(bytes)
        outputStream.flush()
        return totalPages
    }

    private fun buildPageContentStream(
        report: FamilyReportData,
        planned: PdfPlannedPage,
        totalPages: Int
    ): String {
        val sb = StringBuilder()
        fun money(minor: Long, prefix: String = ""): String =
            MoneyCalculator.formatForPdf(minor, report.currency, supportsTakaGlyph = false, prefixSign = prefix)

        fun toPdfY(topDownY: Float): Float = (PAGE_H - topDownY).coerceIn(12f, PAGE_H.toFloat())

        fun drawRectTopDown(x: Float, topY: Float, w: Float, h: Float, r: Float, g: Float, b: Float) {
            val bottomY = PAGE_H - (topY + h)
            sb.append(String.format("%.3f %.3f %.3f rg\n%.2f %.2f %.2f %.2f re f\n", r, g, b, x, bottomY, w, h))
        }

        fun drawText(x: Float, topY: Float, size: Int, bold: Boolean, text: String, r: Float = 0.08f, g: Float = 0.12f, b: Float = 0.10f) {
            val safe = escapePdfText(text)
            val font = if (bold) "/F2" else "/F1"
            sb.append(
                String.format(
                    "%.3f %.3f %.3f rg\nBT %s %d Tf %.2f %.2f Td (%s) Tj ET\n",
                    r, g, b, font, size, x, toPdfY(topY), safe
                )
            )
        }

        var y = 34f
        if (planned.pageNumber > 1) {
            drawText(MARGIN_L, y + 10f, 9, true, "${report.familyName} - ${report.reportType.displayName} (${report.periodLabel})", 0.25f, 0.33f, 0.29f)
            y += 26f
        }

        for (block in planned.blocks) {
            when (block) {
                PdfBlock.HeaderBanner -> {
                    drawRectTopDown(MARGIN_L, y, CONTENT_W, 92f, 0.043f, 0.431f, 0.310f)
                    drawText(MARGIN_L + 12f, y + 24f, 16, true, report.familyName, 1f, 1f, 1f)
                    drawText(MARGIN_L + 12f, y + 42f, 10, false, report.reportType.displayName, 0.86f, 0.96f, 0.91f)
                    drawText(MARGIN_L + 12f, y + 58f, 9, false, "Report Period: ${report.periodLabel} (${report.dateRangeText})", 1f, 1f, 1f)
                    drawText(MARGIN_L + 12f, y + 74f, 9, false, "Generated: ${report.generationDateText} | Currency: ${report.currency}", 1f, 1f, 1f)
                    y += 104f
                }
                PdfBlock.SummaryAndModules -> {
                    drawText(MARGIN_L, y + 14f, 10, true, "Income: ${money(report.totalIncomeMinor)}  |  Expense: ${money(report.totalExpenseMinor)}  |  Net Cash Flow: ${money(report.netCashFlowMinor, if (report.netCashFlowMinor > 0L) "+" else "")}")
                    drawText(MARGIN_L, y + 32f, 10, false, "Household Net Worth: ${money(report.summary.netWorthMinor)}")
                    drawText(MARGIN_L, y + 52f, 9, false, "Savings Summary: Saved ${money(report.savingsSummary.totalSavedMinor)} / Target ${money(report.savingsSummary.totalTargetMinor)}")
                    drawText(MARGIN_L, y + 68f, 9, false, "Loan Summary: Borrowed ${money(report.loansSummary.remainingBorrowedMinor)} | Lent ${money(report.loansSummary.remainingLentMinor)}")
                    drawText(MARGIN_L, y + 84f, 9, false, "Investment Summary: Principal ${money(report.investmentsSummary.totalPrincipalMinor)} | Profit +${money(report.investmentsSummary.totalProfitMinor)} | Loss -${money(report.investmentsSummary.totalLossMinor)}")
                    y += 206f
                }
                PdfBlock.CashFlowChart -> {
                    drawText(MARGIN_L, y + 16f, 10, true, "Income vs. Expense Cash Flow Chart")
                    val maxVal = max(1L, report.monthlySeries.maxOfOrNull { max(it.incomeMinor, it.expenseMinor) } ?: 1L)
                    val groupW = (CONTENT_W - 24f) / report.monthlySeries.size.coerceAtLeast(1)
                    report.monthlySeries.forEachIndexed { idx, pt ->
                        val bx = MARGIN_L + 12f + idx * groupW
                        val ih = (pt.incomeMinor.toFloat() / maxVal.toFloat()) * 90f
                        val eh = (pt.expenseMinor.toFloat() / maxVal.toFloat()) * 90f
                        if (ih > 0f) drawRectTopDown(bx, y + 126f - ih, 7f, ih, 0.055f, 0.541f, 0.365f)
                        if (eh > 0f) drawRectTopDown(bx + 8f, y + 126f - eh, 7f, eh, 0.820f, 0.294f, 0.294f)
                    }
                    y += 176f
                }
                PdfBlock.CategoryChart -> {
                    drawText(MARGIN_L, y + 16f, 10, true, "Category Distribution Chart")
                    val slices = (if (report.reportType == ReportType.INCOME) report.incomeCategories else report.expenseCategories).take(6)
                    slices.forEachIndexed { idx, s ->
                        val rowY = y + 32f + idx * 20f
                        drawText(MARGIN_L + 6f, rowY + 8f, 8, false, "${s.category}: ${money(s.amountMinor)} (${s.sharePercent}%)")
                        drawRectTopDown(MARGIN_L + 200f, rowY + 2f, (s.shareFraction.coerceIn(0.02f, 1f) * 260f), 7f, 0.047f, 0.494f, 0.549f)
                    }
                    y += 172f
                }
                PdfBlock.MemberExpenseChart -> {
                    drawText(MARGIN_L, y + 16f, 10, true, "Member-wise Expense Breakdown Chart")
                    report.memberExpenses.take(6).forEachIndexed { idx, bar ->
                        val rowY = y + 32f + idx * 20f
                        drawText(MARGIN_L + 6f, rowY + 8f, 8, false, "${bar.memberName}: ${money(bar.expenseMinor)} (${bar.sharePercent}%)")
                        drawRectTopDown(MARGIN_L + 210f, rowY + 2f, (bar.shareFraction.coerceIn(0.02f, 1f) * 250f), 7f, 0.722f, 0.490f, 0.118f)
                    }
                    y += 172f
                }
                is PdfBlock.SectionTitle -> {
                    drawText(MARGIN_L, y + 14f, 11, true, block.title, 0.043f, 0.431f, 0.310f)
                    y += 22f
                }
                is PdfBlock.TransactionTableChunk -> {
                    drawRectTopDown(MARGIN_L, y, CONTENT_W, 20f, 0.92f, 0.96f, 0.94f)
                    drawText(MARGIN_L + 4f, y + 13f, 8, true, "Date | Type | Category | Member | Amount")
                    y += 22f
                    report.transactions.subList(block.startIndex, block.endExclusive).forEach { row ->
                        val sign = if (row.transactionType.equals(TransactionType.INCOME.name, ignoreCase = true)) "+" else "-"
                        drawText(MARGIN_L + 4f, y + 13f, 8, false, "${row.dateText} | ${row.category} | ${row.memberName} | ${money(row.amountMinor, sign)}")
                        y += 20f
                    }
                    y += 10f
                }
                is PdfBlock.LoanTableChunk -> {
                    drawText(MARGIN_L + 4f, y + 13f, 8, true, "Counterparty | Installments | Principal | Remaining")
                    y += 22f
                    report.loans.subList(block.startIndex, block.endExclusive).forEach { loan ->
                        drawText(MARGIN_L + 4f, y + 13f, 8, false, "${loan.entity.counterparty} | ${loan.entity.paidInstallments}/${loan.entity.totalInstallments} | ${money(loan.principalMinor)} | ${money(loan.remainingMinor)}")
                        y += 20f
                    }
                    y += 10f
                }
                is PdfBlock.SavingsTableChunk -> {
                    drawText(MARGIN_L + 4f, y + 13f, 8, true, "Savings Goal | Saved | Target | Progress")
                    y += 22f
                    report.savingsGoals.subList(block.startIndex, block.endExclusive).forEach { g ->
                        drawText(MARGIN_L + 4f, y + 13f, 8, false, "${g.entity.goalName} | ${money(g.currentMinor)} | ${money(g.targetMinor)} | ${g.progressPercent}%")
                        y += 20f
                    }
                    y += 10f
                }
                is PdfBlock.InvestmentTableChunk -> {
                    drawText(MARGIN_L + 4f, y + 13f, 8, true, "Asset | Principal | Value | Profit/Loss")
                    y += 22f
                    report.investments.subList(block.startIndex, block.endExclusive).forEach { inv ->
                        val pl = if (inv.profitMinor > 0L) "+${money(inv.profitMinor)}" else "-${money(inv.lossMinor)}"
                        drawText(MARGIN_L + 4f, y + 13f, 8, false, "${inv.entity.assetName} | ${money(inv.principalMinor)} | ${money(inv.currentValueMinor)} | $pl")
                        y += 20f
                    }
                    y += 10f
                }
                is PdfBlock.BudgetTableChunk -> {
                    val rows = listOfNotNull(report.budgetPeriodSummary.overallBudget) + report.budgetPeriodSummary.categoryBudgets
                    y += 22f
                    rows.subList(block.startIndex, block.endExclusive).forEach { bud ->
                        drawText(MARGIN_L + 4f, y + 13f, 8, false, "${bud.entity.category}: ${money(bud.spentMinor)} / ${money(bud.limitMinor)}")
                        y += 20f
                    }
                    y += 10f
                }
                is PdfBlock.FinancialEventTableChunk -> {
                    val events = FamilyReportPdfDocumentBuilder.filteredModuleEvents(report)
                    drawText(MARGIN_L + 4f, y + 13f, 8, true, "Date | Module | Event | Amount | Balance")
                    y += 22f
                    events.subList(block.startIndex, block.endExclusive).forEach { evt ->
                        val bal = evt.balanceAfterMinor?.let { money(it) } ?: "-"
                        drawText(MARGIN_L + 4f, y + 13f, 8, false, "${formatModuleDate(evt.eventDateMillis)} | ${evt.moduleType} | ${evt.eventType} | ${money(evt.amountMinor)} | $bal")
                        y += 20f
                    }
                    y += 10f
                }
                is PdfBlock.EmptyNotice -> {
                    drawText(MARGIN_L, y + 14f, 9, false, block.message)
                    y += 28f
                }
            }
        }
        drawText(PAGE_W - MARGIN_L - 70f, 816f, 8, false, "Page ${planned.pageNumber} of $totalPages", 0.25f, 0.33f, 0.29f)
        return sb.toString()
    }

    private fun escapePdfText(raw: String): String = buildString {
        raw.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '(' -> append("\\(")
                ')' -> append("\\)")
                '\r', '\n' -> append(' ')
                else -> append(if (ch.code in 32..126) ch else '?')
            }
        }
    }
}
