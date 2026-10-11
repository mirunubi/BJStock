package com.mirunubi.bjstock.feature.strategy.template

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mirunubi.bjstock.ui.icons.BJStockIcons
import com.mirunubi.bjstock.feature.strategy.template.TemplatePreviewPresenter as P

// region List section (top of the 전략 tab)

/**
 * [onOpen] / [onCreate] let the 전략 tab close the real strategy layers first; [selectedId] and [showMessage] are for
 * the list-detail layout only. The defaults are the single-pane behavior.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemplatePreviewSection(
    state: TemplatePreviewUiState,
    viewModel: TemplatePreviewViewModel,
    onOpen: (Long) -> Unit = viewModel::open,
    onCreate: () -> Unit = viewModel::create,
    selectedId: Long? = null,
    showMessage: Boolean = true,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(BJStockIcons.Strategy, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(P.SECTION_TITLE, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onCreate, modifier = Modifier.heightIn(min = 48.dp)) { Text("새 템플릿") }
            }
            DemoBanner()
            if (showMessage) state.message?.let { MessageLine(it, viewModel::clearMessage) }
            if (state.cards.isEmpty()) {
                Text(P.LIST_EMPTY, style = MaterialTheme.typography.bodyLarge)
            } else {
                state.cards.forEach { card ->
                    TemplateCardItem(
                        card = card,
                        selected = card.id == selectedId,
                        onOpen = { onOpen(card.id) },
                        onDuplicate = { viewModel.duplicate(card.id) },
                        onRename = { viewModel.requestRename(card.id) },
                        onDelete = { viewModel.requestDelete(card.id) },
                    )
                }
            }
            HorizontalDivider()
            Text(P.BOUNDARY_NOTE, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateCardItem(
    card: TemplateCard,
    selected: Boolean,
    onOpen: () -> Unit,
    onDuplicate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onOpen)
    OutlinedCard(
        modifier = if (selected) modifier.semantics { this.selected = true } else modifier,
        colors = if (selected) {
            CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.outlinedCardColors()
        },
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else CardDefaults.outlinedCardBorder(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(card.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(BJStockIcons.ChevronRight, contentDescription = null)
            }
            Badges(card.badges)
            Text(card.target, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            card.lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
            if (!card.complete) WarningLine(P.INCOMPLETE)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDuplicate, modifier = Modifier.heightIn(min = 48.dp)) { Text("복제") }
                TextButton(onClick = onRename, modifier = Modifier.heightIn(min = 48.dp)) { Text("이름 변경") }
                TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) { Text("삭제") }
            }
        }
    }
}

// endregion

// region Detail / editor layer

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemplatePreviewDetail(state: TemplatePreviewUiState, viewModel: TemplatePreviewViewModel) {
    val template = state.selected ?: return
    val form = state.form ?: return
    val validation = state.validation ?: return
    val dirty = state.dirty

    DemoBanner()
    state.message?.let { MessageLine(it, viewModel::clearMessage) }
    Text(template.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Badges(P.badges(template, editing = dirty))

    PreviewSection("기본 정보") {
        OutlinedTextField(
            value = form.name,
            onValueChange = { text -> viewModel.updateForm { it.copy(name = text) } },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("템플릿 이름") },
            isError = TemplateField.NAME in validation.errors,
            supportingText = validation.errors[TemplateField.NAME]?.let { { Text(it) } },
        )
    }

    PreviewSection("대상") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TemplateTargetType.entries.forEach { type ->
                FilterChip(
                    selected = form.targetType == type,
                    onClick = { viewModel.setTargetType(type) },
                    label = { Text(type.label) },
                    leadingIcon = if (form.targetType == type) ({ Icon(BJStockIcons.CheckCircle, contentDescription = null) }) else null,
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        Text(P.targetLabel(form.targetType, form.stocks, form.theme), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        if (P.hasUnlinkedTarget(form)) Text(P.UNLINKED_NOTE, style = MaterialTheme.typography.bodyMedium)
        when (form.targetType) {
            TemplateTargetType.STOCK, TemplateTargetType.STOCK_SET -> StockPicker(state, form, viewModel)
            TemplateTargetType.THEME -> ThemePicker(state, form, viewModel)
        }
    }

    PreviewSection("진입 조건") {
        PreviewNumberField(
            label = "이동평균 기간 (일)",
            value = form.maPeriodDays,
            onChange = { v -> viewModel.updateForm { it.copy(maPeriodDays = v) } },
            error = validation.errors[TemplateField.MA_PERIOD],
        )
        PreviewNumberField(
            label = "이동평균 아래 연속 일수",
            value = form.belowMaConsecutiveDays,
            onChange = { v -> viewModel.updateForm { it.copy(belowMaConsecutiveDays = v) } },
            error = validation.errors[TemplateField.BELOW_MA_DAYS],
        )
        PreviewNumberField(
            label = "거래량 평균 기간 (일)",
            value = form.volumeAverageLookbackDays,
            onChange = { v -> viewModel.updateForm { it.copy(volumeAverageLookbackDays = v) } },
            error = validation.errors[TemplateField.VOLUME_LOOKBACK],
            placeholder = P.LOOKBACK_UNRESOLVED,
            hint = if (form.volumeAverageLookbackDays.isBlank()) "${P.LOOKBACK_UNRESOLVED} — ${P.LOOKBACK_HINT}" else null,
        )
        PreviewNumberField(
            label = "거래량 기준 (평균 대비 %)",
            value = form.volumeThresholdPercent,
            onChange = { v -> viewModel.updateForm { it.copy(volumeThresholdPercent = v) } },
            error = validation.errors[TemplateField.VOLUME_THRESHOLD],
        )
    }

    PreviewSection("청산 조건") {
        PreviewNumberField(
            label = "익절 (%)",
            value = form.takeProfitPercent,
            onChange = { v -> viewModel.updateForm { it.copy(takeProfitPercent = v) } },
            error = validation.errors[TemplateField.TAKE_PROFIT],
            decimal = true,
            hint = form.takeProfitPercent.trim().toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
                ?.let { "진입가 대비 ${P.takeProfit(it)}에서 익절" },
        )
        PreviewNumberField(
            label = "손절 (%, 크기만 입력)",
            value = form.stopLossPercent,
            onChange = { v -> viewModel.updateForm { it.copy(stopLossPercent = v) } },
            error = validation.errors[TemplateField.STOP_LOSS],
            decimal = true,
            hint = form.stopLossPercent.trim().toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
                ?.let { "진입가 대비 ${P.stopLoss(it)}에서 손절" },
        )
        PreviewNumberField(
            label = "최대 보유 (거래일)",
            value = form.maxHoldingTradingDays,
            onChange = { v -> viewModel.updateForm { it.copy(maxHoldingTradingDays = v) } },
            error = validation.errors[TemplateField.MAX_HOLDING],
        )
    }

    PreviewSection("진입 방식") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TemplateEntryMode.entries.forEach { mode ->
                FilterChip(
                    selected = form.entryMode == mode,
                    onClick = { viewModel.updateForm { it.copy(entryMode = mode) } },
                    label = { Text(mode.label) },
                    leadingIcon = { Icon(BJStockIcons.CheckCircle, contentDescription = null) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        Text(P.ENTRY_MODE_NOTE, style = MaterialTheme.typography.bodyMedium)
    }

    Text(P.VALIDATION_NOTE, style = MaterialTheme.typography.bodyMedium)
    if (validation.targetMissing) WarningLine(P.INCOMPLETE)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = viewModel::applyPreview,
            enabled = dirty && validation.canApply,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(P.APPLY_LABEL) }
        TextButton(onClick = viewModel::revert, enabled = dirty, modifier = Modifier.heightIn(min = 48.dp)) { Text("되돌리기") }
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { viewModel.duplicate(template.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("복제") }
        OutlinedButton(onClick = { viewModel.requestRename(template.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("이름 변경") }
        OutlinedButton(onClick = { viewModel.requestDelete(template.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("삭제") }
    }

    PreviewSection(P.CREATE_VERSION_LABEL) {
        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(BJStockIcons.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(P.CREATE_VERSION_LABEL)
        }
        Text(P.CREATE_VERSION_DISABLED, style = MaterialTheme.typography.bodyLarge)
    }
    DemoBanner()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StockPicker(state: TemplatePreviewUiState, form: TemplateForm, viewModel: TemplatePreviewViewModel) {
    if (form.stocks.isNotEmpty()) {
        Text("선택한 종목", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        form.stocks.forEach { stock ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(BJStockIcons.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(P.stockLabel(stock), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { viewModel.removeStock(stock.symbol) }) {
                    Icon(BJStockIcons.Close, contentDescription = "${stock.name} 선택 해제")
                }
            }
        }
    }
    Text(
        if (form.targetType == TemplateTargetType.STOCK) "종목 하나를 고릅니다." else "여러 종목을 고를 수 있습니다.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.stockQuery,
            onValueChange = viewModel::updateStockQuery,
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text("종목명 또는 코드") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.searchStocks() }),
        )
        Button(onClick = viewModel::searchStocks, modifier = Modifier.heightIn(min = 48.dp)) { Text("검색") }
    }
    when (val search = state.stockSearch) {
        PickerState.Idle -> Unit
        PickerState.Loading -> LoadingLine(P.LOADING)
        is PickerState.Empty -> Text(search.message, style = MaterialTheme.typography.bodyLarge)
        is PickerState.Failed -> {
            WarningLine(search.message)
            OutlinedButton(onClick = viewModel::searchStocks, modifier = Modifier.heightIn(min = 48.dp)) { Text("다시 시도") }
        }
        is PickerState.Loaded -> search.items.forEach { option ->
            val chosen = form.stocks.any { it.symbol == option.symbol }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { viewModel.selectStock(option) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${option.name} ${option.symbol}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (chosen) Icon(BJStockIcons.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (chosen) "선택됨" else "선택", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemePicker(state: TemplatePreviewUiState, form: TemplateForm, viewModel: TemplatePreviewViewModel) {
    when (val themes = state.themes) {
        PickerState.Idle, PickerState.Loading -> LoadingLine(P.LOADING)
        is PickerState.Empty -> Text(themes.message, style = MaterialTheme.typography.bodyLarge)
        is PickerState.Failed -> {
            WarningLine(themes.message)
            OutlinedButton(onClick = viewModel::loadThemes, modifier = Modifier.heightIn(min = 48.dp)) { Text("다시 시도") }
        }
        is PickerState.Loaded -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            themes.items.forEach { option ->
                val chosen = form.theme?.themeId == option.themeId
                FilterChip(
                    selected = chosen,
                    onClick = { viewModel.selectTheme(option) },
                    label = { Text("${option.name} · ${option.memberCount}개") },
                    leadingIcon = if (chosen) ({ Icon(BJStockIcons.CheckCircle, contentDescription = null) }) else null,
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

// endregion

// region Dialogs

@Composable
fun TemplatePreviewDialogs(state: TemplatePreviewUiState, viewModel: TemplatePreviewViewModel) {
    when (val dialog = state.dialog) {
        null -> Unit
        is TemplateDialog.Rename -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text("템플릿 이름 변경 (미리보기)", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dialog.text,
                        onValueChange = viewModel::updateRenameText,
                        singleLine = true,
                        label = { Text("템플릿 이름") },
                        isError = dialog.text.isBlank(),
                        supportingText = if (dialog.text.isBlank()) ({ Text("템플릿 이름을 입력하세요.") }) else null,
                    )
                    Text(P.DEMO_BANNER, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                Button(onClick = viewModel::confirmRename, enabled = dialog.text.isNotBlank()) { Text("이름 바꾸기") }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text("취소") } },
        )
        is TemplateDialog.Delete -> AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text("미리보기에서 지우시겠습니까?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "'${dialog.name}'을(를) 미리보기 목록에서만 지웁니다. 실제 전략이나 Run에는 영향이 없습니다.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = { Button(onClick = viewModel::confirmDelete) { Text("지우기") } },
            dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text("취소") } },
        )
    }
}

// endregion

// region Helpers

@Composable
private fun DemoBanner() {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(BJStockIcons.Warning, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(P.DEMO_BANNER, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(P.RESTART_NOTE, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Badges(labels: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEach { label -> Badge(label, badgeIcon(label)) }
    }
}

private fun badgeIcon(label: String): ImageVector? = when (label) {
    P.BADGE_EDITING -> BJStockIcons.Edit
    P.BADGE_UNSAVED -> BJStockIcons.Warning
    else -> null
}

@Composable
private fun Badge(label: String, icon: ImageVector?) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.let {
                Icon(it, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun MessageLine(message: String, onDismiss: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(BJStockIcons.CheckCircle, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) { Icon(BJStockIcons.Close, contentDescription = "알림 닫기") }
        }
    }
}

@Composable
private fun PreviewSection(title: String, content: @Composable () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun PreviewNumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    error: String?,
    decimal: Boolean = false,
    placeholder: String? = null,
    hint: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        isError = error != null,
        supportingText = (error ?: hint)?.let { { Text(it) } },
        textStyle = MaterialTheme.typography.titleMedium,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
    )
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
