package com.jasonz3157.weightjournal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.dialog.Dialog
import com.itos.coui.picker.CalendarPicker
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val syncStampWidth = 88.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordScreen(
    padding: PaddingValues,
    state: JournalUiState,
    records: List<WeightRecord>,
    openSettings: () -> Unit,
    setValue: (String, Double?) -> String?,
    onRefresh: () -> Unit,
) {
    val refresh = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = state.manuallySyncing,
        onRefresh = onRefresh,
        enabled = state.canSync,
        state = refresh,
        modifier = Modifier.fillMaxSize().semantics {
            if (state.canSync && !state.manuallySyncing) {
                customActions = listOf(CustomAccessibilityAction("立即同步") {
                    onRefresh()
                    true
                })
            }
        },
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = refresh, isRefreshing = state.manuallySyncing,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding()),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        },
    ) {
        RecordList(padding, state, records, openSettings, setValue)
    }
}

@Composable
private fun RecordList(
    padding: PaddingValues,
    state: JournalUiState,
    records: List<WeightRecord>,
    openSettings: () -> Unit,
    setValue: (String, Double?) -> String?,
) {
    val date = state.today
    val current = records.lastOrNull()?.takeIf { it.date == date }
    val height = state.journal.height
    val syncing = state.visibleSyncing
    val syncResult = state.syncResult.takeUnless { syncing }
    var editing by rememberSaveable(date.toString()) { mutableStateOf(false) }
    var weight by rememberSaveable(date.toString()) { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val editingWeight = editing || weight.isNotBlank()
    val editingExistingWeight = current != null && editingWeight
    val showSyncStatus = state.dav != null && !editingExistingWeight
    val stampSpace = if (showSyncStatus) syncStampWidth - 16.dp else 0.dp
    val focus = LocalFocusManager.current
    val submit = {
        val value = weight.trim().replace(',', '.').toDoubleOrNull()
        error = if (value == null || value !in 1.0..1000.0) "请输入 1–1000 kg 的体重。" else setValue(date.toString(), value)
        if (error == null) {
            editing = false
            weight = ""
            focus.clearFocus()
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
        contentPadding = padding,
    ) {
        item {
            Box(Modifier.padding(horizontal = 16.dp, vertical = FirstCardTopPadding)) {
                Panel(decoration = {
                    if (showSyncStatus && (syncing || syncResult != null)) {
                        SyncStamp(syncResult, Modifier.align(Alignment.BottomEnd))
                    }
                }) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle(
                                if (current == null) "记录体重" else if (editingExistingWeight) "修改体重" else "今日体重",
                                modifier = Modifier.weight(1f),
                            )
                            if (current != null) {
                                Text(
                                    text = if (editingExistingWeight) "取消" else "修改",
                                    modifier = Modifier.widthIn(min = 48.dp)
                                        .clip(RoundedCornerShape(percent = 50))
                                        .clickable(role = Role.Button) {
                                            editing = !editingExistingWeight
                                            weight = if (editingExistingWeight) "" else formatNumber(current.kg, "0.###############")
                                            error = null
                                            if (editingExistingWeight) focus.clearFocus()
                                        }
                                        .padding(horizontal = 12.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelLarge,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                        SupportingText(date.format(DateTimeFormatter.ofPattern("M月d日 · EEEE", java.util.Locale.SIMPLIFIED_CHINESE)))
                    }
                    if (current == null || editingWeight) {
                        DecimalField(weight, "体重", "kg", { weight = it; error = null }, submit, error)
                        Row(Modifier.fillMaxWidth()) {
                            Button(
                                onClick = submit,
                                modifier = Modifier.weight(1f),
                                enabled = state.storageError == null,
                            ) {
                                Text("保存体重")
                            }
                            if (showSyncStatus) Spacer(Modifier.width(stampSpace))
                        }
                    } else {
                        TodayWeight(current)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (height == null) {
                                TextButton(onClick = openSettings, modifier = Modifier.weight(1f, fill = false)) {
                                    Text("设置身高以计算 BMI")
                                }
                            } else {
                                TodayBmi(
                                    reading = bmiReading(current.kg, height),
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                            }
                            Spacer(Modifier.width(stampSpace))
                        }
                    }
                    state.storageError?.let { SupportingText(it, error = true) }
                }
            }
        }
        item {
            Box(Modifier.padding(horizontal = 16.dp)) { TrendPanel(records, height, date) }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun TodayWeight(record: WeightRecord) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(formatNumber(record.kg), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold)
        Text("kg", modifier = Modifier.padding(bottom = 7.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        record.delta?.let {
            Text(
                "${formatChange(it)} kg", modifier = Modifier.padding(bottom = 7.dp),
                style = MaterialTheme.typography.labelLarge, color = changeColor(it),
            )
        }
    }
}

@Composable
private fun TodayBmi(reading: BmiReading, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val colors = MaterialTheme.colorScheme
    val (textColor, background) = when (reading.level) {
        BmiLevel.Low -> if (dark) Color(0xFFB8D8EF) to Color(0xFF263E50) else Color(0xFF284B66) to Color(0xFFE8F1F8)
        BmiLevel.Normal -> colors.onPrimaryContainer to colors.primaryContainer
        BmiLevel.Overweight -> if (dark) Color(0xFFF0D598) to Color(0xFF483A1E) else Color(0xFF6F5017) to Color(0xFFFAEDCA)
        BmiLevel.Moderate -> if (dark) Color(0xFFFFC6A2) to Color(0xFF523522) else Color(0xFF834016) to Color(0xFFFBE8DA)
        BmiLevel.Severe -> if (dark) Color(0xFFFFC4BD) to Color(0xFF532C2A) else Color(0xFF8A312E) to Color(0xFFFBE2E2)
    }
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text("BMI  ${formatNumber(reading.value, "0.0")}", color = colors.primary)
        Surface(shape = RoundedCornerShape(4.dp), color = background, contentColor = textColor) {
            Text(
                reading.level.label, Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SyncStamp(result: SyncResult?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (accent, description) = when (result) {
        SyncResult.Success -> colors.primary to "WebDAV 同步成功"
        SyncResult.Failure -> colors.error to "WebDAV 同步失败"
        null -> colors.onSurfaceVariant to "WebDAV 同步中"
    }
    Canvas(
        modifier.size(width = syncStampWidth, height = 56.dp)
            .semantics { contentDescription = description },
    ) {
        val center = Offset(size.width - 28.dp.toPx(), size.height - 14.dp.toPx())
        val foreground = accent.copy(alpha = 0.7f)
        val lineWidth = 5.dp.toPx()
        drawCircle(accent.copy(alpha = 0.24f), radius = 38.dp.toPx(), center = center, style = Stroke(6.dp.toPx()))
        when (result) {
            SyncResult.Success -> {
                val check = Path().apply {
                    moveTo(center.x - 16.dp.toPx(), center.y - 1.dp.toPx())
                    lineTo(center.x - 5.dp.toPx(), center.y + 10.dp.toPx())
                    lineTo(center.x + 16.dp.toPx(), center.y - 11.dp.toPx())
                }
                drawPath(check, foreground, style = Stroke(lineWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            SyncResult.Failure -> {
                drawLine(
                    foreground,
                    start = center - Offset(0f, 14.dp.toPx()),
                    end = center + Offset(0f, 2.dp.toPx()),
                    strokeWidth = lineWidth,
                    cap = StrokeCap.Round,
                )
                drawCircle(foreground, radius = 2.5.dp.toPx(), center = center + Offset(0f, 10.dp.toPx()))
            }
            null -> {
                for (dot in -1..1) {
                    drawCircle(foreground, radius = 2.5.dp.toPx(), center = center + Offset(dot * 9.dp.toPx(), 0f))
                }
            }
        }
    }
}

private enum class TrendRange(val title: String, val months: Long) {
    Month("1个月", 1), Quarter("3个月", 3), Half("半年", 6), Year("1年", 12);

    fun start(end: LocalDate): LocalDate = end.minusMonths(months).plusDays(1)
}

@Composable
private fun TrendPanel(records: List<WeightRecord>, height: Double?, today: LocalDate) {
    var range by rememberSaveable { mutableIntStateOf(1) }
    var focusedDate by rememberSaveable { mutableStateOf<String?>(null) }
    var customRange by rememberSaveable { mutableStateOf<LongArray?>(null) }
    var choosingDates by rememberSaveable { mutableStateOf(false) }
    var draftStart by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    var draftEnd by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    val customIndex = TrendRange.entries.size
    // 保留旧版范围编号. 原来的 1 周 (编号 0) 回到 1 个月.
    val selectedRangeIndex = (range - 1).coerceIn(0, customIndex)
    val custom = customRange.takeIf { selectedRangeIndex == customIndex }
    val presetRange = TrendRange.entries.getOrElse(selectedRangeIndex) { TrendRange.Month }
    val end = custom?.last()?.let(LocalDate::ofEpochDay)?.coerceAtMost(today) ?: today
    val start = custom?.first()?.let(LocalDate::ofEpochDay)?.coerceAtMost(end) ?: presetRange.start(end)
    val selected = records.filter { it.date in start..end }
    val last = selected.lastOrNull()
    val focused = selected.firstOrNull { it.date.toString() == focusedDate } ?: last
    val delta = if (selected.size >= 2) selected.last().kg - selected.first().kg else null
    val openDates = {
        draftStart = customRange?.first() ?: start.toEpochDay()
        draftEnd = customRange?.last() ?: end.toEpochDay()
        choosingDates = true
    }
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("体重趋势")
            delta?.let {
                Text("区间 ${formatChange(it)} kg", color = changeColor(it), style = MaterialTheme.typography.labelLarge)
            }
        }
        Segments(TrendRange.entries.map { it.title } + "自定义", selectedRangeIndex, fitLabels = true) { index ->
            if (index == customIndex) {
                openDates()
            } else {
                range = index + 1
                focusedDate = null
            }
        }
        if (custom != null) {
            TextButton(onClick = openDates, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(if (start == end) "$start · 修改" else "$start ～ $end · 修改")
            }
        }
        if (focused == null) {
            Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                SupportingText(if (records.isEmpty()) "还没有体重记录" else "这个时间范围内没有记录")
            }
        } else {
            val selectedIndex = selected.indexOf(focused)
            val weightText = "${focused.date}  ·  ${formatNumber(focused.kg)} kg"
            val bmiText = height?.let { "  ·  BMI ${formatNumber(bmi(focused.kg, it), "0.0")}" }.orEmpty()
            SupportingText(weightText + bmiText)
            TrendChart(selected, height, start, end, selectedIndex) { focusedDate = selected[it].date.toString() }
        }
    }
    val draftEndDate = LocalDate.ofEpochDay(draftEnd).coerceAtMost(today)
    val draftDates = LocalDate.ofEpochDay(draftStart).coerceAtMost(draftEndDate)..draftEndDate
    val earliest = minOf(LocalDate.of(1900, 1, 1), records.firstOrNull()?.date ?: today, draftDates.start)
    TrendDatesDialog(
        visible = choosingDates,
        dates = draftDates,
        allowed = earliest..today,
        onChange = {
            draftStart = it.start.toEpochDay()
            draftEnd = it.endInclusive.toEpochDay()
        },
        onConfirm = {
            customRange = longArrayOf(draftDates.start.toEpochDay(), draftDates.endInclusive.toEpochDay())
            range = customIndex + 1
            focusedDate = null
            choosingDates = false
        },
        onDismiss = { choosingDates = false },
    )
}

@Composable
private fun TrendDatesDialog(
    visible: Boolean,
    dates: ClosedRange<LocalDate>,
    allowed: ClosedRange<LocalDate>,
    onChange: (ClosedRange<LocalDate>) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var endpoint by rememberSaveable { mutableIntStateOf(0) }
    Dialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = "自定义日期",
        confirmAction = CouiDialogAction("应用", onConfirm),
        dismissAction = CouiDialogAction("取消", onDismiss),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = CouiDialogDefaults.buttonEdgePadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = endpoint == 0, onClick = { endpoint = 0 }, label = { Text("开始 ${dates.start}") })
                FilterChip(selected = endpoint == 1, onClick = { endpoint = 1 }, label = { Text("结束 ${dates.endInclusive}") })
            }
            CalendarPicker(
                modifier = Modifier.fillMaxWidth(),
                value = if (endpoint == 0) dates.start else dates.endInclusive,
                onValueChange = { date ->
                    val next = if (endpoint == 0) date..maxOf(date, dates.endInclusive) else minOf(date, dates.start)..date
                    onChange(next)
                },
                minDate = allowed.start,
                maxDate = allowed.endInclusive,
                today = allowed.endInclusive,
            )
        }
    }
}

@Composable
private fun TrendChart(
    records: List<WeightRecord>,
    heightCm: Double?,
    start: LocalDate,
    end: LocalDate,
    focused: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = colors.onSurfaceVariant, fontSize = 11.sp)
    val length = end.toEpochDay() - start.toEpochDay()
    val fractions = records.map { if (length == 0L) 0.5f else (it.date.toEpochDay() - start.toEpochDay()).toFloat() / length }
    val min = records.minOf { it.kg }
    val max = records.maxOf { it.kg }
    val pad = ((max - min) * 0.15).coerceAtLeast(0.25)
    val lower = (min - pad).coerceAtLeast(0.0)
    val upper = max + pad
    val epochFormat = DateTimeFormatter.ofPattern("M/d")
    val ticks = List(4) { index -> upper - (upper - lower) * index / 3 }
    val kgTitle = textMeasurer.measure("kg", labelStyle)
    val kgLabels = ticks.map { textMeasurer.measure(formatNumber(it, "0.#"), labelStyle) }
    val bmiTitle = heightCm?.let { textMeasurer.measure("BMI", labelStyle) }
    val bmiLabels = heightCm?.let { height ->
        val values = ticks.map { bmi(it, height) }
        val rounded = values.map { formatNumber(it, "0.0") }
        val labels = if (rounded.distinct().size == ticks.size) rounded else values.map { formatNumber(it, "0.00") }
        labels.map { textMeasurer.measure(it, labelStyle) }
    }.orEmpty()
    val tickCount = minOf(length + 1, 4L).toInt()
    val xLabels = List(tickCount) { index ->
        val offset = if (tickCount == 1) 0L else length * index / (tickCount - 1)
        val position = if (length == 0L) 0.5f else offset.toFloat() / length
        position to textMeasurer.measure(start.plusDays(offset).format(epochFormat), labelStyle)
    }
    val axisGap = with(LocalDensity.current) { 8.dp.toPx() }
    val left = maxOf(kgTitle.size.width, kgLabels.maxOf { it.size.width }) + axisGap
    val rightInset = maxOf(bmiTitle?.size?.width ?: 0, bmiLabels.maxOfOrNull { it.size.width } ?: 0) + axisGap
    val onPointSelected by rememberUpdatedState(onSelect)
    fun selectAt(x: Float, width: Int) {
        val plotWidth = (width - left - rightInset).coerceAtLeast(1f)
        val fraction = ((x - left) / plotWidth).coerceIn(0f, 1f)
        onPointSelected(fractions.indices.minBy { abs(fractions[it] - fraction) })
    }
    Canvas(
        Modifier.fillMaxWidth().height(184.dp)
            .semantics {
                contentDescription = if (heightCm == null) "体重趋势曲线，${records.size} 条记录"
                    else "体重与 BMI 趋势曲线，${records.size} 条记录"
            }
            .pointerInput(fractions, left, rightInset) {
                detectTapGestures { tap -> selectAt(tap.x, size.width) }
            }
            .pointerInput(fractions, left, rightInset) {
                detectHorizontalDragGestures(
                    onDragStart = { start -> selectAt(start.x, size.width) },
                    onHorizontalDrag = { change, _ -> selectAt(change.position.x, size.width) },
                )
            },
    ) {
        val right = (size.width - rightInset).coerceAtLeast(left + 1f)
        val titleHeight = maxOf(kgTitle.size.height, bmiTitle?.size?.height ?: 0)
        val maxTickHeight = maxOf(kgLabels.maxOf { it.size.height }, bmiLabels.maxOfOrNull { it.size.height } ?: 0)
        val top = titleHeight + maxTickHeight / 2f + 3.dp.toPx()
        val bottom = (size.height - xLabels.maxOf { (_, label) -> label.size.height } - 10.dp.toPx()).coerceAtLeast(top + 1f)
        val plotHeight = bottom - top
        val gridWidth = 1.dp.toPx()
        drawText(kgTitle, topLeft = Offset(left - axisGap - kgTitle.size.width, 0f))
        drawLine(colors.outlineVariant, Offset(left, top), Offset(left, bottom), gridWidth)
        if (bmiTitle != null) {
            drawText(bmiTitle, topLeft = Offset(right + axisGap, 0f))
            drawLine(colors.outlineVariant, Offset(right, top), Offset(right, bottom), gridWidth)
        }
        ticks.indices.forEach { index ->
            val y = top + plotHeight * index / ticks.lastIndex
            drawLine(colors.outlineVariant, Offset(left, y), Offset(right, y), gridWidth)
            val kgLabel = kgLabels[index]
            drawText(kgLabel, topLeft = Offset(left - axisGap - kgLabel.size.width, y - kgLabel.size.height / 2f))
            bmiLabels.getOrNull(index)?.let { label ->
                drawText(label, topLeft = Offset(right + axisGap, y - label.size.height / 2f))
            }
        }
        val minimumLabelStep = xLabels.maxOf { (_, label) -> label.size.width } + 4.dp.toPx()
        val visibleXLabels = if (xLabels.size > 2 && (right - left) / xLabels.lastIndex < minimumLabelStep) {
            listOf(xLabels.first(), xLabels.last())
        } else {
            xLabels
        }
        visibleXLabels.forEach { (position, label) ->
            val x = left + (right - left) * position
            drawText(label, topLeft = Offset((x - label.size.width / 2f).coerceIn(0f, (size.width - label.size.width).coerceAtLeast(0f)), bottom + 7.dp.toPx()))
        }
        val points = records.mapIndexed { index, record ->
            Offset(left + (right - left) * fractions[index], bottom - ((record.kg - lower) / (upper - lower)).toFloat() * plotHeight)
        }
        fun Path.trace() {
            moveTo(points.first().x, points.first().y)
            points.zipWithNext().forEach { (a, b) ->
                val mid = (a.x + b.x) / 2
                cubicTo(mid, a.y, mid, b.y, b.x, b.y)
            }
        }
        if (points.size > 1) {
            val area = Path().apply {
                trace()
                lineTo(points.last().x, bottom)
                lineTo(points.first().x, bottom)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(colors.primary.copy(alpha = 0.24f), colors.primary.copy(alpha = 0.01f)), top, bottom))
            drawPath(Path().apply { trace() }, colors.primary, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        }
        val focus = points[focused]
        drawLine(colors.primary.copy(alpha = 0.32f), Offset(focus.x, top), Offset(focus.x, bottom), 1.dp.toPx())
        drawCircle(colors.surfaceBright, 6.dp.toPx(), focus)
        drawCircle(colors.primary, 4.dp.toPx(), focus)
    }
}
