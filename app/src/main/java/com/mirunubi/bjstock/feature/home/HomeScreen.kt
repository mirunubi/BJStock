package com.mirunubi.bjstock.feature.home

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
import com.mirunubi.bjstock.ui.icons.BJStockIcons
import com.mirunubi.bjstock.ui.navigation.BJStockBottomBar
import com.mirunubi.bjstock.ui.navigation.PrimaryTab
import com.mirunubi.bjstock.ui.navigation.TabTopBar

@Composable
fun HomeScreen(
    onSelectTab: (PrimaryTab) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = { TabTopBar(title = "BJStock", onOpenSettings = onOpenSettings) },
        bottomBar = { BJStockBottomBar(selected = PrimaryTab.HOME, onSelect = onSelectTab) },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (val current = state) {
                HomeUiState.Loading -> CenteredMessage {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("로딩 중", style = MaterialTheme.typography.titleMedium)
                }
                is HomeUiState.Error -> CenteredMessage {
                    StatusLine(icon = true, isError = true, text = current.message)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = viewModel::refresh) { Text("다시 시도") }
                }
                is HomeUiState.Content -> HomeContent(current)
            }
        }
    }
}

@Composable
private fun HomeContent(content: HomeUiState.Content) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PortfolioSection(content.portfolio)
        DecisionSection(content.decision)
        HoldingsSection(content.holdings)
        AutoSection(content.auto)
        AlertsSection(content.alerts)
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
    HomeCard(title = "최근 전략 판단") {
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
    HomeCard(title = "보유현황") {
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
private fun AutoSection(card: AutoCard) {
    HomeCard(title = "자동운영") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("자동운영", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(12.dp))
            Text(card.stateLabel, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        LabeledRow("다음 실행", card.nextRun)
        LabeledRow("최근 실행", card.latestOperation)
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
                "운영 경고",
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

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}
