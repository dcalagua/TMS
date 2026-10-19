package com.ebim.tms.settlement.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebim.tms.settlement.domain.tax.TaxRegimeCode;
import com.ebim.tms.settlement.domain.tax.TaxRules;
import com.ebim.tms.settlement.domain.tax.WithholdingKind;
import com.ebim.tms.settlement.domain.tax.WithholdingRule;
import com.ebim.tms.shared.settings.FiscalSettings;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * A company's regime comes from its fiscal country (else its default country), and each of its own
 * values wins over the deployment's default, a null being the default (V66, ADR-027, D-06). The
 * defaults are bound from the real {@code application.yml}, so the test also proves what an unset
 * deployment applies.
 */
class FiscalSettingsResolverTest {

    private static TaxSettingsProperties properties;

    @BeforeAll
    static void bindTheRealConfiguration() throws IOException {
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        MutablePropertySources sources = new MutablePropertySources();
        loaded.forEach(sources::addLast);
        properties = new Binder(ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources))
                .bind("tms.finance.tax", TaxSettingsProperties.class)
                .orElseThrow(() -> new IllegalStateException("tms.finance.tax is not bound"));
    }

    private static WithholdingRule rule(TaxRules rules, WithholdingKind kind) {
        return rules.rule(kind).orElseThrow();
    }

    @Test
    @DisplayName("a Peruvian company that stored nothing gets today's Peruvian defaults")
    void peruDefaults() {
        TaxRules rules = FiscalSettingsResolver.resolve(FiscalSettings.unset("PE"), properties);
        assertThat(rules.regime().code()).isEqualTo(TaxRegimeCode.PE);
        assertThat(rules.country()).isEqualTo("PE");
        assertThat(rules.vatRate()).isEqualByComparingTo("18.00");
        assertThat(rules.freightVatRate()).isEqualByComparingTo("18.00");
        assertThat(rules.allowsVatRate(new BigDecimal("18"))).isTrue();
        assertThat(rules.allowsVatRate(BigDecimal.ZERO)).isTrue();
        WithholdingRule detraction = rule(rules, WithholdingKind.PE_DETRACTION);
        assertThat(detraction.enabled()).isTrue();
        assertThat(detraction.code()).isEqualTo("027");
        assertThat(detraction.rate()).isEqualByComparingTo("4.00");
        assertThat(detraction.thresholdAmount()).isEqualByComparingTo("400.00");
        WithholdingRule retention = rule(rules, WithholdingKind.PE_RETENTION);
        assertThat(retention.enabled()).isFalse();
        assertThat(retention.rate()).isEqualByComparingTo("3.00");
    }

    @Test
    @DisplayName("an Ecuadorian company: IVA 15, freight 0, rates 0/5/15, IR 1% code 310 on, IVA withholding off")
    void ecuadorDefaults() {
        TaxRules rules = FiscalSettingsResolver.resolve(FiscalSettings.unset("EC"), properties);
        assertThat(rules.regime().code()).isEqualTo(TaxRegimeCode.EC);
        assertThat(rules.vatRate()).isEqualByComparingTo("15.00");
        assertThat(rules.freightVatRate()).isEqualByComparingTo("0.00");
        assertThat(rules.allowedVatRates()).extracting(BigDecimal::stripTrailingZeros)
                .containsExactlyInAnyOrder(BigDecimal.ZERO, new BigDecimal("5"), new BigDecimal("15"));
        WithholdingRule income = rule(rules, WithholdingKind.EC_INCOME_TAX_WITHHOLDING);
        assertThat(income.enabled()).isTrue();
        assertThat(income.code()).isEqualTo("310");
        assertThat(income.rate()).isEqualByComparingTo("1.00");
        WithholdingRule vat = rule(rules, WithholdingKind.EC_VAT_WITHHOLDING);
        assertThat(vat.enabled()).isFalse();
        assertThat(vat.rate()).isEqualByComparingTo("70.00");
    }

    @Test
    @DisplayName("the fiscal country wins over the default country; without it the default country rules")
    void countrySelection() {
        assertThat(FiscalSettingsResolver.resolve(new FiscalSettings("PE", "EC", null, null, List.of()), properties)
                .regime().code()).isEqualTo(TaxRegimeCode.EC);
        assertThat(FiscalSettingsResolver.resolve(new FiscalSettings("EC", null, null, null, List.of()), properties)
                .country()).isEqualTo("EC");
    }

    @Test
    @DisplayName("any other country: the generic regime, no withholdings, any rate, no rate assumed")
    void generic() {
        TaxRules rules = FiscalSettingsResolver.resolve(FiscalSettings.unset("CO"), properties);
        assertThat(rules.regime().code()).isEqualTo(TaxRegimeCode.GENERIC);
        assertThat(rules.country()).isEqualTo("CO");
        assertThat(rules.withholdings()).isEmpty();
        assertThat(rules.vatRate()).isNull();
        assertThat(rules.allowedVatRates()).isEmpty();
        assertThat(rules.allowsVatRate(new BigDecimal("19"))).isTrue();
        TaxRules withRate = FiscalSettingsResolver.resolve(new FiscalSettings("US", null, new BigDecimal("8.25"),
                null, List.of()), properties);
        assertThat(withRate.vatRate()).isEqualByComparingTo("8.25");
    }

    @Test
    @DisplayName("each stored value replaces only its own default, false included; the own rate becomes allowed")
    void companyOverrides() {
        TaxRules rules = FiscalSettingsResolver.resolve(new FiscalSettings("EC", null, new BigDecimal("16.00"), null,
                List.of(new FiscalSettings.WithholdingSetting("EC_VAT_WITHHOLDING", true, null, new BigDecimal("30.00"),
                                null),
                        new FiscalSettings.WithholdingSetting("EC_INCOME_TAX_WITHHOLDING", false, null, null, null),
                        // A kind of another regime is ignored, not applied.
                        new FiscalSettings.WithholdingSetting("PE_DETRACTION", true, "027", BigDecimal.TEN, null))),
                properties);
        assertThat(rules.vatRate()).isEqualByComparingTo("16.00");
        assertThat(rules.allowsVatRate(new BigDecimal("16"))).isTrue();
        assertThat(rule(rules, WithholdingKind.EC_VAT_WITHHOLDING).enabled()).isTrue();
        assertThat(rule(rules, WithholdingKind.EC_VAT_WITHHOLDING).rate()).isEqualByComparingTo("30.00");
        assertThat(rule(rules, WithholdingKind.EC_INCOME_TAX_WITHHOLDING).enabled()).isFalse();
        assertThat(rule(rules, WithholdingKind.EC_INCOME_TAX_WITHHOLDING).code()).isEqualTo("310");
        assertThat(rules.rule(WithholdingKind.PE_DETRACTION)).isEmpty();
    }

    @Test
    @DisplayName("the disabled electronic validation never says valid")
    void validationIsNeverValid() {
        assertThat(new DisabledFiscalDocumentValidationAdapter().validate("EC", "0990004196001", "01",
                "001-001-000000123"))
                .isEqualTo(FiscalDocumentValidationPort.FiscalDocumentValidationStatus.EXTERNAL_DEPENDENCY);
    }
}
