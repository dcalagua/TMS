package com.ebim.tms.iam.entitlements.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The shape of {@code ebim.entitlements/v1} ({@code schema.json}) and its forbidden keys and values
 * (CCP spec section 7.2.6), written out by hand.
 *
 * <p>It mirrors {@code isValidSnapshotShape} + {@code assertSnapshotSafe} of MasterAdmin's reference
 * receiver: both sides must refuse exactly the same documents, and the golden fixtures prove it.
 */
public final class SnapshotValidator {

    public static final String SCHEMA = "ebim.entitlements/v1";

    private static final Set<String> TOP_LEVEL = Set.of(
            "schema", "environment", "controlPlaneTenantId", "productCode", "external", "snapshotVersion",
            "previousVersion", "effectiveAt", "issuedAt", "appActive", "planCode", "capabilities", "limits",
            "allowances", "aiCredits", "correlationId", "idempotencyKey", "checksum");

    private static final Pattern UUID_RE =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern CODE_RE = Pattern.compile("^[a-z0-9]+(\\.[a-z0-9_]+)+$");
    private static final Pattern ISO_RE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$");
    private static final Pattern PRODUCT_RE = Pattern.compile("^[a-z0-9]+$");
    private static final Pattern IDEMPOTENCY_RE = Pattern.compile("^ma-ent-v1-[0-9a-f]{64}$");
    private static final Pattern CHECKSUM_RE = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Set<String> ENVIRONMENTS = Set.of("DEV", "QAS", "DEMO", "PRD");
    private static final Set<String> SOURCES = Set.of("BASELINE", "PLAN", "ADDON", "OVERRIDE");

    private static final List<String> FORBIDDEN_KEY_FRAGMENTS =
            List.of("price", "amount", "currency", "cost", "secret", "token", "email", "key", "password");
    private static final Set<String> ALLOWED_KEYS = Set.of("idempotencyKey");
    private static final Map<String, Pattern> FORBIDDEN_VALUES = Map.of(
            "an e-mail address", Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"),
            "a PEM block", Pattern.compile("-----BEGIN [A-Z ]+-----"),
            "a JWT", Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\."));

    private SnapshotValidator() {}

    /**
     * @throws EntitlementRejection {@code SNAPSHOT_INVALID} when the shape is wrong or a forbidden key
     *         or value is present. The message names the rule, never the value.
     */
    public static EntitlementSnapshot validate(JsonNode document) {
        if (!validShape(document)) {
            throw invalid("The snapshot does not conform to " + SCHEMA);
        }
        String forbidden = forbidden(document, "$");
        if (forbidden != null) {
            throw invalid("The snapshot carries a forbidden key or value (" + forbidden + ")");
        }
        return read((ObjectNode) document);
    }

    private static EntitlementRejection invalid(String message) {
        return new EntitlementRejection(EntitlementRejection.Code.SNAPSHOT_INVALID, message);
    }

    // -- Shape -------------------------------------------------------------------------------------

    static boolean validShape(JsonNode d) {
        if (d == null || !d.isObject() || !names(d).equals(new TreeSet<>(TOP_LEVEL))) {
            return false;
        }
        JsonNode external = d.get("external");
        return SCHEMA.equals(text(d, "schema"))
                && ENVIRONMENTS.contains(text(d, "environment"))
                && matches(UUID_RE, d.get("controlPlaneTenantId"))
                && matches(PRODUCT_RE, d.get("productCode"))
                && exactly(external, Set.of("tenantId", "organizationId", "companyIds"), Set.of())
                && external.get("tenantId").isString()
                && (external.get("organizationId").isNull() || external.get("organizationId").isString())
                && arrayOfStrings(external.get("companyIds"))
                && integer(d.get("snapshotVersion")) && d.get("snapshotVersion").asLong() >= 1
                && (d.get("previousVersion").isNull()
                        || (integer(d.get("previousVersion"))
                                && d.get("previousVersion").asLong() < d.get("snapshotVersion").asLong()))
                && matches(ISO_RE, d.get("effectiveAt"))
                && matches(ISO_RE, d.get("issuedAt"))
                && d.get("appActive").isBoolean()
                && (d.get("planCode").isNull() || d.get("planCode").isString())
                && all(d.get("capabilities"), c -> exactly(c, Set.of("code", "enabled", "scope", "sources"), Set.of())
                        && matches(CODE_RE, c.get("code")) && c.get("enabled").isBoolean()
                        && validScope(c.get("scope")) && validSources(c.get("sources")))
                && all(d.get("limits"), l -> exactly(l,
                                Set.of("code", "value", "unit", "enforcement", "scope", "sources"), Set.of())
                        && matches(CODE_RE, l.get("code")) && l.get("value").isNumber() && l.get("value").asDouble() >= 0
                        && (l.get("unit").isNull() || l.get("unit").isString())
                        && l.get("enforcement").isString()
                        && Set.of("HARD", "SOFT").contains(l.get("enforcement").stringValue())
                        && validScope(l.get("scope")) && validSources(l.get("sources")))
                && all(d.get("allowances"), a -> exactly(a,
                                Set.of("code", "meterCode", "included", "unit", "period", "overageMode", "sources"),
                                Set.of())
                        && matches(CODE_RE, a.get("code")) && a.get("meterCode").isString()
                        && a.get("included").isNumber() && a.get("included").asDouble() >= 0
                        && (a.get("unit").isNull() || a.get("unit").isString())
                        && exactly(a.get("period"), Set.of("start", "end"), Set.of())
                        && "BLOCK".equals(text(a, "overageMode")) && validSources(a.get("sources")))
                && exactly(d.get("aiCredits"), Set.of("weights", "weightsVersion"), Set.of())
                && d.get("aiCredits").get("weights").isArray()
                && integer(d.get("aiCredits").get("weightsVersion"))
                && matches(UUID_RE, d.get("correlationId"))
                && matches(IDEMPOTENCY_RE, d.get("idempotencyKey"))
                && matches(CHECKSUM_RE, d.get("checksum"));
    }

    private static Set<String> names(JsonNode node) {
        return new TreeSet<>(node.propertyNames());
    }

    private static boolean exactly(JsonNode node, Set<String> required, Set<String> optional) {
        if (node == null || !node.isObject()) {
            return false;
        }
        Set<String> keys = names(node);
        return keys.containsAll(required) && keys.stream().allMatch(k -> required.contains(k) || optional.contains(k));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }

    private static boolean matches(Pattern pattern, JsonNode value) {
        return value != null && value.isString() && pattern.matcher(value.stringValue()).matches();
    }

    private static boolean integer(JsonNode value) {
        return value != null && value.isNumber() && value.canConvertToExactIntegral();
    }

    private static boolean arrayOfStrings(JsonNode value) {
        return all(value, JsonNode::isString);
    }

    private static boolean all(JsonNode value, Predicate<JsonNode> predicate) {
        if (value == null || !value.isArray()) {
            return false;
        }
        for (JsonNode element : value) {
            if (!predicate.test(element)) {
                return false;
            }
        }
        return true;
    }

    private static boolean validScope(JsonNode value) {
        if (!exactly(value, Set.of("level"), Set.of("companyIds"))) {
            return false;
        }
        String level = text(value, "level");
        if ("TENANT".equals(level)) {
            return !value.has("companyIds");
        }
        return "COMPANY".equals(level) && (!value.has("companyIds") || arrayOfStrings(value.get("companyIds")));
    }

    private static boolean validSources(JsonNode value) {
        return all(value, s -> s.isString() && SOURCES.contains(s.stringValue()));
    }

    // -- Forbidden (spec section 7.2.6) ------------------------------------------------------------

    /** @return the path and kind of the first finding, or {@code null}. Never the value itself. */
    static String forbidden(JsonNode node, String path) {
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                String finding = forbidden(node.get(i), path + "[" + i + "]");
                if (finding != null) {
                    return finding;
                }
            }
        } else if (node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                String lower = entry.getKey().toLowerCase(Locale.ROOT);
                if (!ALLOWED_KEYS.contains(entry.getKey()) && FORBIDDEN_KEY_FRAGMENTS.stream().anyMatch(lower::contains)) {
                    return "forbidden key at " + path + "." + entry.getKey();
                }
                String finding = forbidden(entry.getValue(), path + "." + entry.getKey());
                if (finding != null) {
                    return finding;
                }
            }
        } else if (node.isString()) {
            for (Map.Entry<String, Pattern> pattern : FORBIDDEN_VALUES.entrySet()) {
                if (pattern.getValue().matcher(node.stringValue()).find()) {
                    return path + " has the shape of " + pattern.getKey();
                }
            }
        }
        return null;
    }

    // -- Reading ------------------------------------------------------------------------------------

    private static EntitlementSnapshot read(ObjectNode d) {
        List<EntitlementSnapshot.Capability> capabilities = new ArrayList<>();
        for (JsonNode c : d.get("capabilities")) {
            capabilities.add(new EntitlementSnapshot.Capability(
                    c.get("code").stringValue(), c.get("enabled").booleanValue(), c.get("scope").has("companyIds")));
        }
        List<EntitlementSnapshot.Limit> limits = new ArrayList<>();
        for (JsonNode l : d.get("limits")) {
            limits.add(new EntitlementSnapshot.Limit(l.get("code").stringValue(), l.get("value").asDouble(),
                    l.get("unit").isNull() ? null : l.get("unit").stringValue(), l.get("enforcement").stringValue(),
                    l.get("scope").has("companyIds")));
        }
        List<String> allowances = new ArrayList<>();
        d.get("allowances").forEach(a -> allowances.add(a.get("code").stringValue()));
        return new EntitlementSnapshot(
                UUID.fromString(d.get("controlPlaneTenantId").stringValue()),
                d.get("productCode").stringValue(),
                d.get("environment").stringValue(),
                d.get("snapshotVersion").asLong(),
                d.get("checksum").stringValue(),
                d.get("appActive").booleanValue(),
                List.copyOf(capabilities), List.copyOf(limits), List.copyOf(allowances),
                d);
    }
}
