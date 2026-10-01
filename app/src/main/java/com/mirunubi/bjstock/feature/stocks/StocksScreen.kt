package com.mirunubi.bjstock.feature.stocks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mirunubi.bjstock.ui.icons.BJStockIcons
import com.mirunubi.bjstock.ui.navigation.BJStockBottomBar
import com.mirunubi.bjstock.ui.navigation.PrimaryTab
import com.mirunubi.bjstock.ui.navigation.TabTopBar

@Composable
fun StocksScreen(
    onSelectTab: (PrimaryTab) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenThemeManagement: () -> Unit,
    viewModel: StocksViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshThemes() }
    val layered = state.detail != null || state.themeBrowse != null
    BackHandler(enabled = layered) { viewModel.back() }

    Scaffold(
        topBar = {
            TabTopBar(
                title = PrimaryTab.STOCKS.label,
                onOpenSettings = onOpenSettings,
                onBack = if (layered) ({ viewModel.back() }) else null,
            )
        },
        bottomBar = { BJStockBottomBar(selected = PrimaryTab.STOCKS, onSelect = onSelectTab) },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            val detail = state.detail
            val themeBrowse = state.themeBrowse
            when {
                detail != null -> DetailContent(
                    detail = detail,
                    onRetry = viewModel::retryDetail,
                    onRequestQuote = viewModel::requestCurrentPrice,
                    onOpenThemeManagement = onOpenThemeManagement,
                )
                themeBrowse != null -> ThemeMembersContent(
                    browse = themeBrowse,
                    onSelect = viewModel::select,
                    onOpenThemeManagement = onOpenThemeManagement,
                )
                else -> SearchContent(
                    state = state,
                    onQueryChange = viewModel::onQueryChange,
                    onSubmit = viewModel::submitSearch,
                    onClear = viewModel::clearQuery,
                    onSelect = viewModel::select,
                    onOpenTheme = viewModel::openTheme,
                    onOpenThemeManagement = onOpenThemeManagement,
                )
            }
        }
    }
}

@Composable
private fun SearchContent(
    state: StocksUiState,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onSelect: (InstrumentRow) -> Unit,
    onOpenTheme: (ThemeChip) -> Unit,
    onOpenThemeManagement: () -> Unit,
) {
    ScrollColumn {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium,
            placeholder = { Text("종목명 또는 종목코드 검색", style = MaterialTheme.typography.titleMedium) },
            leadingIcon = { Icon(BJStockIcons.Stocks, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = onClear) { Icon(BJStockIcons.Close, contentDescription = "검색어 지우기") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        )
        when (val search = state.search) {
            SearchState.Idle -> {
                BodyText(StocksPresenter.GUIDE)
                if (state.themes.isNotEmpty()) {
                    SectionCard(title = "테마로 찾기") {
                        ChipRow(state.themes, onClick = onOpenTheme)
                    }
                }
                TextButton(onClick = onOpenThemeManagement) { Text("테마 관리", style = MaterialTheme.typography.titleSmall) }
            }
            SearchState.Searching -> LoadingLine("검색 중")
            SearchState.NoResults -> BodyText(StocksPresenter.NO_RESULTS)
            is SearchState.Failed -> WarningLine(search.message)
            is SearchState.Results -> search.rows.forEach { row -> InstrumentRowCard(row, onSelect) }
        }
    }
}

@Composable
private fun ThemeMembersContent(
    browse: ThemeBrowse,
    onSelect: (InstrumentRow) -> Unit,
    onOpenThemeManagement: () -> Unit,
) {
    ScrollColumn {
        Text(browse.theme.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("테마 종목", style = MaterialTheme.typography.titleSmall)
        when (val members = browse.members) {
            MembersState.Loading -> LoadingLine("불러오는 중")
            MembersState.Empty -> BodyText(StocksPresenter.MEMBERS_NONE)
            is MembersState.Failed -> WarningLine(members.message)
            is MembersState.Loaded -> members.rows.forEach { row -> InstrumentRowCard(row, onSelect) }
        }
        TextButton(onClick = onOpenThemeManagement) { Text("테마 관리", style = MaterialTheme.typography.titleSmall) }
    }
}

@Composable
private fun DetailContent(
    detail: DetailState,
    onRetry: () -> Unit,
    onRequestQuote: () -> Unit,
    onOpenThemeManagement: () -> Unit,
) {
    ScrollColumn {
        HeaderBlock(detail.header)
        when (detail) {
            is DetailState.Loading -> LoadingLine("불러오는 중")
            is DetailState.Failed -> {
                WarningLine(detail.message)
                Button(onClick = onRetry) { Text("다시 시도") }
            }
            is DetailState.Content -> {
                PriceCard(detail.price)
                ChartCard(detail.chart)
                QuoteCardSection(detail.quote, onRequestQuote)
                FactorCard(detail.factors)
                ThemesCard(detail.themes, onOpenThemeManagement)
            }
        }
    }
}

@Composable
private fun HeaderBlock(header: StockHeader) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(header.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(header.subtitle, style = MaterialTheme.typography.titleMedium)
        header.metadata.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
private fun PriceCard(price: PriceSummary?) {
    SectionCard(title = "저장 일봉 기준") {
        if (price == null) {
            BodyText(StocksPresenter.NO_BARS)
            return@SectionCard
        }
        Text(price.close, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("전일 대비 ", style = MaterialTheme.typography.titleMedium)
            Text(
                listOfNotNull(price.change, directionWord(price.direction)).joinToString(" "),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = directionColor(price.direction),
            )
        }
        LabeledRow("거래일", price.tradeDate)
        LabeledRow("거래량", price.volume)
    }
}

@Composable
private fun ChartCard(chart: ChartState) {
    SectionCard(title = "가격 차트") {
        when (chart) {
            is ChartState.Empty -> BodyText(chart.message)
            is ChartState.Line -> {
                Text(chart.caption, style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(chart.high, style = MaterialTheme.typography.bodyMedium)
                    Text("최근 ${chart.latest}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
                PriceLineChart(chart.points)
                Text(chart.low, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(chart.startDate, style = MaterialTheme.typography.bodyMedium)
                    Text(chart.endDate, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Straight segments between stored closes in date order; no interpolation, the latest close is marked. */
@Composable
private fun PriceLineChart(points: List<ChartPoint>) {
    val lineColor = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
    ) {
        if (points.size < 2) return@Canvas
        val closes = points.map { it.closePrice }
        val min = closes.min()
        val max = closes.max()
        val pad = 10.dp.toPx()
        val usableHeight = size.height - 2 * pad
        val stepX = size.width / (points.size - 1)
        fun y(close: Long): Float =
            if (max == min) size.height / 2 else pad + usableHeight * (1f - (close - min).toFloat() / (max - min).toFloat())

        drawLine(gridColor, Offset(0f, pad), Offset(size.width, pad), strokeWidth = 1.dp.toPx())
        drawLine(gridColor, Offset(0f, size.height - pad), Offset(size.width, size.height - pad), strokeWidth = 1.dp.toPx())
        val path = Path()
        closes.forEachIndexed { index, close ->
            val x = index * stepX
            if (index == 0) path.moveTo(x, y(close)) else path.lineTo(x, y(close))
        }
        drawPath(path, lineColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(lineColor, radius = 6.dp.toPx(), center = Offset(size.width, y(closes.last())))
    }
}

@Composable
private fun QuoteCardSection(quote: QuoteState, onRequestQuote: () -> Unit) {
    SectionCard(title = "현재가 조회") {
        Text("버튼을 누를 때만 KIS에서 현재가를 조회합니다.", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            onClick = onRequestQuote,
            enabled = quote != QuoteState.Loading,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(if (quote is QuoteState.Loaded) "다시 조회" else "현재가 조회", style = MaterialTheme.typography.titleSmall)
        }
        when (quote) {
            QuoteState.Idle -> Unit
            QuoteState.Loading -> LoadingLine("현재가 조회 중")
            is QuoteState.Failed -> WarningLine(quote.message)
            is QuoteState.Loaded -> {
                val card = quote.card
                Text("현재가 조회 결과", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(card.price, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull("전일 대비 ${card.change}", directionWord(card.direction)).joinToString(" "),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = directionColor(card.direction),
                )
                LabeledRow("시가", card.open)
                LabeledRow("고가", card.high)
                LabeledRow("저가", card.low)
                LabeledRow("거래량", card.volume)
                card.businessDate?.let { LabeledRow("기준일", it) }
                Text(card.fetchedAt, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun FactorCard(panel: FactorPanel) {
    SectionCard(title = "팩터") {
        when (panel) {
            is FactorPanel.Unavailable -> BodyText(panel.message)
            is FactorPanel.Rows -> {
                Text(panel.asOf, style = MaterialTheme.typography.bodyMedium)
                panel.rows.forEach { row -> FactorRowItem(row) }
            }
        }
    }
}

@Composable
private fun FactorRowItem(row: FactorRow) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(row.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            row.score?.let { Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(row.code, style = MaterialTheme.typography.bodySmall)
            row.rawValue?.let { Text(it, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.End) }
        }
        row.unavailable?.let { BodyText(it) }
    }
}

@Composable
private fun ThemesCard(section: ThemesSection, onOpenThemeManagement: () -> Unit) {
    SectionCard(title = "테마") {
        when (section) {
            is ThemesSection.Failed -> WarningLine(section.message)
            is ThemesSection.Loaded ->
                if (section.chips.isEmpty()) BodyText(StocksPresenter.THEMES_NONE) else ThemeBadges(section.chips)
        }
        TextButton(onClick = onOpenThemeManagement) { Text("테마 관리", style = MaterialTheme.typography.titleSmall) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeBadges(chips: List<ThemeChip>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { chip ->
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    chip.name,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(chips: List<ThemeChip>, onClick: (ThemeChip) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { chip ->
            AssistChip(
                onClick = { onClick(chip) },
                label = { Text(chip.name, style = MaterialTheme.typography.titleSmall) },
                modifier = Modifier.heightIn(min = 40.dp),
            )
        }
    }
}

@Composable
private fun InstrumentRowCard(row: InstrumentRow, onSelect: (InstrumentRow) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable { onSelect(row) },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(row.subtitle, style = MaterialTheme.typography.bodyLarge)
            }
            Icon(BJStockIcons.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun ScrollColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}

@Composable
private fun LabeledRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
    }
}

@Composable
private fun BodyText(message: String) {
    Text(message, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun LoadingLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 3.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun WarningLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(BJStockIcons.Warning, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

private fun directionWord(direction: PriceDirection): String? = when (direction) {
    PriceDirection.UP -> "상승"
    PriceDirection.DOWN -> "하락"
    PriceDirection.FLAT -> "보합"
    PriceDirection.UNKNOWN -> null
}

@Composable
private fun directionColor(direction: PriceDirection): Color = when (direction) {
    PriceDirection.UP -> Color(0xFFC62828)
    PriceDirection.DOWN -> Color(0xFF1565C0)
    PriceDirection.FLAT, PriceDirection.UNKNOWN -> MaterialTheme.colorScheme.onSurface
}
