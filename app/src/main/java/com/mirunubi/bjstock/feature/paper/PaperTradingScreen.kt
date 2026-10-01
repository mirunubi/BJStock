package com.mirunubi.bjstock.feature.paper

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mirunubi.bjstock.core.model.ForwardCycleStatus
import com.mirunubi.bjstock.core.model.ForwardOperationStatus
import com.mirunubi.bjstock.core.model.OrderSide
import com.mirunubi.bjstock.core.model.RunStatus
import com.mirunubi.bjstock.ui.icons.BJStockIcons
import com.mirunubi.bjstock.ui.navigation.BJStockBottomBar
import com.mirunubi.bjstock.ui.navigation.PrimaryTab
import com.mirunubi.bjstock.ui.navigation.TabTopBar

@Composable
fun PaperTradingScreen(
    onSelectTab: (PrimaryTab) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: PaperTradingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TabTopBar(title = PrimaryTab.PAPER_TRADING.label, onOpenSettings = onOpenSettings) },
        bottomBar = { BJStockBottomBar(selected = PrimaryTab.PAPER_TRADING, onSelect = onSelectTab) },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            ScrollColumn {
                state.notice?.let { NoticeBanner(it, viewModel::dismissNotice) }
                RunListSection(state, viewModel)
                AutomationSection(state.automation, state.busy, viewModel)
                when (val detail = state.detail) {
                    DetailState.None -> Unit
                    DetailState.Loading -> LoadingLine("불러오는 중")
                    is DetailState.Failed -> {
                        WarningLine(detail.message)
                        OutlinedButton(onClick = viewModel::refresh) { Text("다시 시도") }
                    }
                    is DetailState.Loaded -> RunDetail(detail.view, state, viewModel)
                }
                OperationsSection(state.automation)
            }
        }
    }

    Dialogs(state, viewModel)
}

// region Run list

@Composable
private fun RunListSection(state: PaperTradingUiState, viewModel: PaperTradingViewModel) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("모의투자 목록", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Button(onClick = viewModel::showCreateDraft, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("새 모의투자")
        }
    }
    when (val runs = state.runs) {
        RunsState.Loading -> LoadingLine("불러오는 중")
        is RunsState.Failed -> {
            WarningLine(runs.message)
            OutlinedButton(onClick = viewModel::refresh) { Text("다시 시도") }
        }
        is RunsState.Loaded ->
            if (runs.rows.isEmpty()) {
                BodyText(PaperTradingPresenter.RUNS_EMPTY)
            } else {
                runs.rows.forEach { row ->
                    RunRowItem(row, selected = row.runId == state.selectedRunId) { viewModel.selectRun(row.runId) }
                }
            }
    }
}

@Composable
private fun RunRowItem(row: RunRow, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onClick),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(row.strategyLabel, style = MaterialTheme.typography.bodyLarge)
                RunStatusPill(row.badge)
                Text("${row.startLabel} · ${row.assetLabel}", style = MaterialTheme.typography.bodyMedium)
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

// region Automation

@Composable
private fun AutomationSection(automation: AutomationState, busy: Boolean, viewModel: PaperTradingViewModel) {
    SectionCard("자동운영") {
        when (automation) {
            AutomationState.Loading -> LoadingLine("불러오는 중")
            is AutomationState.Failed -> WarningLine(automation.message)
            is AutomationState.Loaded -> {
                val view = automation.view
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (view.enabled) BJStockIcons.CheckCircle else BJStockIcons.Block,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "자동 모의투자 ${view.stateLabel}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = view.enabled, onCheckedChange = { viewModel.requestAuto(it) }, enabled = !busy)
                }
                if (view.slotPastDue) WarningLine(view.slotLine) else BodyText(view.slotLine)
                view.warning?.let { WarningLine(it) }
                OutlinedButton(onClick = viewModel::requestRunNow, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("지금 실행")
                }
                SmallText("실제 주식 주문은 발생하지 않습니다.")
            }
        }
    }
}

@Composable
private fun OperationsSection(automation: AutomationState) {
    val view = (automation as? AutomationState.Loaded)?.view ?: return
    SectionCard("최근 실행 기록") {
        if (view.operations.isEmpty()) {
            BodyText(PaperTradingPresenter.OPERATIONS_EMPTY)
        }
        view.operations.forEachIndexed { index, operation ->
            if (index > 0) HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(operation.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    OperationStatusPill(operation.badge)
                }
                Text(operation.timing, style = MaterialTheme.typography.bodyMedium)
                Text(operation.throughDate, style = MaterialTheme.typography.bodyMedium)
                operation.message?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                operation.code?.let { SmallText("코드 $it") }
                operation.safeMessage?.let { SmallText(it) }
            }
        }
    }
}

// endregion

// region Run detail

@Composable
private fun RunDetail(view: RunDetailView, state: PaperTradingUiState, viewModel: PaperTradingViewModel) {
    HeaderCard(view.header)
    if (view.canMarkReady) {
        SectionCard("운영 준비") {
            BodyText("투자 대상 종목을 추가한 뒤 운영 준비를 완료하면 포워드 테스트 대상이 됩니다.")
            Button(onClick = viewModel::requestReady, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("운영 준비 완료")
            }
        }
    }
    AccountCard(view.account)
    HoldingsCard(view.holdings)
    OrdersCard(view)
    PolicyCard(view.policy, state.policyExpanded, viewModel::togglePolicy)
    UniverseCard(view.universe, state.universeSearch, state.busy, viewModel)
    CyclesCard(view, state.busy, viewModel)
    TimelineCard(view.timeline)
}

@Composable
private fun HeaderCard(header: RunHeader) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(header.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            RunStatusPill(header.badge, large = true)
            LabeledRow("전략", header.strategyName)
            LabeledRow("버전", header.versionLabel)
            LabeledRow("기간", header.period)
            LabeledRow("초기자금", header.initialCash)
        }
    }
}

@Composable
private fun AccountCard(account: AccountView) {
    SectionCard("모의계좌") {
        account.warning?.let { WarningLine(it) }
        account.rows.forEachIndexed { index, row ->
            if (index == 0) {
                Text(row.label, style = MaterialTheme.typography.bodyLarge)
                Text(row.value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            } else {
                LabeledRow(row.label, row.value)
            }
        }
        account.note?.let { SmallText(it) }
    }
}

@Composable
private fun HoldingsCard(holdings: List<HoldingView>) {
    SectionCard("보유 종목") {
        if (holdings.isEmpty()) BodyText(PaperTradingPresenter.HOLDINGS_EMPTY)
        holdings.forEachIndexed { index, holding ->
            if (index > 0) HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${holding.name} ${holding.symbol}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                LabeledRow("수량", holding.quantity)
                LabeledRow("평균매수가", holding.averagePrice)
                LabeledRow("최근 저장 종가", holding.latestClose)
                LabeledRow("평가금액", holding.marketValue)
                LabeledRow("가격 기준 평가손익", holding.pricePnl)
            }
        }
        if (holdings.isNotEmpty()) SmallText(PaperTradingPresenter.PRICE_PNL_NOTE)
    }
}

@Composable
private fun OrdersCard(view: RunDetailView) {
    SectionCard("최근 주문·체결") {
        SubTitle("가상 체결")
        if (view.executions.isEmpty()) BodyText(PaperTradingPresenter.EXECUTIONS_EMPTY)
        view.executions.forEach { execution ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    execution.headline,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = sideColor(execution.side),
                )
                Text(execution.detail, style = MaterialTheme.typography.bodyLarge)
                Text(execution.costs, style = MaterialTheme.typography.bodyMedium)
            }
        }
        HorizontalDivider()
        SubTitle("모의주문")
        if (view.orders.isEmpty()) BodyText(PaperTradingPresenter.ORDERS_EMPTY)
        view.orders.forEach { order ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(order.headline, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = sideColor(order.side))
                Text(order.detail, style = MaterialTheme.typography.bodyMedium)
            }
        }
        SmallText("모의투자 기록이며 실제 주식 주문은 발생하지 않습니다.")
    }
}

@Composable
private fun PolicyCard(policy: List<LabeledValue>?, expanded: Boolean, onToggle: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = policy != null, onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("거래 정책", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (policy != null) {
                    Text(if (expanded) "접기" else "펼치기", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
            }
            when {
                policy == null -> BodyText(PaperTradingPresenter.POLICY_PENDING)
                expanded -> policy.forEach { LabeledRow(it.label, it.value) }
                else -> SmallText("운영 준비 완료 시 고정된 거래 정책입니다.")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UniverseCard(universe: UniverseView, search: UniverseSearch, busy: Boolean, viewModel: PaperTradingViewModel) {
    SectionCard("투자 대상 (${universe.items.size}종목)") {
        universe.lockedNote?.let { InfoBox(BJStockIcons.Lock, it) }
        if (universe.items.isEmpty()) BodyText(PaperTradingPresenter.UNIVERSE_EMPTY)
        universe.items.forEach { item ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (universe.editable) {
                    TextButton(onClick = { viewModel.removeInstrument(item.instrumentId) }, enabled = !busy) { Text("제외") }
                }
            }
        }
        if (universe.editable) {
            HorizontalDivider()
            SubTitle("종목 추가")
            OutlinedTextField(
                value = search.query,
                onValueChange = viewModel::onUniverseQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("종목명 또는 종목코드") },
            )
            search.results.forEach { result ->
                val added = universe.items.any { it.instrumentId == result.instrumentId }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(result.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (added) {
                        Text("추가됨", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        TextButton(onClick = { viewModel.addInstrument(result.instrumentId) }, enabled = !busy) { Text("추가") }
                    }
                }
            }
            if (universe.themes.isNotEmpty()) {
                SubTitle("테마로 추가")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    universe.themes.forEach { theme ->
                        OutlinedButton(onClick = { viewModel.addTheme(theme.themeId) }, enabled = !busy) { Text(theme.name) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CyclesCard(view: RunDetailView, busy: Boolean, viewModel: PaperTradingViewModel) {
    SectionCard("거래일 처리") {
        when (val retry = view.retry) {
            null -> Unit
            is RetryView.Retryable -> {
                WarningLine("처리에 실패한 거래일이 있습니다.")
                retry.reason?.let { BodyText(it) }
                Button(onClick = viewModel::requestRetry, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(retry.label)
                }
            }
            is RetryView.NotRetryable -> {
                WarningLine(retry.message)
                retry.reason?.let { BodyText(it) }
                retry.code?.let { SmallText("코드 $it") }
            }
        }
        if (view.cycles.isEmpty()) BodyText(PaperTradingPresenter.CYCLES_EMPTY)
        view.cycles.forEach { cycle ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(cycleIcon(cycle.status), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(cycle.headline, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    cycle.detail?.let { SmallText(it) }
                }
            }
        }
    }
}

@Composable
private fun TimelineCard(timeline: List<TimelineView>) {
    SectionCard("활동 기록") {
        if (timeline.isEmpty()) BodyText(PaperTradingPresenter.TIMELINE_EMPTY)
        timeline.forEach { event ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(event.headline, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                event.reason?.let { SmallText(it) }
            }
        }
    }
}

// endregion

// region Dialogs

@Composable
private fun Dialogs(state: PaperTradingUiState, viewModel: PaperTradingViewModel) {
    when (val dialog = state.dialog) {
        null -> Unit
        is PaperDialog.ConfirmAuto -> ConfirmDialog(
            title = if (dialog.enable) PaperTradingPresenter.AUTO_ON_TITLE else PaperTradingPresenter.AUTO_OFF_TITLE,
            body = if (dialog.enable) PaperTradingPresenter.AUTO_ON_BODY else PaperTradingPresenter.AUTO_OFF_BODY,
            confirmLabel = if (dialog.enable) "켜기" else "끄기",
            onConfirm = viewModel::confirmAuto,
            onDismiss = viewModel::dismissDialog,
        )
        PaperDialog.ConfirmRunNow -> ConfirmDialog(
            title = PaperTradingPresenter.RUN_NOW_TITLE,
            body = PaperTradingPresenter.RUN_NOW_BODY,
            confirmLabel = "실행",
            onConfirm = viewModel::confirmRunNow,
            onDismiss = viewModel::dismissDialog,
        )
        is PaperDialog.ConfirmRetry -> ConfirmDialog(
            title = PaperTradingPresenter.RETRY_TITLE,
            body = PaperTradingPresenter.retryBody(dialog.marketDate),
            confirmLabel = "다시 처리",
            onConfirm = viewModel::confirmRetry,
            onDismiss = viewModel::dismissDialog,
        )
        is PaperDialog.ConfirmReady -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text(PaperTradingPresenter.READY_TITLE, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    dialog.rows.forEach { LabeledRow(it.label, it.value) }
                    Spacer(Modifier.width(4.dp))
                    Text(PaperTradingPresenter.READY_BODY, style = MaterialTheme.typography.bodyLarge)
                }
            },
            confirmButton = { Button(onClick = viewModel::confirmReady) { Text("운영 준비 완료") } },
            dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text("취소") } },
        )
        is PaperDialog.CreateDraft -> CreateDraftDialog(dialog, state.busy, viewModel)
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(body, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = { Button(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateDraftDialog(dialog: PaperDialog.CreateDraft, busy: Boolean, viewModel: PaperTradingViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissDialog,
        title = { Text("새 모의투자", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("전략 버전", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    dialog.versions.forEach { option ->
                        FilterChip(
                            selected = option.strategyVersionId == dialog.selectedVersionId,
                            onClick = { viewModel.onDraftVersion(option.strategyVersionId) },
                            label = { Text(option.label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = dialog.name,
                    onValueChange = viewModel::onDraftName,
                    singleLine = true,
                    label = { Text("모의투자 이름") },
                )
                OutlinedTextField(
                    value = dialog.startDate,
                    onValueChange = viewModel::onDraftStartDate,
                    singleLine = true,
                    label = { Text("시작일") },
                    placeholder = { Text("2026-10-01") },
                )
                OutlinedTextField(
                    value = dialog.initialCash,
                    onValueChange = viewModel::onDraftInitialCash,
                    singleLine = true,
                    label = { Text("초기자금") },
                    suffix = { Text("원") },
                    supportingText = {
                        PaperTradingPresenter.parseWon(dialog.initialCash)?.let { Text(PaperTradingPresenter.won(it)) }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text("만들면 설정중 상태로 저장됩니다. 운영 준비를 완료하기 전에는 실행되지 않습니다.", style = MaterialTheme.typography.bodyMedium)
                dialog.error?.let { WarningLine(it) }
            }
        },
        confirmButton = {
            Button(onClick = viewModel::confirmCreateDraft, enabled = !busy && dialog.versions.isNotEmpty()) { Text("만들기") }
        },
        dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text("취소") } },
    )
}

// endregion

// region Shared pieces

@Composable
private fun RunStatusPill(badge: RunStatusBadge, large: Boolean = false) {
    val (icon, color) = when (badge.status) {
        RunStatus.RUNNING -> BJStockIcons.PaperTrading to MaterialTheme.colorScheme.primaryContainer
        RunStatus.READY -> BJStockIcons.Lock to MaterialTheme.colorScheme.secondaryContainer
        RunStatus.PAUSED -> BJStockIcons.Warning to MaterialTheme.colorScheme.tertiaryContainer
        RunStatus.DRAFT -> BJStockIcons.Edit to MaterialTheme.colorScheme.tertiaryContainer
        RunStatus.COMPLETED -> BJStockIcons.CheckCircle to MaterialTheme.colorScheme.surfaceVariant
        RunStatus.CANCELLED -> BJStockIcons.Close to MaterialTheme.colorScheme.surfaceVariant
    }
    Pill(icon, color, badge.label, large)
}

@Composable
private fun OperationStatusPill(badge: OperationStatusBadge) {
    val (icon, color) = when (badge.status) {
        ForwardOperationStatus.RUNNING -> BJStockIcons.PaperTrading to MaterialTheme.colorScheme.secondaryContainer
        ForwardOperationStatus.SUCCEEDED -> BJStockIcons.CheckCircle to MaterialTheme.colorScheme.primaryContainer
        ForwardOperationStatus.NO_OP -> BJStockIcons.CheckCircle to MaterialTheme.colorScheme.surfaceVariant
        ForwardOperationStatus.PARTIAL -> BJStockIcons.Warning to MaterialTheme.colorScheme.tertiaryContainer
        ForwardOperationStatus.BLOCKED -> BJStockIcons.Block to MaterialTheme.colorScheme.errorContainer
        ForwardOperationStatus.FAILED -> BJStockIcons.Warning to MaterialTheme.colorScheme.errorContainer
    }
    Pill(icon, color, badge.label, large = false)
}

@Composable
private fun Pill(icon: ImageVector, color: Color, label: String, large: Boolean) {
    Surface(shape = MaterialTheme.shapes.small, color = color) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = if (large) 6.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(if (large) 22.dp else 18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun cycleIcon(status: ForwardCycleStatus): ImageVector = when (status) {
    ForwardCycleStatus.PENDING -> BJStockIcons.ChevronRight
    ForwardCycleStatus.RUNNING -> BJStockIcons.PaperTrading
    ForwardCycleStatus.COMPLETE -> BJStockIcons.CheckCircle
    ForwardCycleStatus.FAILED -> BJStockIcons.Warning
}

/** Secondary cue only; the side is always written in text. */
@Composable
private fun sideColor(side: OrderSide): Color = when (side) {
    OrderSide.BUY -> Color(0xFFC62828)
    OrderSide.SELL -> Color(0xFF1565C0)
}

@Composable
private fun NoticeBanner(notice: PaperNotice, onDismiss: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (notice.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (notice.isError) BJStockIcons.Warning else BJStockIcons.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(notice.message, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) { Icon(BJStockIcons.Close, contentDescription = "알림 닫기") }
        }
    }
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
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
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
