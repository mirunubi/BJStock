package com.mirunubi.bjstock.feature.performance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.ui.icons.BJStockIcons
import com.mirunubi.bjstock.ui.navigation.BJStockBottomBar
import com.mirunubi.bjstock.ui.navigation.PrimaryTab
import com.mirunubi.bjstock.ui.navigation.TabTopBar

@Composable
fun PerformanceScreen(
    onSelectTab: (PrimaryTab) -> Unit,
    viewModel: PerformanceViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TabTopBar(title = PrimaryTab.PERFORMANCE.label) },
        bottomBar = { BJStockBottomBar(selected = PrimaryTab.PERFORMANCE, onSelect = onSelectTab) },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            ScrollColumn {
                if (state.comparison.open) {
                    ComparisonSection(state, viewModel)
                } else {
                    RunSelectorSection(state, viewModel)
                    when (val detail = state.detail) {
                        PerformanceDetailState.None -> Unit
                        PerformanceDetailState.Loading -> LoadingLine("불러오는 중")
                        is PerformanceDetailState.Failed -> {
                            WarningLine(detail.message)
                            OutlinedButton(onClick = viewModel::refresh) { Text("다시 시도") }
                        }
                        is PerformanceDetailState.Loaded -> Detail(detail.view)
                    }
                }
            }
        }
    }
}

// region Run selector

@Composable
private fun RunSelectorSection(state: PerformanceUiState, viewModel: PerformanceViewModel) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("모의투자 선택", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        val canCompare = ((state.runs as? PerformanceRunsState.Loaded)?.rows?.size ?: 0) >= PerformancePresenter.MIN_COMPARE
        OutlinedButton(onClick = viewModel::openComparison, enabled = canCompare, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("모의투자 비교")
        }
    }
    when (val runs = state.runs) {
        PerformanceRunsState.Loading -> LoadingLine("불러오는 중")
        is PerformanceRunsState.Failed -> {
            WarningLine(runs.message)
            OutlinedButton(onClick = viewModel::refresh) { Text("다시 시도") }
        }
        is PerformanceRunsState.Loaded ->
            if (runs.rows.isEmpty()) {
                BodyText(PerformancePresenter.RUNS_EMPTY)
            } else {
                runs.rows.forEach { row ->
                    RunRowItem(row, selected = row.runId == state.selectedRunId) { viewModel.selectRun(row.runId) }
                }
            }
    }
}

@Composable
private fun RunRowItem(row: PerformanceRunRow, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onClick),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(row.strategyLabel, style = MaterialTheme.typography.bodyLarge)
                RunStatusPill(row.badge)
                Text(row.period, style = MaterialTheme.typography.bodyMedium)
                Text(row.assetSummary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            if (selected) {
                Icon(BJStockIcons.CheckCircle, contentDescription = "선택됨")
            } else {
                Icon(BJStockIcons.ChevronRight, contentDescription = null)
            }
        }
    }
}

// endregion

// region Detail

@Composable
private fun Detail(view: PerformanceDetailView) {
    HeaderCard(view.header)
    when (view) {
        is PerformanceDetailView.DataError -> DataErrorCard(view)
        is PerformanceDetailView.Ready -> {
            MetricsCard(view.metrics)
            EquityCard(view.equity, view.daily)
            MonthlyCard(view.monthly)
            TradesCard(view.trades)
            SignalsCard(view.signals)
            SectionCard("보유 현황") {
                BodyText(view.positionLine)
                SmallText(PerformancePresenter.POSITIONS_NOTE)
            }
        }
    }
}

@Composable
private fun HeaderCard(header: PerformanceHeader) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(header.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            RunStatusPill(header.badge, large = true)
            LabeledRow("전략", header.strategyLabel)
            LabeledRow("성과 상태", header.statusLabel)
            LabeledRow("분석기간", header.period)
            LabeledRow("거래일 수", header.tradingDays)
        }
    }
}

@Composable
private fun DataErrorCard(view: PerformanceDetailView.DataError) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(BJStockIcons.Warning, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(view.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(view.guidance, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun MetricsCard(metrics: KeyMetricsView) {
    SectionCard("핵심 성과") {
        when (metrics) {
            is KeyMetricsView.NotValued -> {
                InfoBox(BJStockIcons.Performance, metrics.message)
                LabeledRow("초기자금", metrics.initialCash)
            }
            is KeyMetricsView.Measured -> {
                Text("총 모의자산", style = MaterialTheme.typography.bodyLarge)
                Text(metrics.totalAsset, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                LabeledRow("누적 손익", metrics.cumulativeProfit)
                LabeledRow("누적 수익률", metrics.cumulativeReturn)
                LabeledRow("최대 낙폭(MDD)", metrics.maxDrawdown)
                LabeledRow("CAGR(연환산)", metrics.cagr)
                metrics.cagrNote?.let { SmallText(it) }
            }
        }
    }
}

@Composable
private fun EquityCard(equity: EquityView?, daily: List<DailyRowView>) {
    SectionCard("자산 추이") {
        if (equity == null) {
            BodyText(PerformancePresenter.EQUITY_EMPTY)
        } else {
            EquityDetails(equity, daily)
        }
    }
}

@Composable
private fun EquityDetails(equity: EquityView, daily: List<DailyRowView>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EquityChart(equity)
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                SmallText("시작 ${equity.startDate}")
                Text(equity.firstAsset, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                SmallText("최근 ${equity.endDate} ●")
                Text(equity.latestAsset, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            }
        }
        SmallText("기간 중 최고 ${equity.highAsset} · 최저 ${equity.lowAsset}")
        if (daily.isNotEmpty()) {
            HorizontalDivider()
            SubTitle("최근 일별 기록")
            daily.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(row.date, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(row.totalAsset, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    SmallText("일 손익 ${row.dailyProfit} (${row.dailyReturn}) · 누적 ${row.cumulativeReturn} · 낙폭 ${row.drawdown}")
                }
            }
        }
    }
}

/** Plain line through the actual snapshot points, in date order; the latest point is marked. */
@Composable
private fun EquityChart(view: EquityView) {
    val lineColor = MaterialTheme.colorScheme.primary
    val baseColor = MaterialTheme.colorScheme.outlineVariant
    val markerInner = MaterialTheme.colorScheme.surface
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .semantics { contentDescription = view.description },
    ) {
        val pad = 10.dp.toPx()
        val width = size.width - pad * 2
        val height = size.height - pad * 2
        val count = view.fractions.size
        fun x(index: Int): Float = pad + if (count == 1) width / 2 else width * index / (count - 1)
        fun y(fraction: Float): Float = pad + height * (1f - fraction)

        drawLine(baseColor, Offset(pad, pad + height), Offset(pad + width, pad + height), strokeWidth = 1.dp.toPx())
        if (count > 1) {
            val path = Path().apply {
                moveTo(x(0), y(view.fractions[0]))
                for (index in 1 until count) lineTo(x(index), y(view.fractions[index]))
            }
            drawPath(path, lineColor, style = Stroke(width = 3.dp.toPx()))
        }
        val latest = Offset(x(count - 1), y(view.fractions.last()))
        drawCircle(lineColor, radius = 7.dp.toPx(), center = latest)
        drawCircle(markerInner, radius = 3.dp.toPx(), center = latest)
    }
}

@Composable
private fun MonthlyCard(monthly: List<MonthlyRowView>) {
    SectionCard("월간 수익률") {
        if (monthly.isEmpty()) BodyText(PerformancePresenter.MONTHLY_EMPTY)
        monthly.forEach { row ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(row.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    row.returnRate,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = directionColor(row.direction),
                )
            }
            SmallText("월말 자산 ${row.endAsset}")
        }
    }
}

@Composable
private fun TradesCard(trades: TradeStatsView) {
    SectionCard("거래 통계") {
        trades.rows.forEach { LabeledRow(it.label, it.value) }
        trades.winRateNote?.let { SmallText(it) }
        SmallText(trades.note)
    }
}

@Composable
private fun SignalsCard(signals: SignalStatsView) {
    SectionCard("전략 판단 · 가상 체결") {
        SubTitle("전략 판단")
        signals.decisions.forEach { LabeledRow(it.label, it.value) }
        HorizontalDivider()
        SubTitle("가상 체결")
        signals.executions.forEach { LabeledRow(it.label, it.value) }
        SmallText(signals.note)
    }
}

// endregion

// region Comparison

@Composable
private fun ComparisonSection(state: PerformanceUiState, viewModel: PerformanceViewModel) {
    val comparison = state.comparison
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("모의투자 비교", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TextButton(onClick = viewModel::closeComparison, modifier = Modifier.heightIn(min = 48.dp)) { Text("닫기") }
    }
    BodyText("비교할 모의투자를 2~3개 선택해 주세요. (${comparison.selected.size}/${PerformancePresenter.MAX_COMPARE})")
    val rows = (state.runs as? PerformanceRunsState.Loaded)?.rows.orEmpty()
    rows.forEach { row ->
        val checked = row.runId in comparison.selected
        Card(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { viewModel.toggleComparisonRun(row.runId) }) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = { viewModel.toggleComparisonRun(row.runId) })
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(row.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(row.strategyLabel, style = MaterialTheme.typography.bodyMedium)
                }
                RunStatusPill(row.badge)
            }
        }
    }
    comparison.message?.let { WarningLine(it) }
    val selectedCount = comparison.selected.size
    Button(
        onClick = viewModel::compare,
        enabled = selectedCount in PerformancePresenter.MIN_COMPARE..PerformancePresenter.MAX_COMPARE &&
            comparison.result != ComparisonResult.Loading,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text("비교하기")
    }
    when (val result = comparison.result) {
        ComparisonResult.None -> Unit
        ComparisonResult.Loading -> LoadingLine("불러오는 중")
        is ComparisonResult.Failed -> WarningLine(result.message)
        is ComparisonResult.Loaded -> {
            InfoBox(BJStockIcons.Warning, result.view.note)
            result.view.columns.forEach { ComparisonCard(it) }
        }
    }
}

@Composable
private fun ComparisonCard(column: ComparisonColumn) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(column.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(column.strategyLabel, style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                column.badge?.let {
                    RunStatusPill(it)
                    Spacer(Modifier.width(8.dp))
                }
                Text(column.statusLabel, style = MaterialTheme.typography.bodyMedium)
            }
            column.warning?.let { WarningLine(it) }
            column.metrics.forEach { LabeledRow(it.label, it.value) }
            HorizontalDivider()
            SubTitle("거래 정책")
            column.policy.forEach { LabeledRow(it.label, it.value) }
        }
    }
}

// endregion

// region Shared pieces

@Composable
private fun RunStatusPill(badge: PerformanceRunBadge, large: Boolean = false) {
    val (icon, color) = when (badge.status) {
        RunStatus.RUNNING -> BJStockIcons.PaperTrading to MaterialTheme.colorScheme.primaryContainer
        RunStatus.READY -> BJStockIcons.Lock to MaterialTheme.colorScheme.secondaryContainer
        RunStatus.PAUSED -> BJStockIcons.Warning to MaterialTheme.colorScheme.tertiaryContainer
        RunStatus.DRAFT -> BJStockIcons.Edit to MaterialTheme.colorScheme.tertiaryContainer
        RunStatus.COMPLETED -> BJStockIcons.CheckCircle to MaterialTheme.colorScheme.surfaceVariant
        RunStatus.CANCELLED -> BJStockIcons.Close to MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(shape = MaterialTheme.shapes.small, color = color) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = if (large) 6.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(if (large) 22.dp else 18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                badge.label,
                style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Secondary cue only; the sign is always written in text. */
@Composable
private fun directionColor(direction: ReturnDirection): Color = when (direction) {
    ReturnDirection.UP -> Color(0xFFC62828)
    ReturnDirection.DOWN -> Color(0xFF1565C0)
    ReturnDirection.FLAT -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun InfoBox(icon: ImageVector, text: String) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
private fun SubTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun BodyText(message: String) {
    Text(message, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun SmallText(message: String) {
    Text(message, style = MaterialTheme.typography.bodySmall)
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

// endregion
