package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.analytics.ExpenseCategorySlice
import com.example.core.analytics.InvestmentPerformanceBar
import com.example.core.analytics.MemberExpenseBar
import com.example.core.analytics.SavingsGrowthPoint
import com.example.core.model.MoneyCalculator
import com.example.ui.theme.ExpenseCoral
import com.example.ui.theme.IncomeGreen
import com.example.ui.theme.InfoTeal
import com.example.ui.theme.MoneySmallStyle
import com.example.ui.theme.WarningAmber
import com.example.ui.theme.WealthGoldTertiary
import kotlin.math.atan2
import kotlin.math.hypot

private val CategoryPalette = listOf(
    Color(0xFF0B6E4F),
    Color(0xFF0C7E8C),
    Color(0xFFB87D1E),
    Color(0xFFD14B4B),
    Color(0xFF2B6CB0),
    Color(0xFF6B46C1),
    Color(0xFF2F855A),
    Color(0xFFD97706),
    Color(0xFF718096)
)

@Composable
fun ExpenseCategoryDonutChart(
    slices: List<ExpenseCategorySlice>,
    currency: String,
    modifier: Modifier = Modifier
) {
    var selectedIdx by remember(slices) { mutableIntStateOf(0) }
    val totalMinor = remember(slices) { slices.sumOf { it.amountMinor } }
    val selected = slices.getOrNull(selectedIdx)
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHigh

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(148.dp)
                    .pointerInput(slices) {
                        detectTapGestures { offset ->
                            if (slices.isEmpty() || totalMinor <= 0L) return@detectTapGestures
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val dist = hypot(offset.x - cx, offset.y - cy)
                            if (dist < size.width * 0.18f || dist > size.width * 0.52f) return@detectTapGestures
                            var angle = Math.toDegrees(atan2((offset.y - cy).toDouble(), (offset.x - cx).toDouble())).toFloat()
                            angle = (angle + 90f + 360f) % 360f
                            var acc = 0f
                            slices.forEachIndexed { idx, slice ->
                                val sweep = (slice.amountMinor.toFloat() / totalMinor.toFloat()) * 360f
                                if (angle >= acc && angle <= acc + sweep) {
                                    selectedIdx = idx
                                    return@detectTapGestures
                                }
                                acc += sweep
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokePx = 22.dp.toPx()
                    val pad = strokePx / 2f + 4.dp.toPx()
                    val arcSize = Size(size.width - pad * 2f, size.height - pad * 2f)
                    val topLeft = Offset(pad, pad)
                    drawArc(
                        color = trackColor,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx)
                    )
                    var start = -90f
                    slices.forEachIndexed { idx, slice ->
                        val sweep = ((slice.amountMinor.toFloat() / totalMinor.coerceAtLeast(1L).toFloat()) * 360f)
                        val color = CategoryPalette[idx % CategoryPalette.size]
                        drawArc(
                            color = color,
                            startAngle = start,
                            sweepAngle = (sweep - 2f).coerceAtLeast(1f),
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(
                                width = if (idx == selectedIdx) strokePx + 4.dp.toPx() else strokePx,
                                cap = StrokeCap.Butt
                            )
                        )
                        start += sweep
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = selected?.let { "${it.sharePercent}%" } ?: "100%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = selected?.category ?: "Expenses",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                slices.take(6).forEachIndexed { idx, slice ->
                    val color = CategoryPalette[idx % CategoryPalette.size]
                    val isSelected = idx == selectedIdx
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { selectedIdx = idx },
                        shape = MaterialTheme.shapes.small,
                        color = if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(color)
                            )
                            Text(
                                text = slice.category,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${slice.sharePercent}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        selected?.let { slice ->
            ChartTooltipBanner(
                title = "${slice.category} • ${slice.transactionCount} transaction(s)",
                metrics = listOf(
                    MoneyCalculator.formatMinorUnits(slice.amountMinor, currency) to CategoryPalette[selectedIdx % CategoryPalette.size],
                    "${slice.sharePercent}% of total expenses" to MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}

@Composable
fun SavingsGrowthLineChart(
    points: List<SavingsGrowthPoint>,
    currency: String,
    modifier: Modifier = Modifier
) {
    var selectedIdx by remember(points) { mutableIntStateOf((points.size - 1).coerceAtLeast(0)) }
    val maxMinor = remember(points) {
        points.maxOfOrNull { it.cumulativeSavingsMinor }?.coerceAtLeast(100L) ?: 100L
    }
    val selected = points.getOrNull(selectedIdx)
    val lineColor = InfoTeal
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ChartLegendDot(lineColor, "Cumulative Savings")
        }

        selected?.let { pt ->
            val deltaPrefix = if (pt.deltaMinor >= 0L) "+" else ""
            ChartTooltipBanner(
                title = "${pt.fullDateLabel} (${pt.eventCount} movement(s))",
                metrics = listOf(
                    "Balance: ${MoneyCalculator.formatMinorUnits(pt.cumulativeSavingsMinor, currency)}" to lineColor,
                    "Change: $deltaPrefix${MoneyCalculator.formatMinorUnits(pt.deltaMinor, currency)}" to
                        if (pt.deltaMinor >= 0L) IncomeGreen else ExpenseCoral
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
                        .pointerInput(points) {
                            detectTapGestures { offset ->
                                if (points.isNotEmpty() && size.width > 0) {
                                    selectedIdx = ((offset.x / size.width) * points.size)
                                        .toInt()
                                        .coerceIn(0, points.lastIndex)
                                }
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        for (i in 0..2) {
                            val y = size.height * (i / 2f)
                            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                        }
                        val stepX = if (points.size <= 1) size.width / 2f else size.width / (points.size - 1).coerceAtLeast(1)
                        val coords = points.mapIndexed { idx, pt ->
                            val x = if (points.size == 1) size.width / 2f else idx * stepX
                            val y = size.height - ((pt.cumulativeSavingsMinor.toFloat() / maxMinor.toFloat()) * size.height * 0.88f)
                            Offset(x, y.coerceIn(8.dp.toPx(), size.height))
                        }

                        if (coords.size >= 2) {
                            val areaPath = Path().apply {
                                moveTo(coords.first().x, size.height)
                                coords.forEach { lineTo(it.x, it.y) }
                                lineTo(coords.last().x, size.height)
                                close()
                            }
                            drawPath(
                                path = areaPath,
                                brush = Brush.verticalGradient(
                                    colors = listOf(lineColor.copy(alpha = 0.28f), lineColor.copy(alpha = 0.02f))
                                )
                            )
                            val strokePath = Path().apply {
                                moveTo(coords.first().x, coords.first().y)
                                for (i in 1 until coords.size) lineTo(coords[i].x, coords[i].y)
                            }
                            drawPath(
                                path = strokePath,
                                color = lineColor,
                                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }

                        coords.forEachIndexed { idx, pt ->
                            drawCircle(
                                color = if (idx == selectedIdx) lineColor else Color.White,
                                radius = if (idx == selectedIdx) 6.dp.toPx() else 4.dp.toPx(),
                                center = pt
                            )
                            drawCircle(
                                color = lineColor,
                                radius = if (idx == selectedIdx) 6.dp.toPx() else 4.dp.toPx(),
                                center = pt,
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val visibleLabels = if (points.size <= 5) points else listOf(points.first(), points[points.size / 2], points.last())
                    visibleLabels.forEach { pt ->
                        Text(
                            text = pt.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun InvestmentPerformanceChart(
    items: List<InvestmentPerformanceBar>,
    currency: String,
    modifier: Modifier = Modifier
) {
    var selectedIdx by remember(items) { mutableIntStateOf(0) }
    val maxMinor = remember(items) {
        items.maxOfOrNull { maxOf(it.principalMinor, it.currentValueMinor) }?.coerceAtLeast(100L) ?: 100L
    }
    val selected = items.getOrNull(selectedIdx)
    val principalColor = WealthGoldTertiary
    val valueColor = IncomeGreen
    val lossValueColor = ExpenseCoral
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ChartLegendDot(principalColor, "Principal Cost")
            ChartLegendDot(valueColor, "Value (Gain)")
            ChartLegendDot(lossValueColor, "Value (Loss)")
        }

        selected?.let { item ->
            ChartTooltipBanner(
                title = "${item.label} (${item.assetType} • ${item.returnPercentText})",
                metrics = listOf(
                    "Cost: ${MoneyCalculator.formatMinorUnits(item.principalMinor, currency)}" to principalColor,
                    "Val: ${MoneyCalculator.formatMinorUnits(item.currentValueMinor, currency)}" to InfoTeal,
                    if (item.profitMinor > 0L) {
                        "Profit: +${MoneyCalculator.formatMinorUnits(item.profitMinor, currency)}" to IncomeGreen
                    } else {
                        "Loss: -${MoneyCalculator.formatMinorUnits(item.lossMinor, currency)}" to ExpenseCoral
                    }
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
                        .pointerInput(items) {
                            detectTapGestures { offset ->
                                if (items.isNotEmpty() && size.width > 0) {
                                    selectedIdx = ((offset.x / size.width) * items.size)
                                        .toInt()
                                        .coerceIn(0, items.lastIndex)
                                }
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        for (i in 0..2) {
                            val y = size.height * (i / 2f)
                            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                        }
                        val groupW = size.width / items.size.coerceAtLeast(1)
                        val barW = (groupW * 0.25f).coerceAtMost(22.dp.toPx())
                        val gap = (groupW * 0.08f).coerceAtMost(6.dp.toPx())

                        items.forEachIndexed { index, item ->
                            val cx = groupW * index + groupW / 2f
                            val costH = (item.principalMinor.toFloat() / maxMinor.toFloat()) * size.height * 0.9f
                            val valH = (item.currentValueMinor.toFloat() / maxMinor.toFloat()) * size.height * 0.9f
                            val curColor = if (item.lossMinor > 0L) lossValueColor else valueColor

                            if (costH > 0f) {
                                drawRoundRect(
                                    color = principalColor,
                                    topLeft = Offset(cx - barW - gap / 2f, size.height - costH),
                                    size = Size(barW, costH),
                                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                                )
                            }
                            if (valH > 0f) {
                                drawRoundRect(
                                    color = curColor,
                                    topLeft = Offset(cx + gap / 2f, size.height - valH),
                                    size = Size(barW, valH),
                                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                                )
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    items.forEachIndexed { idx, item ->
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (idx == selectedIdx) FontWeight.Bold else FontWeight.Normal,
                            color = if (idx == selectedIdx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MemberExpenseHorizontalChart(
    members: List<MemberExpenseBar>,
    currency: String,
    modifier: Modifier = Modifier
) {
    var selectedIdx by remember(members) { mutableIntStateOf(0) }
    val selected = members.getOrNull(selectedIdx)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        selected?.let { member ->
            ChartTooltipBanner(
                title = "${member.memberName} • ${member.transactionCount} expense(s)",
                metrics = listOf(
                    MoneyCalculator.formatMinorUnits(member.expenseMinor, currency) to ExpenseCoral,
                    "${member.sharePercent}% of family spend" to MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }

        members.forEachIndexed { idx, item ->
            val isSelected = idx == selectedIdx
            val barColor = if (idx == 0) ExpenseCoral else CategoryPalette[(idx + 1) % CategoryPalette.size]
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .clip(MaterialTheme.shapes.small)
                    .clickable { selectedIdx = idx },
                shape = MaterialTheme.shapes.small,
                color = if (isSelected) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent,
                border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = barColor.copy(alpha = 0.16f),
                                modifier = Modifier.size(26.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = item.memberName.take(1).uppercase(),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = barColor
                                    )
                                }
                            }
                            Text(
                                text = item.memberName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = "${MoneyCalculator.formatMinorUnits(item.expenseMinor, currency)} (${item.sharePercent}%)",
                            style = MoneySmallStyle,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    LinearProgressIndicator(
                        progress = { item.shareFraction.coerceIn(0.02f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(50)),
                        color = barColor,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                }
            }
        }
    }
}
