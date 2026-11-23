package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.analytics.MonthlyCashFlowPoint
import com.example.core.analytics.YearlyCashFlowPoint
import com.example.core.model.MoneyCalculator
import com.example.ui.theme.ExpenseCoral
import com.example.ui.theme.IncomeGreen
import com.example.ui.theme.InfoTeal
import com.example.ui.theme.MoneySmallStyle
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

@Composable
fun AnalyticsChartCard(
    title: String,
    subtitle: String,
    isEmpty: Boolean,
    emptyIcon: ImageVector = Icons.Outlined.BarChart,
    emptyTitle: String = "No data for this period",
    emptySubtitle: String = "Record entries or widen the date filter to view this chart.",
    testTag: String = "",
    headerAction: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.then(if (testTag.isNotBlank()) Modifier.testTag(testTag) else Modifier),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                headerAction?.invoke()
            }

            if (isEmpty) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = emptyIcon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(30.dp)
                        )
                        Text(
                            text = emptyTitle,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = emptySubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                content()
            }
        }
    }
}

@Composable
fun ChartLegendDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ChartTooltipBanner(
    title: String,
    metrics: List<Pair<String, Color>>,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                metrics.forEach { (text, color) ->
                    Text(
                        text = text,
                        style = MoneySmallStyle,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

fun formatCompactAxisAmount(amountMinor: Long, currency: String): String {
    val major = MoneyCalculator.minorUnitsToBigDecimal(amountMinor, currency)
    val absMajor = major.abs()
    val sym = MoneyCalculator.currencySymbol(currency)
    val sign = if (amountMinor < 0L) "-" else ""
    return when {
        absMajor >= BigDecimal(1_000_000) -> {
            val scaled = absMajor.divide(BigDecimal(1_000_000), 1, RoundingMode.HALF_UP)
            "$sign$sym${scaled.stripTrailingZeros().toPlainString()}M"
        }
        absMajor >= BigDecimal(1_000) -> {
            val scaled = absMajor.divide(BigDecimal(1_000), 1, RoundingMode.HALF_UP)
            "$sign$sym${scaled.stripTrailingZeros().toPlainString()}k"
        }
        else -> "$sign$sym${absMajor.setScale(0, RoundingMode.HALF_UP).toPlainString()}"
    }
}

@Composable
fun MonthlyIncomeExpenseBarChart(
    data: List<MonthlyCashFlowPoint>,
    currency: String,
    modifier: Modifier = Modifier
) {
    val hasAnyActivity = remember(data) { data.any { it.incomeMinor > 0L || it.expenseMinor > 0L } }
    val defaultIndex = remember(data) {
        data.indexOfLast { it.incomeMinor > 0L || it.expenseMinor > 0L }.coerceAtLeast(0)
    }
    var selectedIdx by remember(data) { mutableIntStateOf(defaultIndex) }
    val progress by animateFloatAsState(targetValue = 1f, animationSpec = tween(350), label = "monthly_bars")

    if (!hasAnyActivity) {
        Text(
            text = "No monthly income or expense entries recorded yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    val maxMinor = remember(data) {
        data.maxOfOrNull { maxOf(it.incomeMinor, it.expenseMinor) }?.coerceAtLeast(100L) ?: 100L
    }
    val selected = data.getOrNull(selectedIdx)
    val incomeColor = IncomeGreen
    val expenseColor = ExpenseCoral
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val highlightBg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChartLegendDot(incomeColor, "Income")
            ChartLegendDot(expenseColor, "Expense")
            Spacer(Modifier.weight(1f))
            Text(
                text = "Tap a month",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AnimatedVisibility(visible = selected != null) {
            selected?.let { item ->
                ChartTooltipBanner(
                    title = "${item.label} (${item.yearMonth})",
                    metrics = listOf(
                        "In: ${MoneyCalculator.formatMinorUnits(item.incomeMinor, currency)}" to incomeColor,
                        "Out: ${MoneyCalculator.formatMinorUnits(item.expenseMinor, currency)}" to expenseColor,
                        "Net: ${MoneyCalculator.formatMinorUnits(item.netMinor, currency)}" to
                            if (item.netMinor >= 0L) incomeColor else expenseColor
                    )
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier
                    .height(176.dp)
                    .padding(end = 6.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                Text(formatCompactAxisAmount(maxMinor, currency), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatCompactAxisAmount(maxMinor / 2L, currency), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatCompactAxisAmount(0L, currency), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
            ) {
                val chartWidth = 440.dp
                Box(
                    modifier = Modifier
                        .width(chartWidth)
                        .height(176.dp)
                        .pointerInput(data) {
                            detectTapGestures { offset ->
                                if (data.isNotEmpty() && size.width > 0) {
                                    val idx = ((offset.x / size.width) * data.size)
                                        .toInt()
                                        .coerceIn(0, data.lastIndex)
                                    selectedIdx = idx
                                }
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val gridLines = 2
                        for (i in 0..gridLines) {
                            val y = size.height * (i / gridLines.toFloat())
                            drawLine(
                                color = gridColor,
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                        val groupW = size.width / data.size
                        val barW = (groupW * 0.28f).coerceAtMost(14.dp.toPx())
                        val gap = (groupW * 0.08f).coerceAtMost(4.dp.toPx())

                        data.forEachIndexed { index, item ->
                            val leftX = groupW * index
                            if (index == selectedIdx) {
                                drawRoundRect(
                                    color = highlightBg,
                                    topLeft = Offset(leftX + 2.dp.toPx(), 0f),
                                    size = Size(groupW - 4.dp.toPx(), size.height),
                                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                                )
                            }
                            val centerX = leftX + groupW / 2f
                            val incH = ((item.incomeMinor.toFloat() / maxMinor.toFloat()) * size.height * 0.92f) * progress
                            val expH = ((item.expenseMinor.toFloat() / maxMinor.toFloat()) * size.height * 0.92f) * progress
                            if (incH > 0f) {
                                drawRoundRect(
                                    color = incomeColor,
                                    topLeft = Offset(centerX - barW - gap / 2f, size.height - incH),
                                    size = Size(barW, incH),
                                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                                )
                            }
                            if (expH > 0f) {
                                drawRoundRect(
                                    color = expenseColor,
                                    topLeft = Offset(centerX + gap / 2f, size.height - expH),
                                    size = Size(barW, expH),
                                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                                )
                            }
                        }
                    }
                }
                Row(modifier = Modifier.width(chartWidth)) {
                    data.forEachIndexed { idx, item ->
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (idx == selectedIdx) FontWeight.Bold else FontWeight.Normal,
                            color = if (idx == selectedIdx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun YearlyIncomeExpenseBarChart(
    data: List<YearlyCashFlowPoint>,
    currency: String,
    modifier: Modifier = Modifier
) {
    var selectedIdx by remember(data) { mutableIntStateOf((data.size - 1).coerceAtLeast(0)) }
    val maxMinor = remember(data) {
        data.maxOfOrNull { maxOf(it.incomeMinor, it.expenseMinor) }?.coerceAtLeast(100L) ?: 100L
    }
    val selected = data.getOrNull(selectedIdx)
    val incomeColor = IncomeGreen
    val expenseColor = ExpenseCoral
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val highlightBg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ChartLegendDot(incomeColor, "Annual Income")
            ChartLegendDot(expenseColor, "Annual Expense")
        }

        selected?.let { item ->
            ChartTooltipBanner(
                title = "Year ${item.year}",
                metrics = listOf(
                    "In: ${MoneyCalculator.formatMinorUnits(item.incomeMinor, currency)}" to incomeColor,
                    "Out: ${MoneyCalculator.formatMinorUnits(item.expenseMinor, currency)}" to expenseColor,
                    "Net: ${MoneyCalculator.formatMinorUnits(item.netMinor, currency)}" to
                        if (item.netMinor >= 0L) incomeColor else expenseColor
                )
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(
                modifier = Modifier
                    .height(160.dp)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                Text(formatCompactAxisAmount(maxMinor, currency), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatCompactAxisAmount(maxMinor / 2L, currency), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatCompactAxisAmount(0L, currency), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .pointerInput(data) {
                            detectTapGestures { offset ->
                                if (data.isNotEmpty() && size.width > 0) {
                                    selectedIdx = ((offset.x / size.width) * data.size)
                                        .toInt()
                                        .coerceIn(0, data.lastIndex)
                                }
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        for (i in 0..2) {
                            val y = size.height * (i / 2f)
                            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                        }
                        val groupW = size.width / data.size.coerceAtLeast(1)
                        val barW = (groupW * 0.24f).coerceAtMost(24.dp.toPx())
                        val gap = (groupW * 0.08f).coerceAtMost(8.dp.toPx())

                        data.forEachIndexed { index, item ->
                            val leftX = groupW * index
                            if (index == selectedIdx) {
                                drawRoundRect(
                                    color = highlightBg,
                                    topLeft = Offset(leftX + 4.dp.toPx(), 0f),
                                    size = Size(groupW - 8.dp.toPx(), size.height),
                                    cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                                )
                            }
                            val cx = leftX + groupW / 2f
                            val incH = (item.incomeMinor.toFloat() / maxMinor.toFloat()) * size.height * 0.9f
                            val expH = (item.expenseMinor.toFloat() / maxMinor.toFloat()) * size.height * 0.9f
                            if (incH > 0f) {
                                drawRoundRect(
                                    color = incomeColor,
                                    topLeft = Offset(cx - barW - gap / 2f, size.height - incH),
                                    size = Size(barW, incH),
                                    cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
                                )
                            }
                            if (expH > 0f) {
                                drawRoundRect(
                                    color = expenseColor,
                                    topLeft = Offset(cx + gap / 2f, size.height - expH),
                                    size = Size(barW, expH),
                                    cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
                                )
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    data.forEachIndexed { idx, item ->
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (idx == selectedIdx) FontWeight.Bold else FontWeight.Normal,
                            color = if (idx == selectedIdx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MonthlyCashFlowNetChart(
    data: List<MonthlyCashFlowPoint>,
    currency: String,
    modifier: Modifier = Modifier
) {
    val defaultIndex = remember(data) {
        data.indexOfLast { it.incomeMinor > 0L || it.expenseMinor > 0L }.coerceAtLeast(0)
    }
    var selectedIdx by remember(data) { mutableIntStateOf(defaultIndex) }
    val maxAbsMinor = remember(data) {
        data.maxOfOrNull { abs(it.netMinor) }?.coerceAtLeast(100L) ?: 100L
    }
    val selected = data.getOrNull(selectedIdx)
    val surplusColor = IncomeGreen
    val deficitColor = ExpenseCoral
    val lineColor = InfoTeal
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ChartLegendDot(surplusColor, "Surplus")
            ChartLegendDot(deficitColor, "Deficit")
            ChartLegendDot(lineColor, "Trend")
        }

        selected?.let { item ->
            val sign = if (item.netMinor > 0L) "+" else ""
            ChartTooltipBanner(
                title = "${item.label} Net Cash Flow (${item.yearMonth})",
                metrics = listOf(
                    "Net: $sign${MoneyCalculator.formatMinorUnits(item.netMinor, currency)}" to
                        if (item.netMinor >= 0L) surplusColor else deficitColor,
                    "In: ${MoneyCalculator.formatMinorUnits(item.incomeMinor, currency)}" to surplusColor,
                    "Out: ${MoneyCalculator.formatMinorUnits(item.expenseMinor, currency)}" to deficitColor
                )
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(
                modifier = Modifier
                    .height(176.dp)
                    .padding(end = 6.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                Text("+${formatCompactAxisAmount(maxAbsMinor, currency)}", style = MaterialTheme.typography.labelSmall, color = surplusColor)
                Text(formatCompactAxisAmount(0L, currency), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("-${formatCompactAxisAmount(maxAbsMinor, currency)}", style = MaterialTheme.typography.labelSmall, color = deficitColor)
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
            ) {
                val chartWidth = 440.dp
                Box(
                    modifier = Modifier
                        .width(chartWidth)
                        .height(176.dp)
                        .pointerInput(data) {
                            detectTapGestures { offset ->
                                if (data.isNotEmpty() && size.width > 0) {
                                    selectedIdx = ((offset.x / size.width) * data.size)
                                        .toInt()
                                        .coerceIn(0, data.lastIndex)
                                }
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val zeroY = size.height / 2f
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, zeroY),
                            end = Offset(size.width, zeroY),
                            strokeWidth = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                        )
                        val groupW = size.width / data.size.coerceAtLeast(1)
                        val barW = (groupW * 0.42f).coerceAtMost(18.dp.toPx())
                        val linePoints = mutableListOf<Offset>()

                        data.forEachIndexed { index, item ->
                            val cx = groupW * index + groupW / 2f
                            val ratio = (item.netMinor.toFloat() / maxAbsMinor.toFloat()).coerceIn(-1f, 1f)
                            val h = abs(ratio) * (size.height * 0.44f)
                            val yPoint = zeroY - ratio * (size.height * 0.44f)
                            linePoints += Offset(cx, yPoint)

                            if (h > 1f) {
                                val topY = if (item.netMinor >= 0L) zeroY - h else zeroY
                                drawRoundRect(
                                    color = (if (item.netMinor >= 0L) surplusColor else deficitColor).copy(
                                        alpha = if (index == selectedIdx) 0.95f else 0.55f
                                    ),
                                    topLeft = Offset(cx - barW / 2f, topY),
                                    size = Size(barW, h),
                                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                                )
                            }
                        }

                        if (linePoints.size >= 2) {
                            val path = Path().apply {
                                moveTo(linePoints.first().x, linePoints.first().y)
                                for (i in 1 until linePoints.size) {
                                    lineTo(linePoints[i].x, linePoints[i].y)
                                }
                            }
                            drawPath(
                                path = path,
                                color = lineColor,
                                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                        linePoints.forEachIndexed { idx, pt ->
                            drawCircle(
                                color = if (idx == selectedIdx) lineColor else Color.White,
                                radius = if (idx == selectedIdx) 5.dp.toPx() else 3.dp.toPx(),
                                center = pt
                            )
                            if (idx != selectedIdx) {
                                drawCircle(
                                    color = lineColor,
                                    radius = 3.dp.toPx(),
                                    center = pt,
                                    style = Stroke(width = 1.5.dp.toPx())
                                )
                            }
                        }
                    }
                }
                Row(modifier = Modifier.width(chartWidth)) {
                    data.forEachIndexed { idx, item ->
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (idx == selectedIdx) FontWeight.Bold else FontWeight.Normal,
                            color = if (idx == selectedIdx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
