package com.ebim.tms.iam.provisioning.application;

import java.util.Map;

/**
 * The time zone a provisioned company starts with.
 *
 * <p>{@code tms.company.time_zone} decides where a company's operating day begins and ends, and the
 * GENERIC contract carries no time zone. Guessing it from a country is only honest where the country
 * has exactly one IANA zone, so this table lists those countries and nothing else. Every other
 * country - and a missing one - gets {@code UTC}, which is the column's own default and is visible
 * in the provisioning response ({@code resources.companyTimeZone}) so an administrator corrects it
 * on the company settings screen.
 *
 * <p>Countries with several zones (MX, BR, CL, AR, US, EC, ES...) are left out on purpose: picking
 * the capital's zone would be right for some of their companies and silently wrong for the rest.
 */
public final class CountryTimeZones {

    public static final String FALLBACK = "UTC";

    private static final Map<String, String> SINGLE_ZONE_COUNTRIES = Map.ofEntries(
            Map.entry("PE", "America/Lima"),
            Map.entry("CO", "America/Bogota"),
            Map.entry("BO", "America/La_Paz"),
            Map.entry("PY", "America/Asuncion"),
            Map.entry("UY", "America/Montevideo"),
            Map.entry("VE", "America/Caracas"),
            Map.entry("PA", "America/Panama"),
            Map.entry("CR", "America/Costa_Rica"),
            Map.entry("GT", "America/Guatemala"),
            Map.entry("SV", "America/El_Salvador"),
            Map.entry("HN", "America/Tegucigalpa"),
            Map.entry("NI", "America/Managua"),
            Map.entry("DO", "America/Santo_Domingo"));

    private CountryTimeZones() {}

    public static String forCountry(String isoAlpha2) {
        if (isoAlpha2 == null) {
            return FALLBACK;
        }
        return SINGLE_ZONE_COUNTRIES.getOrDefault(isoAlpha2, FALLBACK);
    }
}
