package com.mirunubi.bjstock.feature.admin

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mirunubi.bjstock.core.model.ForwardOperationStatus
import com.mirunubi.bjstock.ui.icons.BJStockIcons
import com.mirunubi.bjstock.ui.navigation.BackTopBar

@Composable
fun AdminScreen(
    onBack: () -> Unit,
    viewModel: AdminViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    val detail = state.detail
    BackHandler(enabled = detail != null) { viewModel.closeDetail() }

    Scaffold(
        topBar = {
            BackTopBar(
                title = if (detail != null) "실행 상세" else "운영 · 감사",
                onBack = if (detail != null) viewModel::closeDetail else onBack,
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            ScrollColumn {
                if (detail != null) {
                    DetailContent(detail)
                } else {
                    InfoBox(BJStockIcons.Lock, "읽기 전용 화면입니다. 실행이나 설정을 변경하지 않습니다.")
                    StatusSection(state.status, viewModel::refresh)
                    OperationsSection(state.operations, viewModel::refresh, viewModel::openOperation)
                    AuditSection(state, viewModel)
                    ErrorsSection(state, viewModel)
                    EnvironmentSection(state.environment, viewModel::refresh)
                }
            }
        }
    }
}

// region Sections

@Composable
private fun StatusSection(section: SectionState<StatusView>, onReload: () -> Unit) {
    SectionCard("운영 상태") {
        SectionBody(section, onReload) { view ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("자동운영", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Pill(
                    icon = if (view.autoEnabled) BJStockIcons.CheckCircle else BJStockIcons.Block,
                    tone = if (view.autoEnabled) AdminTone.POSITIVE else AdminTone.NEUTRAL,
                    label = view.autoLabel,
                )
            }
            if (view.nextRunPastDue) WarningLine("다음 실행: ${view.nextRun}") else LabeledRow("다음 실행", view.nextRun)
            LabeledRow("WorkManager 상태", view.workState)
            view.scheduleWarning?.let { WarningLine(it) }
            LabeledRow("실행 중 작업", view.runningOperation)
            LabeledRow("최근 성공", view.lastSuccess)
            LabeledRow("최근 실패/차단", view.lastProblem)
            LabeledRow("Run 상태", view.runs)
            LabeledRow("KIS 환경", view.kisEnvironment)
            LabeledRow("KIS 인증정보", view.kisCredential)
            SmallText(view.windowNote)
        }
    }
}

@Composable
private fun OperationsSection(
    section: SectionState<List<OperationRow>>,
    onReload: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    SectionCard("최근 실행") {
        SectionBody(section, onReload) { rows ->
            rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onOpen(row.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(row.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            OperationStatusPill(row.status, row.statusLabel)
                        }
                        Text("${row.time} · ${row.trigger} · 소요 ${row.elapsed}", style = MaterialTheme.typography.bodyMedium)
                        row.message?.let { BodyText(it) }
                        row.code?.let { SmallText("코드 $it") }
                        row.safeMessage?.let { SmallText(it) }
                    }
                    Icon(BJStockIcons.ChevronRight, contentDescription = "실행 상세 보기")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AuditSection(state: AdminUiState, viewModel: AdminViewModel) {
    SectionCard("Audit") {
        SmallText(AdminPresenter.AUDIT_WINDOW_NOTE)
        SmallText(AdminPresenter.AUDIT_WINDOW_DETAIL)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AuditFilter.entries.forEach { filter ->
                FilterChip(
                    selected = state.auditFilter == filter,
                    onClick = { viewModel.selectAuditFilter(filter) },
                    label = { Text(filter.label) },
                )
            }
        }
        SectionBody(state.audit, viewModel::refresh) { rows ->
            val visible = AdminPresenter.filter(rows, state.auditFilter)
            if (visible.isEmpty()) BodyText(AdminPresenter.AUDIT_EMPTY)
            visible.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider()
                AuditRowItem(row, expanded = row.key in state.expandedAudit) { viewModel.toggleAudit(row.key) }
            }
        }
    }
}

@Composable
private fun AuditRowItem(row: AuditRow, expanded: Boolean, onToggle: (() -> Unit)?) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SmallText("${row.time} · ${row.category.label}")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Pill(
                icon = if (row.problem) BJStockIcons.Warning else BJStockIcons.ChevronRight,
                tone = if (row.problem) AdminTone.ERROR else AdminTone.NEUTRAL,
                label = row.result,
            )
        }
        row.reason?.let { BodyText("사유: $it") }
        listOfNotNull(row.run, row.operation).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { SmallText(it) }
        if (expanded) row.details.forEach { LabeledRow(it.label, it.value) }
    }
}

@Composable
private fun ErrorsSection(state: AdminUiState, viewModel: AdminViewModel) {
    SectionCard("오류") {
        SmallText(AdminPresenter.ERRORS_WINDOW_NOTE)
        SmallText(AdminPresenter.ERRORS_WINDOW_DETAIL)
        SectionBody(state.errors, viewModel::refresh) { rows ->
            rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider()
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { viewModel.toggleError(row.key) }.padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    SmallText("${row.time} · ${row.source}")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(row.description, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Pill(BJStockIcons.Warning, row.tone, "심각도 ${row.severity}")
                    }
                    LabeledRow("분류", row.category)
                    LabeledRow("오류 코드", row.code)
                    LabeledRow("재시도", row.retry)
                    LabeledRow("운영 영향", row.impact)
                    if (row.key in state.expandedErrors) row.details.forEach { LabeledRow(it.label, it.value) }
                }
            }
        }
    }
}

@Composable
private fun EnvironmentSection(section: SectionState<EnvironmentView>, onReload: () -> Unit) {
    SectionCard("앱 정보 · 환경") {
        SectionBody(section, onReload) { view ->
            view.fields.forEach { LabeledRow(it.label, it.value) }
            SubTitle("사용 중인 전략 버전")
            if (view.activeVersions.isEmpty()) BodyText(AdminPresenter.NO_ACTIVE_VERSIONS)
            view.activeVersions.forEach { BodyText(it) }
            SubTitle("실행 준비 · 운영 중 Run")
            if (view.runs.isEmpty()) BodyText(AdminPresenter.NO_ACTIVE_RUNS)
            view.runs.forEach { BodyText(it) }
        }
    }
}

@Composable
private fun DetailContent(detail: AdminDetail) {
    when (detail) {
        is AdminDetail.Loading -> LoadingLine(AdminPresenter.LOADING)
        is AdminDetail.Failed -> WarningLine(detail.message)
        is AdminDetail.Loaded -> {
            val view = detail.view
            SectionCard(view.title) {
                OperationStatusPill(view.status, view.statusLabel)
                view.fields.forEach { LabeledRow(it.label, it.value) }
            }
            SectionCard("이벤트 타임라인") {
                if (view.events.isEmpty()) BodyText(AdminPresenter.EVENTS_EMPTY)
                view.events.forEachIndexed { index, event ->
                    if (index > 0) HorizontalDivider()
                    EventItem(event)
                }
            }
            SectionCard("관련 Audit") {
                if (view.audits.isEmpty()) BodyText(AdminPresenter.AUDIT_EMPTY)
                view.audits.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider()
                    AuditRowItem(row, expanded = true, onToggle = null)
                }
            }
        }
    }
}

@Composable
private fun EventItem(event: EventView) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SmallText(event.time)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(event.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Pill(
                icon = if (event.problem) BJStockIcons.Warning else BJStockIcons.ChevronRight,
                tone = if (event.problem) AdminTone.ERROR else AdminTone.NEUTRAL,
                label = event.result,
            )
        }
        event.reasonCode?.let { LabeledRow("사유 코드", it) }
        event.reason?.let { BodyText(it) }
        event.safeMessage?.let { SmallText(it) }
        event.ids?.let { SmallText(it) }
        event.elapsed?.let { SmallText("소요 $it") }
    }
}

/** Loading / empty / failed / loaded rendering shared by every section. */
@Composable
private fun <T> SectionBody(section: SectionState<T>, onReload: () -> Unit, content: @Composable (T) -> Unit) {
    when (section) {
        SectionState.Loading -> LoadingLine(AdminPresenter.LOADING)
        is SectionState.Empty -> BodyText(section.message)
        is SectionState.Failed -> {
            WarningLine(section.message)
            OutlinedButton(onClick = onReload, modifier = Modifier.heightIn(min = 48.dp)) { Text("다시 불러오기") }
        }
        is SectionState.Loaded -> content(section.value)
    }
}

// endregion

// region Shared pieces

@Composable
private fun OperationStatusPill(status: ForwardOperationStatus, label: String) {
    val icon = when (status) {
        ForwardOperationStatus.RUNNING -> BJStockIcons.PaperTrading
        ForwardOperationStatus.SUCCEEDED, ForwardOperationStatus.NO_OP -> BJStockIcons.CheckCircle
        ForwardOperationStatus.PARTIAL, ForwardOperationStatus.FAILED -> BJStockIcons.Warning
        ForwardOperationStatus.BLOCKED -> BJStockIcons.Block
    }
    Pill(icon, AdminPresenter.statusTone(status), label)
}

@Composable
private fun Pill(icon: ImageVector, tone: AdminTone, label: String) {
    Surface(shape = MaterialTheme.shapes.small, color = toneColor(tone)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun toneColor(tone: AdminTone): Color = when (tone) {
    AdminTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
    AdminTone.POSITIVE -> MaterialTheme.colorScheme.primaryContainer
    AdminTone.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
    AdminTone.ERROR -> MaterialTheme.colorScheme.errorContainer
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
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(0.4f))
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.6f),
        )
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
