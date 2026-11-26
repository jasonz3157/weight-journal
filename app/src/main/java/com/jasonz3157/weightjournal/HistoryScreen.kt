package com.jasonz3157.weightjournal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.dialog.Dialog
import com.itos.coui.icons.CouiIcons
import com.itos.coui.menu.rememberCouiWindowBackdrop
import com.itos.coui.picker.CouiPickerDefaults
import com.itos.coui.picker.WheelPicker
import com.itos.coui.picker.couiPickerSelectionLines
import com.itos.coui.theme.CouiTheme
import java.time.LocalDate
import java.time.YearMonth

@Composable
internal fun HistoryScreen(
    padding: PaddingValues,
    state: JournalUiState,
    records: List<WeightRecord>,
    onEdit: (LocalDate, WeightRecord?) -> Unit,
) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var monthKey by rememberSaveable { mutableStateOf(YearMonth.from(state.today).toString()) }
    var choosingMonth by rememberSaveable { mutableStateOf(false) }
    var draftMonthKey by rememberSaveable { mutableStateOf(monthKey) }
    val latestMonth = YearMonth.from(state.today)
    val month = YearMonth.parse(monthKey).coerceAtMost(latestMonth)
    Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = FirstCardTopPadding)) {
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("历史记录")
                    SupportingText("${records.size} 条记录")
                }
                Segments(listOf("日历", "列表"), mode) { mode = it }
            }
        }
        state.storageError?.let { SupportingText(it, error = true, modifier = Modifier.padding(16.dp)) }
        if (mode == 0) {
            val previous: (() -> Unit)? = if (month > YearMonth.of(1, 1)) {
                { monthKey = month.minusMonths(1).toString() }
            } else null
            val next: (() -> Unit)? = if (month < latestMonth) {
                { monthKey = month.plusMonths(1).toString() }
            } else null
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .pointerInput(month, latestMonth) {
                        val threshold = 48.dp.toPx()
                        var distance = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { distance = 0f },
                            onDragEnd = {
                                if (distance >= threshold) previous?.invoke()
                                if (distance <= -threshold) next?.invoke()
                            },
                            onHorizontalDrag = { _, amount -> distance += amount },
                        )
                    }
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                CalendarPanel(
                    month = month,
                    today = state.today,
                    records = records,
                    onPreviousMonth = previous,
                    onNextMonth = next,
                    onPickMonth = {
                        draftMonthKey = month.toString()
                        choosingMonth = true
                    },
                    onDay = onEdit,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                if (records.isEmpty()) {
                    item { SupportingText("还没有体重记录", modifier = Modifier.padding(24.dp)) }
                } else {
                    items(records.asReversed(), key = { it.date.toString() }) { record ->
                        HistoryRow(record, state.journal.height, onEdit = { onEdit(record.date, record) })
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
    val draft = YearMonth.parse(draftMonthKey).coerceAtMost(latestMonth)
    val earliestYear = minOf(1900, month.year, draft.year, records.firstOrNull()?.date?.year ?: latestMonth.year)
    Dialog(
        visible = choosingMonth,
        onDismissRequest = { choosingMonth = false },
        title = "选择年月",
        confirmAction = CouiDialogAction("确定", {
            monthKey = draft.toString()
            choosingMonth = false
        }),
        dismissAction = CouiDialogAction("取消", { choosingMonth = false }),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = CouiDialogDefaults.buttonEdgePadding)
                .couiPickerSelectionLines(MaterialTheme.colorScheme.outlineVariant),
            horizontalArrangement = Arrangement.Center,
        ) {
            WheelPicker(
                modifier = Modifier.weight(1f),
                value = draft.year,
                onValueChange = { year ->
                    val monthValue = YearMonth.parse(draftMonthKey).monthValue
                    draftMonthKey = YearMonth.of(year, monthValue).coerceAtMost(latestMonth).toString()
                },
                valueRange = earliestYear..latestMonth.year,
                label = "年份",
                formatter = { "$it 年" },
                visualWidth = CouiPickerDefaults.dateYearWidth,
                wrap = false,
            )
            WheelPicker(
                modifier = Modifier.weight(1f),
                value = draft.monthValue,
                onValueChange = { monthValue ->
                    val year = YearMonth.parse(draftMonthKey).year
                    draftMonthKey = YearMonth.of(year, monthValue).coerceAtMost(latestMonth).toString()
                },
                valueRange = 1..if (draft.year == latestMonth.year) latestMonth.monthValue else 12,
                label = "月份",
                formatter = { "$it 月" },
                visualWidth = CouiPickerDefaults.dateMonthWidth,
                wrap = false,
            )
        }
    }
}

internal class HistoryEditor {
    var date by mutableStateOf("")
    var weight by mutableStateOf("")
    var editing by mutableStateOf(false)
    var deleting by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun open(day: LocalDate, record: WeightRecord?) {
        date = day.toString()
        weight = record?.kg?.let { formatNumber(it, "0.###############") }.orEmpty()
        error = null
        editing = true
    }
}

@Composable
internal fun rememberHistoryEditor(): HistoryEditor = rememberSaveable(
    saver = listSaver(
        save = { listOf(it.date, it.weight, it.editing, it.deleting) },
        restore = { saved ->
            HistoryEditor().apply {
                date = saved[0] as String
                weight = saved[1] as String
                editing = saved[2] as Boolean
                deleting = saved[3] as Boolean
            }
        },
    ),
) { HistoryEditor() }

@Composable
internal fun HistoryDialogs(
    editor: HistoryEditor,
    records: List<WeightRecord>,
    setValue: (String, Double?) -> String?,
) {
    val activeRecord = records.firstOrNull { it.date.toString() == editor.date }
    val save = {
        val value = editor.weight.trim().replace(',', '.').toDoubleOrNull()
        editor.error = if (value == null || value !in 1.0..1000.0) {
            "请输入 1–1000 kg 的体重。"
        } else {
            setValue(editor.date, value)
        }
        if (editor.error == null) editor.editing = false
    }
    val backdrop = rememberCouiWindowBackdrop()
    val material = if (CouiDialogDefaults.isMaterialSupported() && backdrop != null) {
        CouiDialogDefaults.material(CouiTheme.isDark)
    } else null
    Dialog(
        visible = editor.editing,
        onDismissRequest = { editor.editing = false },
        confirmAction = CouiDialogAction("保存", save),
        dismissAction = CouiDialogAction("取消", { editor.editing = false }),
        material = material,
        backdrop = backdrop,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = CouiDialogDefaults.buttonEdgePadding)
                    .padding(top = 8.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (activeRecord == null) "补记体重" else "修改体重",
                            Modifier.weight(2f).semantics { heading() },
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            if (activeRecord != null) {
                                TextButton(onClick = {
                                    editor.editing = false
                                    editor.deleting = true
                                    editor.error = null
                                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                    SupportingText(editor.date)
                }
                DecimalField(
                    input = editor.weight,
                    label = "体重",
                    unit = "kg",
                    onChange = { editor.weight = it; editor.error = null },
                    onDone = save,
                    error = editor.error,
                    shape = RoundedCornerShape(12.dp),
                )
            }
        },
    )
    Dialog(
        visible = editor.deleting,
        onDismissRequest = { editor.deleting = false },
        title = "删除体重记录？",
        message = editor.error ?: "${editor.date} 的记录将从日记中删除。",
        confirmAction = CouiDialogAction("删除", {
            editor.error = setValue(editor.date, null)
            if (editor.error == null) editor.deleting = false
        }),
        dismissAction = CouiDialogAction("取消", { editor.deleting = false }),
        material = material,
        backdrop = backdrop,
    )
}

@Composable
private fun HistoryRow(record: WeightRecord, height: Double?, onEdit: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceBright,
        contentColor = colors.onSurface,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 68.dp)
                .clickable(onClickLabel = "修改记录", onClick = onEdit)
                .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(record.date.toString(), style = MaterialTheme.typography.bodyMedium)
                height?.let { SupportingText("BMI ${formatNumber(bmi(record.kg, it), "0.0")}") }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${formatNumber(record.kg)} kg", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                record.delta?.let { Text("${formatChange(it)} kg", style = MaterialTheme.typography.labelMedium, color = changeColor(it)) }
            }
            Icon(CouiIcons.Outlined.Next, null, tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun CalendarPanel(
    month: YearMonth,
    today: LocalDate,
    records: List<WeightRecord>,
    onPreviousMonth: (() -> Unit)?,
    onNextMonth: (() -> Unit)?,
    onPickMonth: () -> Unit,
    onDay: (LocalDate, WeightRecord?) -> Unit,
) {
    val weights = records.filter { YearMonth.from(it.date) == month }.associateBy { it.date }
    val first = month.atDay(1)
    val skip = first.dayOfWeek.value - 1
    val weeks = (skip + month.lengthOfMonth() + 6) / 7
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onPreviousMonth?.invoke() }, enabled = onPreviousMonth != null) {
                Icon(CouiIcons.Outlined.Next, "上个月", modifier = Modifier.scale(scaleX = -1f, scaleY = 1f))
            }
            TextButton(onClick = onPickMonth) {
                Text(
                    "${month.year} 年 ${month.monthValue} 月",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.size(4.dp))
                Icon(CouiIcons.Outlined.Popup, null, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = { onNextMonth?.invoke() }, enabled = onNextMonth != null) {
                Icon(CouiIcons.Outlined.Next, "下个月")
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            val weekGap = 3.dp
            val cellHeight = ((maxHeight - weekGap * (weeks - 1)) / weeks).coerceIn(0.dp, 64.dp)
            Column(verticalArrangement = Arrangement.spacedBy(weekGap)) {
                repeat(weeks) { week ->
                    Row(Modifier.fillMaxWidth()) {
                        repeat(7) { weekday ->
                            val number = week * 7 + weekday - skip + 1
                            if (number in 1..month.lengthOfMonth()) {
                                val date = month.atDay(number)
                                DayCell(Modifier.weight(1f), cellHeight, date, today, weights[date], onDay)
                            } else {
                                Spacer(Modifier.weight(1f).height(cellHeight))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    modifier: Modifier,
    height: Dp,
    date: LocalDate,
    today: LocalDate,
    record: WeightRecord?,
    onSelect: (LocalDate, WeightRecord?) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val compact = height < 60.dp
    val description = buildString {
        append(date)
        if (date == today) append("，今天")
        if (record != null) {
            append("，${formatNumber(record.kg)} 公斤")
            record.delta?.let { append("，较上次 ${formatChange(it)} 公斤") }
        }
    }
    Column(
        modifier.height(height).padding(horizontal = 1.dp).clip(RoundedCornerShape(8.dp))
            .background(if (date == today) colors.primaryContainer else Color.Transparent)
            .clickable(enabled = date <= today, onClickLabel = if (record == null) "补记体重" else "修改记录") { onSelect(date, record) }
            .semantics { contentDescription = description }
            .padding(vertical = if (compact) 1.dp else 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            date.dayOfMonth.toString(),
            modifier = Modifier.clearAndSetSemantics { },
            style = MaterialTheme.typography.labelSmall,
            lineHeight = if (compact) 13.sp else 16.sp,
            fontWeight = FontWeight.Normal,
            color = if (date > today) colors.onSurfaceVariant.copy(alpha = 0.66f) else colors.onSurfaceVariant,
        )
        if (record != null) {
            Spacer(Modifier.height(if (compact) 1.dp else 3.dp))
            Text(
                formatNumber(record.kg), Modifier.clearAndSetSemantics { },
                textAlign = TextAlign.Center,
                color = colors.onPrimaryContainer,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 16.sp, stepSize = 0.5.sp),
                lineHeight = if (compact) 16.sp else 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            record.delta?.let {
                Text(formatChange(it), Modifier.clearAndSetSemantics { }, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, lineHeight = if (compact) 10.sp else 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = changeColor(it))
            }
        }
    }
}
