package com.mirunubi.bjstock.feature.strategy.template

import java.math.BigDecimal

// region Preview model (in memory only; there is no persisted template)

enum class TemplateTargetType(val label: String) {
    STOCK("종목"),
    STOCK_SET("종목 묶음"),
    THEME("테마"),
}

/** Only the Human-confirmed B-option exists; no other execution mode is offered. */
enum class TemplateEntryMode(val label: String) {
    PRE_CLOSE_ENTRY("장마감 전 판단 후 종가진입 시도"),
}

data class TemplateStock(val instrumentId: Long?, val symbol: String, val name: String)

data class TemplateTheme(val themeId: Long?, val name: String, val memberCount: Int?)

/**
 * A preview template. Percent magnitudes are always positive: take profit means entry × (1 + p/100) and
 * stop loss means entry × (1 − p/100).
 */
data class PreviewTemplate(
    val id: Long,
    val name: String,
    val example: Boolean,
    val unsaved: Boolean,
    val maPeriodDays: Int,
    val belowMaConsecutiveDays: Int,
    val volumeAverageLookbackDays: Int?,
    val volumeThresholdPercent: Int,
    val takeProfitPercent: BigDecimal,
    val stopLossPercent: BigDecimal,
    val maxHoldingTradingDays: Int,
    val entryMode: TemplateEntryMode,
    val targetType: TemplateTargetType,
    val stocks: List<TemplateStock>,
    val theme: TemplateTheme?,
)

data class TemplateForm(
    val name: String,
    val maPeriodDays: String,
    val belowMaConsecutiveDays: String,
    val volumeAverageLookbackDays: String,
    val volumeThresholdPercent: String,
    val takeProfitPercent: String,
    val stopLossPercent: String,
    val maxHoldingTradingDays: String,
    val entryMode: TemplateEntryMode,
    val targetType: TemplateTargetType,
    val stocks: List<TemplateStock>,
    val theme: TemplateTheme?,
)

enum class TemplateField {
    NAME,
    MA_PERIOD,
    BELOW_MA_DAYS,
    VOLUME_LOOKBACK,
    VOLUME_THRESHOLD,
    TAKE_PROFIT,
    STOP_LOSS,
    MAX_HOLDING,
}

data class FormValidation(val errors: Map<TemplateField, String>, val targetMissing: Boolean) {
    val canApply: Boolean get() = errors.isEmpty()
    val complete: Boolean get() = errors.isEmpty() && !targetMissing
}

data class TemplateCard(
    val id: Long,
    val name: String,
    val badges: List<String>,
    val target: String,
    val lines: List<String>,
    val complete: Boolean,
)

// endregion

/** Pure mapping and form logic for 전략 템플릿 (미리보기). Never touches a repository. */
object TemplatePreviewPresenter {
    const val SECTION_TITLE = "전략 템플릿 (미리보기)"
    const val DEMO_BANNER = "데모 — 저장되지 않으며 실제 전략이나 Run에 적용되지 않습니다."
    const val RESTART_NOTE = "앱을 다시 시작하면 미리보기 변경 내용이 사라질 수 있습니다."
    const val BOUNDARY_NOTE = "아래 '전략 목록'은 실제 저장된 전략과 버전입니다."
    const val LIST_EMPTY = "미리보기 템플릿이 없습니다. 새 템플릿을 만들어 보세요."
    const val LOOKBACK_UNRESOLVED = "미정"
    const val LOOKBACK_HINT = "아직 확정되지 않은 항목입니다."
    const val TARGET_UNSET = "대상 미정"
    const val INCOMPLETE = "대상을 선택해야 미리보기가 완성됩니다."
    const val VALIDATION_NOTE = "미리보기 입력 확인일 뿐이며 최종 허용 범위가 아닙니다."
    const val APPLY_LABEL = "미리보기 반영"
    const val CREATE_VERSION_LABEL = "전략 버전 만들기"
    const val CREATE_VERSION_DISABLED = "저장형 템플릿과 실행 규칙은 아직 구현되지 않았습니다."
    const val ENTRY_MODE_NOTE = "현재 미리보기는 이 진입 방식 하나만 표시합니다. 실제 실행과 연결되지 않습니다."
    const val UNLINKED_NOTE = "기본 제공 값이며 종목·테마 데이터와 연결되지 않았습니다."
    const val UNLINKED_MARKER = "데이터 미연결"
    const val SEARCH_EMPTY = "검색 결과가 없습니다."
    const val THEMES_EMPTY = "사용 중인 테마가 없습니다."
    const val LOAD_FAILED = "데이터를 불러오지 못했습니다."
    const val LOADING = "데이터를 불러오는 중입니다."

    const val BADGE_PREVIEW = "미리보기"
    const val BADGE_EXAMPLE = "예시"
    const val BADGE_UNSAVED = "미저장"
    const val BADGE_EDITING = "편집 중"

    // region Built-in and default templates

    fun initialTemplates(): List<PreviewTemplate> = listOf(
        baseline(
            id = 1,
            name = "삼성전자 종가돌파",
            example = false,
            targetType = TemplateTargetType.STOCK,
            stocks = listOf(TemplateStock(instrumentId = null, symbol = "005930", name = "삼성전자")),
        ),
        baseline(
            id = 2,
            name = "HBM 종가돌파 예시",
            example = true,
            targetType = TemplateTargetType.THEME,
            theme = TemplateTheme(themeId = null, name = "HBM", memberCount = null),
        ),
        baseline(
            id = 3,
            name = "방산 종가돌파 예시",
            example = true,
            targetType = TemplateTargetType.THEME,
            theme = TemplateTheme(themeId = null, name = "방산", memberCount = null),
        ),
    )

    /** A new template: baseline values, no target, volume lookback unresolved. */
    fun newTemplate(id: Long, name: String): PreviewTemplate =
        baseline(id = id, name = name, example = false, targetType = TemplateTargetType.STOCK).copy(unsaved = true)

    private fun baseline(
        id: Long,
        name: String,
        example: Boolean,
        targetType: TemplateTargetType,
        stocks: List<TemplateStock> = emptyList(),
        theme: TemplateTheme? = null,
    ) = PreviewTemplate(
        id = id,
        name = name,
        example = example,
        unsaved = false,
        maPeriodDays = 5,
        belowMaConsecutiveDays = 5,
        volumeAverageLookbackDays = null,
        volumeThresholdPercent = 200,
        takeProfitPercent = BigDecimal("5"),
        stopLossPercent = BigDecimal("5"),
        maxHoldingTradingDays = 10,
        entryMode = TemplateEntryMode.PRE_CLOSE_ENTRY,
        targetType = targetType,
        stocks = stocks,
        theme = theme,
    )

    // endregion

    // region Display

    fun card(template: PreviewTemplate) = TemplateCard(
        id = template.id,
        name = template.name,
        badges = badges(template, editing = false),
        target = targetLabel(template.targetType, template.stocks, template.theme) +
            if (isUnlinked(template.targetType, template.stocks, template.theme)) " · $UNLINKED_MARKER" else "",
        lines = listOf(
            "${template.maPeriodDays}일선 / 아래 ${template.belowMaConsecutiveDays}일",
            "거래량 ${template.volumeThresholdPercent}% · 평균 기간 ${lookback(template.volumeAverageLookbackDays)}",
            "익절 ${takeProfit(template.takeProfitPercent)}",
            "손절 ${stopLoss(template.stopLossPercent)}",
            "최대 ${template.maxHoldingTradingDays}일",
        ),
        complete = !targetMissing(template.targetType, template.stocks, template.theme),
    )

    fun badges(template: PreviewTemplate, editing: Boolean): List<String> = listOfNotNull(
        BADGE_PREVIEW,
        BADGE_EXAMPLE.takeIf { template.example },
        BADGE_UNSAVED.takeIf { template.unsaved },
        BADGE_EDITING.takeIf { editing },
    )

    fun targetLabel(type: TemplateTargetType, stocks: List<TemplateStock>, theme: TemplateTheme?): String = when (type) {
        TemplateTargetType.STOCK -> stocks.firstOrNull()?.let { "${type.label} · ${stockLabel(it)}" }
        TemplateTargetType.STOCK_SET -> stocks.takeIf { it.isNotEmpty() }
            ?.let { list -> "${type.label} · ${list.joinToString(", ") { it.name }} (${list.size}개)" }
        TemplateTargetType.THEME -> theme?.let { "${type.label} · ${themeLabel(it)}" }
    } ?: "${type.label} · $TARGET_UNSET"

    fun hasUnlinkedTarget(form: TemplateForm): Boolean = isUnlinked(form.targetType, form.stocks, form.theme)

    /** True when a displayed target has no Instrument/Theme id behind it. An unset target is not "unlinked". */
    fun isUnlinked(type: TemplateTargetType, stocks: List<TemplateStock>, theme: TemplateTheme?): Boolean = when (type) {
        TemplateTargetType.STOCK -> stocks.firstOrNull()?.let { it.instrumentId == null } ?: false
        TemplateTargetType.STOCK_SET -> stocks.any { it.instrumentId == null }
        TemplateTargetType.THEME -> theme != null && theme.themeId == null
    }

    fun stockLabel(stock: TemplateStock): String = "${stock.name} ${stock.symbol}"

    fun themeLabel(theme: TemplateTheme): String =
        theme.memberCount?.let { "${theme.name} (등록 종목 ${it}개)" } ?: theme.name

    fun lookback(days: Int?): String = days?.let { "${it}일" } ?: LOOKBACK_UNRESOLVED

    /** Positive magnitude shown as a gain: 5 → +5%. */
    fun takeProfit(percent: BigDecimal): String = "+${plain(percent)}%"

    /** Positive magnitude shown as a loss: 5 → -5%. */
    fun stopLoss(percent: BigDecimal): String = "-${plain(percent)}%"

    private fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

    // endregion

    // region Form

    fun formOf(template: PreviewTemplate) = TemplateForm(
        name = template.name,
        maPeriodDays = template.maPeriodDays.toString(),
        belowMaConsecutiveDays = template.belowMaConsecutiveDays.toString(),
        volumeAverageLookbackDays = template.volumeAverageLookbackDays?.toString().orEmpty(),
        volumeThresholdPercent = template.volumeThresholdPercent.toString(),
        takeProfitPercent = plain(template.takeProfitPercent),
        stopLossPercent = plain(template.stopLossPercent),
        maxHoldingTradingDays = template.maxHoldingTradingDays.toString(),
        entryMode = template.entryMode,
        targetType = template.targetType,
        stocks = template.stocks,
        theme = template.theme,
    )

    fun isDirty(template: PreviewTemplate, form: TemplateForm): Boolean = normalized(form) != normalized(formOf(template))

    private fun normalized(form: TemplateForm) = form.copy(
        name = form.name.trim(),
        maPeriodDays = form.maPeriodDays.trim(),
        belowMaConsecutiveDays = form.belowMaConsecutiveDays.trim(),
        volumeAverageLookbackDays = form.volumeAverageLookbackDays.trim(),
        volumeThresholdPercent = form.volumeThresholdPercent.trim(),
        takeProfitPercent = form.takeProfitPercent.trim().toBigDecimalOrNull()?.let(::plain) ?: form.takeProfitPercent.trim(),
        stopLossPercent = form.stopLossPercent.trim().toBigDecimalOrNull()?.let(::plain) ?: form.stopLossPercent.trim(),
        maxHoldingTradingDays = form.maxHoldingTradingDays.trim(),
    )

    /** Form-level preview checks only; these are not engine or product limits. */
    fun validate(form: TemplateForm): FormValidation {
        val errors = buildMap {
            if (form.name.isBlank()) put(TemplateField.NAME, "템플릿 이름을 입력하세요.")
            requiredInt(form.maPeriodDays)?.let { put(TemplateField.MA_PERIOD, it) }
            requiredInt(form.belowMaConsecutiveDays)?.let { put(TemplateField.BELOW_MA_DAYS, it) }
            requiredInt(form.volumeThresholdPercent)?.let { put(TemplateField.VOLUME_THRESHOLD, it) }
            requiredInt(form.maxHoldingTradingDays)?.let { put(TemplateField.MAX_HOLDING, it) }
            if (form.volumeAverageLookbackDays.isNotBlank()) {
                requiredInt(form.volumeAverageLookbackDays)?.let { put(TemplateField.VOLUME_LOOKBACK, it) }
            }
            magnitude(form.takeProfitPercent, cap = null, shownAs = "+5%")?.let { put(TemplateField.TAKE_PROFIT, it) }
            magnitude(form.stopLossPercent, cap = HUNDRED, shownAs = "-5%")?.let { put(TemplateField.STOP_LOSS, it) }
        }
        return FormValidation(errors, targetMissing(form.targetType, form.stocks, form.theme))
    }

    fun targetMissing(type: TemplateTargetType, stocks: List<TemplateStock>, theme: TemplateTheme?): Boolean = when (type) {
        TemplateTargetType.STOCK -> stocks.size != 1
        TemplateTargetType.STOCK_SET -> stocks.isEmpty()
        TemplateTargetType.THEME -> theme == null
    }

    /** Applies a valid form to the in-memory template; returns null when the form has errors. */
    fun apply(template: PreviewTemplate, form: TemplateForm): PreviewTemplate? {
        if (!validate(form).canApply) return null
        return template.copy(
            name = form.name.trim(),
            unsaved = true,
            maPeriodDays = form.maPeriodDays.trim().toInt(),
            belowMaConsecutiveDays = form.belowMaConsecutiveDays.trim().toInt(),
            volumeAverageLookbackDays = form.volumeAverageLookbackDays.trim().takeIf { it.isNotEmpty() }?.toInt(),
            volumeThresholdPercent = form.volumeThresholdPercent.trim().toInt(),
            takeProfitPercent = form.takeProfitPercent.trim().toBigDecimal(),
            stopLossPercent = form.stopLossPercent.trim().toBigDecimal(),
            maxHoldingTradingDays = form.maxHoldingTradingDays.trim().toInt(),
            entryMode = form.entryMode,
            targetType = form.targetType,
            stocks = form.stocks,
            theme = form.theme,
        )
    }

    /** Changing the target type keeps only selections that still make sense for the new type. */
    fun withTargetType(form: TemplateForm, type: TemplateTargetType): TemplateForm = when (type) {
        TemplateTargetType.STOCK -> form.copy(targetType = type, stocks = form.stocks.take(1))
        TemplateTargetType.STOCK_SET -> form.copy(targetType = type)
        TemplateTargetType.THEME -> form.copy(targetType = type)
    }

    /** STOCK replaces the single selection; STOCK_SET toggles membership. */
    fun withStock(form: TemplateForm, option: InstrumentOption): TemplateForm {
        val stock = TemplateStock(option.instrumentId, option.symbol, option.name)
        return when (form.targetType) {
            TemplateTargetType.STOCK -> form.copy(stocks = listOf(stock))
            TemplateTargetType.STOCK_SET ->
                if (form.stocks.any { it.symbol == stock.symbol }) {
                    form.copy(stocks = form.stocks.filterNot { it.symbol == stock.symbol })
                } else {
                    form.copy(stocks = form.stocks + stock)
                }
            TemplateTargetType.THEME -> form
        }
    }

    fun withoutStock(form: TemplateForm, symbol: String): TemplateForm =
        form.copy(stocks = form.stocks.filterNot { it.symbol == symbol })

    fun withTheme(form: TemplateForm, option: ThemeOption): TemplateForm =
        form.copy(theme = TemplateTheme(option.themeId, option.name, option.memberCount))

    fun duplicateName(name: String): String = "$name 복사본"

    private fun requiredInt(raw: String): String? {
        val value = raw.trim().toIntOrNull() ?: return "1 이상의 정수를 입력하세요."
        return if (value >= 1) null else "1 이상의 정수를 입력하세요."
    }

    private fun magnitude(raw: String, cap: BigDecimal?, shownAs: String): String? {
        val text = raw.trim()
        if (text.startsWith("-") || text.startsWith("+")) return "부호 없이 크기만 입력하세요. 예: 5 → $shownAs"
        val value = text.toBigDecimalOrNull() ?: return "0보다 큰 숫자를 입력하세요."
        if (value.signum() <= 0) return "0보다 큰 숫자를 입력하세요."
        if (value.scale() > 2) return "소수점 둘째 자리까지 입력하세요."
        if (cap != null && value >= cap) return "100보다 작은 값을 입력하세요."
        return null
    }

    private val HUNDRED = BigDecimal("100")

    // endregion
}
