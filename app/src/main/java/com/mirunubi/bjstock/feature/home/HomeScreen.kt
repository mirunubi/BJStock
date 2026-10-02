package com.mirunubi.bjstock.feature.home

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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mirunubi.bjstock.feature.admin.SectionState
import com.mirunubi.bjstock.ui.icons.BJStockIcons
import com.mirunubi.bjstock.ui.navigation.BJStockBottomBar
import com.mirunubi.bjstock.ui.navigation.PrimaryTab
import com.mirunubi.bjstock.ui.navigation.TabTopBar

@Composable
fun HomeScreen(
    onSelectTab: (PrimaryTab) -> Unit,
    onOpenAdmin: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
    activityViewModel: HomeActivityViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activity by activityViewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.refresh()
        activityViewModel.refresh()
    }
    val openLink: (HomeDestination) -> Unit = { destination ->
        when (destination) {
            HomeDestination.PAPER_TRADING -> onSelectTab(PrimaryTab.PAPER_TRADING)
            HomeDestination.ADMIN -> onOpenAdmin()
        }
    }

    Scaffold(
        topBar = { TabTopBar(title = "BJStock") },
        bottomBar = { BJStockBottomBar(selected = PrimaryTab.HOME, onSelect = onSelectTab) },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            HomeContent(
                core = state,
                activity = activity,
                onRetryCore = viewModel::refresh,
                onRetryActivity = activityViewModel::refresh,
                openLink = openLink,
            )
        }
    }
}

/** The core cards and the activity summaries load independently; neither failure blanks the other. */
@Composable
private fun HomeContent(
    core: HomeUiState,
    activity: HomeActivityState,
    onRetryCore: () -> Unit,
    onRetryActivity: () -> Unit,
    openLink: (HomeDestination) -> Unit,
) {
    val content = core as? HomeUiState.Content
    val section: @Composable (HomeActivitySection) -> Unit = { which ->
        ActivitySection(which, activity.section(which), onRetryActivity, openLink)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when (core) {
            HomeUiState.Loading -> HomeCard(title = "모의자산") { LoadingLine("로딩 중") }
            is HomeUiState.Error -> HomeCard(title = "모의자산") {
                StatusLine(icon = true, isError = true, text = core.message)
                Text(HomePresenter.CORE_SECTIONS_UNAVAILABLE, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onRetryCore, modifier = Modifier.heightIn(min = 48.dp)) { Text("다시 시도") }
            }
            is HomeUiState.Content -> PortfolioSection(core.portfolio)
        }
        section(HomeActivitySection.RUNS)
        content?.let { DecisionSection(it.decision) }
        section(HomeActivitySection.SIGNALS)
        content?.let { HoldingsSection(it.holdings) }
        section(HomeActivitySection.TRADES)
        content?.let { AutoSection(it.auto) { openLink(HomeDestination.ADMIN) } }
        content?.let { AlertsSection(it.alerts) }
        section(HomeActivitySection.ERRORS)
        section(HomeActivitySection.AUDIT)
    }
}

@Composable
private fun ActivitySection(
    section: HomeActivitySection,
    state: SectionState<HomeSummary>,
    onRetry: () -> Unit,
    openLink: (HomeDestination) -> Unit,
) {
    HomeCard(title = section.title) {
        when (state) {
            SectionState.Loading -> LoadingLine("불러오는 중")
            is SectionState.Empty -> EmptyText(state.message)
            is SectionState.Failed -> {
                StatusLine(icon = true, isError = true, text = state.message)
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("다시 시도") }
            }
            is SectionState.Loaded -> SummaryBody(state.value)
        }
        section.link?.let { link -> DetailLink(link.label) { openLink(link.destination) } }
    }
}

@Composable
private fun SummaryBody(summary: HomeSummary) {
    summary.headline?.let { Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
    summary.rows.forEachIndexed { index, row ->
        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (row.problem) {
                Icon(BJStockIcons.Warning, contentDescription = "문제", modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(row.headline, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
        row.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
    summary.moreNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    summary.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun DetailLink(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(label)
        Spacer(Modifier.width(4.dp))
        Icon(BJStockIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun LoadingLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 3.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PortfolioSection(card: PortfolioCard) {
    HomeCard(title = "모의자산") {
        when (card) {
            PortfolioCard.NoRun -> EmptyText(HomePresenter.NO_RUN)
            is PortfolioCard.Summary -> {
                Text(card.totalAssetLabel, style = MaterialTheme.typography.titleSmall)
                Text(
                    card.totalAsset,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("누적수익률 ", style = MaterialTheme.typography.titleMedium)
                    Text(
                        card.cumulativeReturn,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                card.cumulativeProfit?.let { Text("누적손익 $it", style = MaterialTheme.typography.bodyLarge) }
                Spacer(Modifier.height(4.dp))
                Text("${card.runLabel} · ${card.runStatus}", style = MaterialTheme.typography.bodyMedium)
                card.otherRunsNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun DecisionSection(card: DecisionCard) {
    HomeCard(title = HomePresenter.DECISION_TITLE) {
        when (card) {
            is DecisionCard.Empty -> EmptyText(card.message)
            is DecisionCard.Latest -> {
                Text(card.instrument, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(card.decision, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text("(${card.canonicalDecision})", style = MaterialTheme.typography.bodyMedium)
                }
                LabeledRow("전략점수", card.score)
                LabeledRow("판단일", card.date)
                card.moreNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun HoldingsSection(card: HoldingsCard) {
    HomeCard(title = HomePresenter.HOLDINGS_TITLE) {
        when (card) {
            is HoldingsCard.Empty -> EmptyText(card.message)
            is HoldingsCard.Holdings -> {
                LabeledRow("보유 종목 수", "${card.count}종목")
                card.items.forEach { row ->
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text(row.instrument, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    LabeledRow("수량", row.quantity)
                    LabeledRow("평균단가", row.averagePrice)
                    LabeledRow("평가손익", row.unrealizedPnl)
                }
                card.moreNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun AutoSection(card: AutoCard, onOpenDetail: () -> Unit) {
    HomeCard(title = HomePresenter.AUTO_TITLE) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("자동운영", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(12.dp))
            Text(card.stateLabel, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        LabeledRow("다음 실행", card.nextRun)
        LabeledRow("최근 실행", card.latestOperation)
        DetailLink(HomeActivityPresenter.AUTO_LINK_LABEL, onOpenDetail)
    }
}

@Composable
private fun AlertsSection(alerts: List<HomeAlert>) {
    if (alerts.isEmpty()) {
        StatusLine(icon = true, isError = false, text = "운영 상태 정상")
        return
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                HomePresenter.ALERTS_TITLE,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            alerts.forEach { alert ->
                StatusLine(
                    icon = true,
                    isError = true,
                    text = "${if (alert.level == AlertLevel.ERROR) "오류" else "주의"} · ${alert.message}",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun HomeCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
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
private fun EmptyText(message: String) {
    Text(message, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun StatusLine(icon: Boolean, isError: Boolean, text: String, color: Color = Color.Unspecified) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon) {
            Icon(
                imageVector = if (isError) BJStockIcons.Warning else BJStockIcons.CheckCircle,
                contentDescription = null,
                tint = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = color)
    }
}