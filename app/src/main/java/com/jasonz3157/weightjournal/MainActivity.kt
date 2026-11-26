package com.jasonz3157.weightjournal

import android.app.Application
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.itos.coui.effect.CouiFloatingRenderer
import com.itos.coui.icons.CouiIcons
import com.itos.coui.layout.Scaffold
import com.itos.coui.material3.CouiMaterial3Style
import com.itos.coui.menu.ProvideCouiMenuBackdrop
import com.itos.coui.navigation.CouiNavigationItem
import com.itos.coui.navigation.NavigationBar
import com.itos.coui.segmentbutton.CouiSegmentedControlDefaults
import com.itos.coui.segmentbutton.SegmentedControl
import com.itos.coui.theme.Coui
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

class JournalApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Coui.initialize(defaultStyle = CouiMaterial3Style())
    }
}

class MainActivity : ComponentActivity() {
    private val model by viewModels<JournalViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val state by model.ui.collectAsStateWithLifecycle()
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) NightColors else DayColors) {
                JournalApp(state, model)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.refreshToday()
                model.ui.first { !it.loading }
                model.syncOnOpen()
                while (isActive) {
                    delay(30_000)
                    model.refreshToday()
                    model.syncInBackground()
                }
            }
        }
    }
}

private val DayColors = lightColorScheme(
    primary = Color(0xFF216A51), onPrimary = Color.White,
    primaryContainer = Color(0xFFD9F2E4), onPrimaryContainer = Color(0xFF124532),
    background = Color(0xFFEFF3EF), onBackground = Color(0xFF16261F),
    surface = Color(0xFFF7F9F6), surfaceBright = Color.White,
    onSurface = Color(0xFF17261F), onSurfaceVariant = Color(0xFF52625A),
    surfaceVariant = Color(0xFFE6EDE7), outline = Color(0xFF6D7D73),
    outlineVariant = Color(0xFFD5DFD7), error = Color(0xFFAB373A),
)

private val NightColors = darkColorScheme(
    primary = Color(0xFF8ED7B1), onPrimary = Color(0xFF083824),
    primaryContainer = Color(0xFF244837), onPrimaryContainer = Color(0xFFB7EBCB),
    background = Color(0xFF111412), onBackground = Color(0xFFEAF0EB),
    surface = Color(0xFF1B201D), surfaceBright = Color(0xFF202622),
    onSurface = Color(0xFFEAF0EB), onSurfaceVariant = Color(0xFFB1C0B5),
    surfaceVariant = Color(0xFF303A33), outline = Color(0xFF98A69B),
    outlineVariant = Color(0xFF3B4840), error = Color(0xFFFFB4B3),
)

@Composable
private fun JournalApp(state: JournalUiState, model: JournalViewModel) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val pageState = rememberSaveableStateHolder()
    val historyEditor = rememberHistoryEditor()
    val records = remember(state.journal, state.today) { state.journal.records(state.today) }
    BackHandler(page != 0) { page = 0 }
    ProvideCouiMenuBackdrop { windowSource ->
        Scaffold(
            modifier = Modifier.fillMaxSize().then(windowSource)
                .then(if (LocalWindowInfo.current.isWindowFocused) Modifier.imePadding() else Modifier),
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = { JournalNavigation(page) { page = it } },
        ) { padding ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                if (state.loading) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else {
                    Box(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                        pageState.SaveableStateProvider(page) {
                            when (page) {
                                0 -> RecordScreen(padding, state, records, { page = 2 }, model::setValue, model::syncManually)
                                1 -> HistoryScreen(padding, state, records, historyEditor::open)
                                2 -> SettingsScreen(padding, state, model)
                            }
                        }
                    }
                }
            }
        }
        HistoryDialogs(historyEditor, records, model::setValue)
    }
}

@Composable
private fun JournalNavigation(selected: Int, onSelect: (Int) -> Unit) {
    val items = listOf(
        CouiNavigationItem("记录", CouiIcons.Outlined.Pen, CouiIcons.Filled.Pen),
        CouiNavigationItem(
            "历史",
            ImageVector.vectorResource(R.drawable.ic_history_calendar),
            ImageVector.vectorResource(R.drawable.ic_history_calendar_filled),
        ),
        CouiNavigationItem("设置", CouiIcons.Outlined.Setting, CouiIcons.Filled.Setting),
    )
    if (Build.VERSION.SDK_INT >= 34) {
        val renderer = remember { runCatching { CouiFloatingRenderer() }.getOrNull() }
        if (renderer != null) {
            NavigationBar(items = items, selectedIndex = selected, onSelection = onSelect, renderer = renderer)
            return
        }
    }
    androidx.compose.material3.NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        items.forEachIndexed { index, item ->
            NavigationBarItem(
                selected = selected == index, onClick = { onSelect(index) },
                icon = { Icon(if (selected == index) item.selectedIcon else item.icon, null) },
                label = { Text(item.title) },
            )
        }
    }
}

internal val FirstCardTopPadding = 10.dp

@Composable
internal fun Panel(
    decoration: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceBright,
    ) {
        Box {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
            decoration()
        }
    }
}

@Composable
internal fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title, style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold, modifier = modifier.semantics { heading() },
    )
}

@Composable
internal fun SupportingText(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Text(
        text, modifier = modifier, style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun DecimalField(
    input: String,
    label: String,
    unit: String,
    onChange: (String) -> Unit,
    onDone: () -> Unit,
    error: String? = null,
    shape: Shape = OutlinedTextFieldDefaults.shape,
) {
    OutlinedTextField(
        value = input, onValueChange = onChange, modifier = Modifier.fillMaxWidth(),
        label = { Text(label) }, suffix = { Text(unit) }, singleLine = true,
        shape = shape,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
    )
}

@Composable
internal fun Segments(labels: List<String>, selected: Int, fitLabels: Boolean = false, onSelect: (Int) -> Unit) {
    if (Build.VERSION.SDK_INT >= 34) {
        val control: @Composable (Modifier) -> Unit = { modifier ->
            SegmentedControl(
                modifier = modifier, labels = labels,
                selectedIndex = selected, onSelected = onSelect, commitOnRelease = fitLabels,
            )
        }
        if (fitLabels) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                control(Modifier.horizontalScroll(rememberScrollState()).widthIn(min = maxWidth))
            }
        } else {
            control(Modifier.fillMaxWidth())
        }
    } else {
        val dark = isSystemInDarkTheme()
        val shape = RoundedCornerShape(percent = 50)
        val background = CouiSegmentedControlDefaults.containerColor(dark)
        val indicator = CouiSegmentedControlDefaults.indicatorColor(dark)
        Row(Modifier.fillMaxWidth().clip(shape).background(background).selectableGroup().padding(4.dp)) {
            labels.forEachIndexed { index, label ->
                val active = selected == index
                Box(
                    modifier = Modifier.weight(1f).heightIn(min = 32.dp).clip(shape)
                        .background(if (active) indicator else Color.Transparent)
                        .selectable(selected = active, role = Role.Tab, onClick = { onSelect(index) })
                        .padding(horizontal = 2.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
                        autoSize = if (fitLabels) TextAutoSize.StepBased(10.sp, 14.sp, 0.5.sp) else null,
                        maxLines = if (fitLabels) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

internal fun formatNumber(number: Double, pattern: String = "0.##"): String =
    DecimalFormat(pattern, DecimalFormatSymbols(Locale.SIMPLIFIED_CHINESE)).format(number)

internal fun formatChange(delta: Double): String = formatNumber(delta, "+0.##;−0.##").let {
    if (it == "+0" || it == "−0") "0" else it
}

@Composable
internal fun changeColor(delta: Double): Color = when {
    delta >= 0.005 -> MaterialTheme.colorScheme.error
    delta <= -0.005 -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
