package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.tax.TaxRegime;
import com.ebim.tms.settlement.domain.tax.TaxRegimes;
import com.ebim.tms.settlement.domain.tax.WithholdingKind;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.settings.FiscalSettings;
import com.ebim.tms.shared.settings.FiscalSettingsPort;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and edits a company's fiscal regime (V66, ADR-027): its fiscal country and the overrides of
 * the regime's defaults. The validation is here because it is the regime's - which withholdings a
 * country has - and the write goes through {@link FiscalSettingsPort} to {@code iam}, which owns
 * {@code tms.company_settings}.
 *
 * <p>What changes is what every <b>later</b> document is computed with: a document keeps the regime it
 * was received under ({@code carrier_invoice.fiscal_country}), so no figure already received moves.
 */
@Service
public class FiscalSettingsService {

    private final FiscalSettingsResolver resolver;
    private final FiscalSettingsPort fiscalSettingsPort;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;

    public FiscalSettingsService(FiscalSettingsResolver resolver, FiscalSettingsPort fiscalSettingsPort,
            AuditActorProvider auditActorProvider, AuditRecorder auditRecorder) {
        this.resolver = resolver;
        this.fiscalSettingsPort = fiscalSettingsPort;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
    }

    @Transactional(readOnly = true)
    public TaxRegimeView get(CompanyScope scope) {
        FiscalSettings stored = resolver.storedOf(scope.companyId());
        return TaxRegimeView.of(resolver.rulesOf(scope.companyId()), stored);
    }

    @Transactional
    public TaxRegimeView replace(CompanyScope scope, FiscalSettingsRequest request) {
        java.util.UUID actorId = auditActorProvider.requireAppUserId();
        FiscalSettings before = resolver.storedOf(scope.companyId());
        String fiscalCountry = request.fiscalCountry() == null || request.fiscalCountry().isBlank() ? null
                : request.fiscalCountry().trim().toUpperCase(Locale.ROOT);
        String country = fiscalCountry != null ? fiscalCountry : before.defaultCountry();
        TaxRegime regime = TaxRegimes.forCountry(country);

        List<FiscalSettingsRequest.Withholding> requested =
                request.withholdings() == null ? List.of() : request.withholdings();
        Set<WithholdingKind> seen = EnumSet.noneOf(WithholdingKind.class);
        for (FiscalSettingsRequest.Withholding withholding : requested) {
            if (!regime.withholdingKinds().contains(withholding.kind())) {
                throw new InvalidRequestException(withholding.kind() + " is not a withholding of the "
                        + country + " tax regime" + (regime.withholdingKinds().isEmpty() ? ", which has none."
                                : " (" + regime.withholdingKinds() + ")."));
            }
            if (!seen.add(withholding.kind())) {
                throw new InvalidRequestException(withholding.kind() + " is listed twice.");
            }
        }
        FiscalSettings after = new FiscalSettings(before.defaultCountry(), fiscalCountry, request.vatRate(),
                request.freightVatRate(), requested.stream()
                        .map(withholding -> new FiscalSettings.WithholdingSetting(withholding.kind().name(),
                                withholding.enabled(),
                                blankToNull(withholding.code()) == null ? null
                                        : withholding.code().trim().toUpperCase(Locale.ROOT),
                                withholding.rate(), withholding.thresholdAmount()))
                        .toList());
        fiscalSettingsPort.replaceFiscalSettings(scope.companyId(), after, actorId);

        // A company setting like the others: UPDATE on COMPANY, with the section and both versions.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("section", "FISCAL_REGIME");
        details.put("before", describe(before));
        details.put("after", describe(after));
        auditRecorder.record(scope, AuditAggregateType.COMPANY, scope.companyId(), AuditAction.UPDATE, details);
        return get(scope);
    }

    private static Map<String, Object> describe(FiscalSettings settings) {
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("fiscalCountry", settings.fiscalCountry());
        described.put("vatRate", settings.vatRate() == null ? null : settings.vatRate().toPlainString());
        described.put("freightVatRate", settings.freightVatRate() == null ? null : settings.freightVatRate().toPlainString());
        described.put("withholdings", settings.withholdings().stream().map(withholding -> {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("kind", withholding.kind());
            one.put("enabled", withholding.enabled());
            one.put("code", withholding.code());
            one.put("rate", withholding.rate() == null ? null : withholding.rate().toPlainString());
            one.put("thresholdAmount", withholding.thresholdAmount() == null ? null
                    : withholding.thresholdAmount().toPlainString());
            return one;
        }).toList());
        return described;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
