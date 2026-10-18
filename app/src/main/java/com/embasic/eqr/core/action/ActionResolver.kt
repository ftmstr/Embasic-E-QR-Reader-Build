package com.embasic.eqr.core.action

import com.embasic.eqr.core.payload.ParsedPayload
import com.embasic.eqr.core.risk.RiskReport
import com.embasic.eqr.core.risk.RiskSeverity

enum class ActionKind {
    COPY,
    SHARE,
    OPEN_URL,
    OPEN_CLEAN_URL,
    OPEN_DEEP_LINK,
    CONNECT_WIFI,
    ADD_CONTACT,
    DIAL,
    COMPOSE_SMS,
    COMPOSE_EMAIL,
    ADD_CALENDAR,
    OPEN_MAP,
    WEB_SEARCH,
    REGENERATE_QR,
}

data class ResolvedAction(
    val kind: ActionKind,
    val requiresConfirmation: Boolean = false,
)

interface ActionResolver {
    fun resolve(
        payload: ParsedPayload,
        riskReport: RiskReport,
    ): List<ResolvedAction>
}

class DefaultActionResolver : ActionResolver {
    override fun resolve(
        payload: ParsedPayload,
        riskReport: RiskReport,
    ): List<ResolvedAction> {
        val common = mutableListOf(
            ResolvedAction(ActionKind.COPY),
            ResolvedAction(ActionKind.SHARE),
            ResolvedAction(ActionKind.REGENERATE_QR),
        )

        when (payload) {
            is ParsedPayload.Url -> {
                val confirmation = riskReport.severity == RiskSeverity.STRONG
                riskReport.cleanedUrl?.let {
                    common += ResolvedAction(
                        ActionKind.OPEN_CLEAN_URL,
                        requiresConfirmation = confirmation,
                    )
                }
                common += ResolvedAction(
                    ActionKind.OPEN_URL,
                    requiresConfirmation = confirmation,
                )
            }

            is ParsedPayload.Wifi ->
                common += ResolvedAction(ActionKind.CONNECT_WIFI)

            is ParsedPayload.Contact ->
                common += ResolvedAction(ActionKind.ADD_CONTACT)

            is ParsedPayload.Phone ->
                common += ResolvedAction(ActionKind.DIAL)

            is ParsedPayload.Sms ->
                common += ResolvedAction(ActionKind.COMPOSE_SMS)

            is ParsedPayload.Email ->
                common += ResolvedAction(ActionKind.COMPOSE_EMAIL)

            is ParsedPayload.Calendar ->
                common += ResolvedAction(ActionKind.ADD_CALENDAR)

            is ParsedPayload.Geo ->
                common += ResolvedAction(ActionKind.OPEN_MAP)

            is ParsedPayload.ProductCode ->
                common += ResolvedAction(ActionKind.WEB_SEARCH)

            is ParsedPayload.Text,
            is ParsedPayload.Unknown -> Unit

            is ParsedPayload.DeepLink ->
                common += ResolvedAction(
                    ActionKind.OPEN_DEEP_LINK,
                    requiresConfirmation = true,
                )
        }

        return common.distinctBy(ResolvedAction::kind)
    }
}
