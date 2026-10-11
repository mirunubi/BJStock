package com.mirunubi.bjstock.feature.strategy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mirunubi.bjstock.core.model.SignalAction
import com.mirunubi.bjstock.core.model.SignalOperator
import com.mirunubi.bjstock.core.model.StrategyVersionStatus
import com.mirunubi.bjstock.core.model.TradeDecision
import com.mirunubi.bjstock.feature.strategy.template.TemplatePreviewDetail
import com.mirunubi.bjstock.feature.strategy.template.TemplatePreviewDialogs
import com.mirunubi.bjstock.feature.strategy.template.TemplatePreviewSection
import com.mirunubi.bjstock.feature.strategy.template.TemplatePreviewViewModel
import com.mirunubi.bjstock.ui.icons.BJStockIcons
import com.mirunubi.bjstock.ui.navigation.BJStockBottomBar
import com.mirunubi.bjstock.ui.navigation.LayerBackHandler
import com.mirunubi.bjstock.ui.navigation.LocalNavChrome
import com.mirunubi.bjstock.ui.navigation.PrimaryTab
import com.mirunubi.bjstock.ui.navigation.TabTopBar

@Composable
fun StrategyScreen(
    onSelectTab: (PrimaryTab) -> Unit,
    viewModel: StrategyViewModel = hiltViewModel(),
    previewViewModel: TemplatePreviewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val preview by previewViewModel.uiState.collectAsStateWithLifecycle()
    val previewOpen = preview.selectedId != null
    val layered = previewOpen || state.strategy != null || state.version != null
    val back: () -> Unit = { if (previewOpen) previewViewModel.close() else viewModel.back() }
    LayerBackHandler(hasInScreenLayer = layered) { back() }

    Scaffold(
        topBar = {
            TabTopBar(
                title = PrimaryTab.STRATEGY.label,
                onBack = if (layered) back else null,
            )
        },
        bottomBar = { BJStockBottomBar(selected = PrimaryTab.STRATEGY, onSelect = onSelectTab) },
    ) { innerPadding ->
        val mode = StrategyLayout.modeFor(LocalNavChrome.current.widthClass)
        val detail = StrategyLayout.detailOf(preview.selectedId, state.version, state.strategy)
        val families = remember(viewModel, previewViewModel) { StrategyFamilies(viewModel, previewViewModel) }
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (mode) {
                StrategyLayoutMode.SINGLE_PANE -> ScrollColumn {
                    state.notice?.let { NoticeBanner(it, viewModel::dismissNotice) }
                    when (detail) {
                        is StrategyDetail.Template -> TemplatePreviewDetail(preview, previewViewModel)
                        is StrategyDetail.Version -> VersionContent(detail.layer, state.busy, viewModel)
                        is StrategyDetail.Strategy -> StrategyContent(detail.panel, state.busy, viewModel)
                        StrategyDetail.None -> {
                            TemplatePreviewSection(preview, previewViewModel)
                            ListContent(state.list, viewModel)
                        }
                    }
                }
                StrategyLayoutMode.LIST_DETAIL -> {
                    val selection = StrategyLayout.masterSelection(mode, preview.selectedId, state.strategy)
                    Column(Modifier.fillMaxSize()) {
                        state.notice?.let {
                            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) { NoticeBanner(it, viewModel::dismissNotice) }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            PaneColumn(Modifier.width(StrategyLayout.MASTER_PANE_WIDTH_DP.dp)) {
                                TemplatePreviewSection(
                                    state = preview,
                                    viewModel = previewViewModel,
                                    onOpen = families::openTemplate,
                                    onCreate = families::createTemplate,
                                    selectedId = selection.templateId,
                                    showMessage = detail !is StrategyDetail.Template,
                                )
                                ListContent(
                                    list = state.list,
                                    viewModel = viewModel,
                                    onOpen = families::openStrategy,
                                    onCreate = families::showCreateStrategy,
                                    selectedId = selection.strategyId,
                                )
                            }
                            key(StrategyLayout.detailKey(detail)) {
                                PaneColumn(Modifier.weight(1f)) {
                                    when (detail) {
                                        is StrategyDetail.Template -> TemplatePreviewDetail(preview, previewViewModel)
                                        is StrategyDetail.Version -> VersionContent(detail.layer, state.busy, viewModel)
                                        is StrategyDetail.Strategy ->
                                            StrategyContent(detail.panel, state.busy, viewModel, onOpenVersion = families::openVersion)
                                        StrategyDetail.None -> BodyText(StrategyLayout.EMPTY_DETAIL)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    TemplatePreviewDialogs(preview, previewViewModel)

    when (val dialog = state.dialog) {
        null -> Unit
        is StrategyDialog.CreateStrategy -> CreateStrategyDialog(dialog, state.busy, viewModel)
        is StrategyDialog.ConfirmActivate -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text(dialog.title, fontWeight = FontWeight.Bold) },
            text = { Text(dialog.body, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { Button(onClick = viewModel::confirmActivate) { Text("사용 시작") } },
            dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text("취소") } },
        )
        is StrategyDialog.ConfirmDeleteRule -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text("신호 규칙을 삭제하시겠습니까?", fontWeight = FontWeight.Bold) },
            text = { Text("'${dialog.ruleName}' 규칙을 이 작성본에서 삭제합니다.", style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { Button(onClick = viewModel::confirmDeleteRule) { Text("삭제") } },
            dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text("취소") } },
        )
    }
}

// region Strategy list

@Composable
private fun ListContent(
    list: ListState,
    viewModel: StrategyViewModel,
    onOpen: (Long) -> Unit = viewModel::openStrategy,
    onCreate: () -> Unit = viewModel::showCreateStrategy,
    selectedId: Long? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("전략 목록", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Button(onClick = onCreate, modifier = Modifier.heightIn(min = 48.dp)) { Text("새 전략") }
    }
    when (list) {
        ListState.Loading -> LoadingLine("불러오는 중")
        is ListState.Failed -> {
            WarningLine(list.message)
            OutlinedButton(onClick = viewModel::refresh) { Text("다시 시도") }
        }
        is ListState.Loaded ->
            if (list.cards.isEmpty()) {
                BodyText(StrategyPresenter.LIST_EMPTY)
            } else {
                list.cards.forEach { card ->
                    StrategyCardItem(card, selected = card.strategyId == selectedId) { onOpen(card.strategyId) }
                }
            }
    }
}

@Composable
private fun StrategyCardItem(card: StrategyCard, selected: Boolean, onClick: () -> Unit) {
    val modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onClick)
    Card(
        modifier = if (selected) modifier.semantics { this.selected = true } else modifier,
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(card.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(card.code, style = MaterialTheme.typography.bodyMedium)
                Text("${card.versionCount} · ${card.statusSummary}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Icon(BJStockIcons.ChevronRight, contentDescription = null)
        }
    }
}

// endregion

// region Strategy versions

@Composable
private fun StrategyContent(
    strategy: StrategyPanel,
    busy: Boolean,
    viewModel: StrategyViewModel,
    onOpenVersion: (Long) -> Unit = viewModel::openVersion,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(strategy.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(strategy.code, style = MaterialTheme.typography.bodyMedium)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("버전", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = viewModel::createDraft, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("새 작성본")
        }
    }
    when (val versions = strategy.versions) {
        VersionsState.Loading -> LoadingLine("불러오는 중")
        is VersionsState.Failed -> WarningLine(versions.message)
        is VersionsState.Loaded ->
            if (versions.rows.isEmpty()) {
                BodyText(StrategyPresenter.VERSIONS_EMPTY)
            } else {
                versions.rows.forEach { row -> VersionRowItem(row) { onOpenVersion(row.versionId) } }
            }
    }
}

@Composable
private fun VersionRowItem(row: VersionRow, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(row.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusPill(row.badge)
                Text(row.thresholdSummary, style = MaterialTheme.typography.bodyLarge)
                row.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            Icon(BJStockIcons.ChevronRight, contentDescription = null)
        }
    }
}

// endregion

// region Version detail

@Composable
private fun VersionContent(layer: VersionLayer, busy: Boolean, viewModel: StrategyViewModel) {
    when (layer) {
        is VersionLayer.Loading -> LoadingLine("불러오는 중")
        is VersionLayer.Failed -> {
            WarningLine(layer.message)
            OutlinedButton(onClick = viewModel::retryVersion) { Text("다시 시도") }
        }
        is VersionLayer.Loaded -> {
            val panel = layer.panel
            VersionHeader(panel, busy, viewModel)
            ThresholdCard(panel, busy, viewModel)
            FactorCard(panel, busy, viewModel)
            RulesCard(panel, busy, viewModel)
            PreviewCard(panel, viewModel)
        }
    }
}

@Composable
private fun VersionHeader(panel: VersionPanel, busy: Boolean, viewModel: StrategyViewModel) {
    SectionCard(title = panel.strategyName) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(panel.label, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            StatusPill(panel.badge, large = true)
        }
        panel.lockedReason?.let { InfoBox(BJStockIcons.Lock, it) }
        if (panel.editable) {
            Button(
                onClick = viewModel::requestActivate,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text("버전 사용 시작", style = MaterialTheme.typography.titleMedium) }
            if (panel.hasUnsavedChanges) WarningLine(StrategyPresenter.UNSAVED_BLOCKS_ACTIVATION)
        }
        OutlinedButton(
            onClick = viewModel::copyVersion,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("이 버전을 복사해 새 작성본 만들기") }
    }
}

@Composable
private fun ThresholdCard(panel: VersionPanel, busy: Boolean, viewModel: StrategyViewModel) {
    SectionCard(title = "판단 기준") {
        val sell = if (panel.editable) panel.sellInput else panel.savedSell
        val buy = if (panel.editable) panel.buyInput else panel.savedBuy
        val bands = StrategyPresenter.bands(sell, buy)
        val hint = if (panel.editable) StrategyPresenter.thresholdHint(sell, buy) else null
        if (bands != null && hint == null) {
            bands.forEach { band -> BandRow(band) }
        }
        if (panel.editable) {
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField("매도 기준 점수", panel.sellInput, viewModel::onSellChange, Modifier.weight(1f))
                NumberField("매수 기준 점수", panel.buyInput, viewModel::onBuyChange, Modifier.weight(1f))
            }
            hint?.let { WarningLine(it) }
            if (panel.thresholdsDirty) {
                SaveRow(busy = busy, onSave = viewModel::saveThresholds, onDiscard = viewModel::discardThresholds, saveLabel = "판단 기준 저장")
            }
        }
    }
}

@Composable
private fun BandRow(band: ThresholdBand) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            band.label,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = decisionColor(band.decision),
            modifier = Modifier.width(72.dp),
        )
        Text(band.range, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FactorCard(panel: VersionPanel, busy: Boolean, viewModel: StrategyViewModel) {
    SectionCard(title = "팩터 가중치") {
        val total = StrategyPresenter.totalWeight(panel.factors)
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = if (total.complete) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("사용 팩터 총 비중", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (total.complete) BJStockIcons.CheckCircle else BJStockIcons.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(total.text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
                total.warning?.let { Text(it, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold) }
            }
        }
        panel.factors.forEach { factor ->
            HorizontalDivider()
            FactorRowItem(
                factor = factor,
                editable = panel.editable,
                expanded = factor.code in panel.expandedFactors,
                viewModel = viewModel,
            )
        }
        if (panel.editable && panel.factorsDirty) {
            SaveRow(busy = busy, onSave = viewModel::saveFactors, onDiscard = viewModel::discardFactors, saveLabel = "팩터 비중 저장")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FactorRowItem(factor: FactorInput, editable: Boolean, expanded: Boolean, viewModel: StrategyViewModel) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (editable) {
                Checkbox(checked = factor.enabled, onCheckedChange = { viewModel.onFactorEnabled(factor.code, it) })
            } else {
                Icon(
                    if (factor.enabled) BJStockIcons.CheckCircle else BJStockIcons.Block,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(factor.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (factor.enabled) factor.code else "${factor.code} · 사용 안 함",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (editable) {
                OutlinedTextField(
                    value = factor.weightPercent,
                    onValueChange = { viewModel.onFactorWeight(factor.code, it) },
                    modifier = Modifier.width(110.dp),
                    singleLine = true,
                    suffix = { Text("%") },
                    label = { Text("비중") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            } else {
                Text(StrategyPresenter.weightLabel(factor), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(StrategyPresenter.calculationLabel(factor), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { viewModel.toggleFactorAdvanced(factor.code) }) {
                Text(if (expanded) "고급 설정 닫기" else "고급 설정")
            }
        }
        if (expanded) {
            if (editable) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField("최소 점수", factor.minScore, { viewModel.onFactorMin(factor.code, it) }, Modifier.weight(1f))
                    NumberField("최대 점수", factor.maxScore, { viewModel.onFactorMax(factor.code, it) }, Modifier.weight(1f))
                }
                Text("비워 두면 조건이 없습니다.", style = MaterialTheme.typography.bodySmall)
                Text("계산 버전", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    factor.availableVersions.forEach { version ->
                        FilterChip(
                            selected = version == factor.calculationVersion,
                            onClick = { viewModel.onFactorVersion(factor.code, version) },
                            label = { Text(version) },
                        )
                    }
                }
            } else {
                Text(StrategyPresenter.gateLabel(factor), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun RulesCard(panel: VersionPanel, busy: Boolean, viewModel: StrategyViewModel) {
    SectionCard(title = "신호 규칙") {
        Text(StrategyPresenter.PRIORITY_HELP, style = MaterialTheme.typography.bodyMedium)
        if (panel.rules.isEmpty()) BodyText(StrategyPresenter.RULES_EMPTY)
        panel.rules.forEach { rule ->
            HorizontalDivider()
            RuleRowItem(rule, panel.editable, busy, viewModel)
        }
        if (panel.editable) {
            val form = panel.ruleForm
            if (form == null) {
                OutlinedButton(onClick = viewModel::startNewRule, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("규칙 추가")
                }
            } else {
                HorizontalDivider()
                RuleFormSection(form, busy, viewModel)
            }
        }
    }
}

@Composable
private fun RuleRowItem(rule: RuleView, editable: Boolean, busy: Boolean, viewModel: StrategyViewModel) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(rule.condition, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "→ ${rule.actionLabel}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = actionColor(rule.action),
        )
        Text(
            listOfNotNull(rule.priority, rule.name, if (rule.enabled) null else "사용 안 함").joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
        )
        rule.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (editable) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { viewModel.editRule(rule.ruleId) }, enabled = !busy) { Text("수정") }
                TextButton(onClick = { viewModel.requestDeleteRule(rule.ruleId) }, enabled = !busy) { Text("삭제") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleFormSection(form: RuleForm, busy: Boolean, viewModel: StrategyViewModel) {
    Text(if (form.editing) "규칙 수정" else "새 규칙", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    OutlinedTextField(
        value = form.ruleCode,
        onValueChange = viewModel::onRuleCode,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !form.editing,
        label = { Text("규칙 이름") },
        supportingText = if (form.editing) ({ Text("같은 이름의 규칙을 수정합니다.") }) else null,
    )
    Text("조건 · 일간 등락률이", style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = form.threshold,
            onValueChange = viewModel::onRuleThreshold,
            modifier = Modifier.width(130.dp),
            singleLine = true,
            label = { Text("기준 %") },
            suffix = { Text("%") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SignalOperator.entries.forEach { operator ->
                FilterChip(
                    selected = form.operator == operator,
                    onClick = { viewModel.onRuleOperator(operator) },
                    label = { Text(StrategyPresenter.operatorLabel(operator)) },
                )
            }
        }
    }
    Text("판단", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SignalAction.entries.forEach { action ->
            FilterChip(
                selected = form.action == action,
                onClick = { viewModel.onRuleAction(action) },
                label = { Text(StrategyPresenter.actionLabel(action)) },
            )
        }
    }
    OutlinedTextField(
        value = form.priority,
        onValueChange = viewModel::onRulePriority,
        modifier = Modifier.width(160.dp),
        singleLine = true,
        label = { Text("우선순위") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = viewModel::saveRule, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("규칙 저장") }
        TextButton(onClick = viewModel::cancelRule) { Text("취소") }
    }
}

// endregion

// region Preview

@Composable
private fun PreviewCard(panel: VersionPanel, viewModel: StrategyViewModel) {
    val preview = panel.preview
    SectionCard(title = "판단 미리보기") {
        Text(StrategyPresenter.PREVIEW_NOT_ORDER, style = MaterialTheme.typography.bodyMedium)
        if (panel.hasUnsavedChanges) WarningLine(StrategyPresenter.PREVIEW_USES_SAVED)
        val instrument = preview.instrument
        if (instrument == null) {
            OutlinedTextField(
                value = preview.query,
                onValueChange = viewModel::onPreviewQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("종목 검색") },
                placeholder = { Text("종목명 또는 종목코드") },
                leadingIcon = { Icon(BJStockIcons.Stocks, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.submitPreviewSearch() }),
            )
            when (val search = preview.search) {
                PreviewSearch.Idle -> Unit
                PreviewSearch.Searching -> LoadingLine("검색 중")
                PreviewSearch.NoResults -> BodyText(StrategyPresenter.SEARCH_NO_RESULTS)
                is PreviewSearch.Failed -> WarningLine(search.message)
                is PreviewSearch.Results -> search.instruments.forEach { found ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { viewModel.selectPreviewInstrument(found) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(found.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("${found.symbol} · ${found.board.name}", style = MaterialTheme.typography.bodyMedium)
                        }
                        Icon(BJStockIcons.ChevronRight, contentDescription = null)
                    }
                }
            }
        } else {
            PreviewSetup(preview, instrument, viewModel)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewSetup(preview: PreviewPanel, instrument: PreviewInstrument, viewModel: StrategyViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(instrument.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${instrument.symbol} · ${instrument.board.name}", style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = viewModel::clearPreviewInstrument) { Text("종목 변경") }
    }
    Text("평가일", style = MaterialTheme.typography.titleSmall)
    if (preview.dates.isEmpty()) {
        BodyText(StrategyPresenter.PREVIEW_NO_DATES)
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            preview.dates.forEach { date ->
                FilterChip(
                    selected = preview.dateInput == date.toString(),
                    onClick = { viewModel.pickPreviewDate(date) },
                    label = { Text(StrategyPresenter.dateChip(date)) },
                )
            }
        }
    }
    OutlinedTextField(
        value = preview.dateInput,
        onValueChange = viewModel::onPreviewDate,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("평가일 (예: 2026-09-29)") },
    )
    Button(
        onClick = viewModel::runPreview,
        enabled = preview.result != PreviewResultState.Loading,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) { Text("미리보기", style = MaterialTheme.typography.titleMedium) }
    when (val result = preview.result) {
        PreviewResultState.Idle -> Unit
        PreviewResultState.Loading -> LoadingLine("미리보기 중")
        is PreviewResultState.Failed -> WarningLine(result.message)
        is PreviewResultState.Shown -> PreviewResult(result.view)
    }
}

@Composable
private fun PreviewResult(view: PreviewView) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("미리보기 결과", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            LabeledRow("종목", view.instrument)
            LabeledRow("날짜", view.date)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("판단", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    view.decisionLabel,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = view.decision?.let { decisionColor(it) } ?: MaterialTheme.colorScheme.onSurface,
                )
            }
            view.sourceLabel?.let { LabeledRow("판단 방식", it) }
            view.score?.let { LabeledRow("팩터 점수", it) }
            view.ruleLine?.let { Text(it, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold) }
            view.notice?.let { WarningLine(it) }
            if (view.missingFactors.isNotEmpty()) {
                Text("부족한 팩터: ${view.missingFactors.joinToString(", ")}", style = MaterialTheme.typography.bodyMedium)
            }
            view.factorRows.forEach { row ->
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(row.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(row.code, style = MaterialTheme.typography.bodySmall)
                    Text("${row.score} · ${row.weight} · ${row.contribution}", style = MaterialTheme.typography.bodyLarge)
                    row.gateNote?.let { WarningLine("${StrategyPresenter.PREVIEW_GATE_FAILED} · $it") }
                }
            }
        }
    }
}

// endregion

// region Dialogs and shared pieces

@Composable
private fun CreateStrategyDialog(dialog: StrategyDialog.CreateStrategy, busy: Boolean, viewModel: StrategyViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissDialog,
        title = { Text("새 전략", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = dialog.name,
                    onValueChange = viewModel::onCreateNameChange,
                    singleLine = true,
                    label = { Text("전략 이름") },
                )
                OutlinedTextField(
                    value = dialog.code,
                    onValueChange = viewModel::onCreateCodeChange,
                    singleLine = true,
                    label = { Text("전략 코드") },
                    placeholder = { Text("예: MOMENTUM_BASIC") },
                )
                Text("만들면 작성중 V1이 함께 생성됩니다.", style = MaterialTheme.typography.bodyMedium)
                dialog.error?.let { WarningLine(it) }
            }
        },
        confirmButton = { Button(onClick = viewModel::confirmCreateStrategy, enabled = !busy) { Text("만들기") } },
        dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text("취소") } },
    )
}

@Composable
private fun StatusPill(badge: StatusBadge, large: Boolean = false) {
    val (icon, color) = when (badge.status) {
        StrategyVersionStatus.ACTIVE -> BJStockIcons.CheckCircle to MaterialTheme.colorScheme.primaryContainer
        StrategyVersionStatus.DRAFT -> BJStockIcons.Edit to MaterialTheme.colorScheme.tertiaryContainer
        StrategyVersionStatus.RETIRED -> BJStockIcons.Block to MaterialTheme.colorScheme.surfaceVariant
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

@Composable
private fun NoticeBanner(notice: Notice, onDismiss: () -> Unit) {
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
private fun SaveRow(busy: Boolean, onSave: () -> Unit, onDiscard: () -> Unit, saveLabel: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onSave, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(saveLabel) }
        TextButton(onClick = onDiscard) { Text("되돌리기") }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        singleLine = true,
        label = { Text(label) },
        textStyle = MaterialTheme.typography.titleMedium,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
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

/** One pane of the list-detail layout, scrolled on its own; the surrounding row does not scroll. */
@Composable
private fun PaneColumn(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
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

/** Secondary cue only; the decision is always written in text. */
@Composable
private fun decisionColor(decision: TradeDecision): Color = when (decision) {
    TradeDecision.BUY -> Color(0xFFC62828)
    TradeDecision.SELL -> Color(0xFF1565C0)
    TradeDecision.HOLD, TradeDecision.NO_ACTION -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun actionColor(action: SignalAction): Color = when (action) {
    SignalAction.BUY -> decisionColor(TradeDecision.BUY)
    SignalAction.SELL -> decisionColor(TradeDecision.SELL)
}

// endregion
