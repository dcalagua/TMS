package com.ebim.tms.orders.application;

import com.ebim.tms.orders.domain.Eligibility;
import com.ebim.tms.orders.domain.SchedulingAssessment;
import com.ebim.tms.orders.domain.SchedulingReason;
import com.ebim.tms.orders.domain.TransportOrder;
import com.ebim.tms.shared.api.ConflictException;
import java.io.Serial;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The release gate's 409 (ADR-014 section 8). The problem detail carries, beside the sentence:
 *
 * <ul>
 *   <li>{@code eligibility} - {@code BLOCKED} or {@code WARNING};</li>
 *   <li>{@code overrideRequired} - true when the only thing missing is a person's reason;</li>
 *   <li>{@code reasons} - every reason, each {@code {code, severity, requiresOverride, detail}}.</li>
 * </ul>
 *
 * <p>So a client switches on codes and asks for a reason exactly when {@code overrideRequired} is
 * true, instead of parsing prose.
 */
public class ReleaseRefusal extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient SchedulingAssessment assessment;
    private final String orderNumber;
    private final boolean overrideRequired;

    private ReleaseRefusal(String message, TransportOrder order, SchedulingAssessment assessment,
            boolean overrideRequired) {
        super(message, properties(assessment, overrideRequired));
        this.assessment = assessment;
        this.orderNumber = order.orderNumber();
        this.overrideRequired = overrideRequired;
    }

    static ReleaseRefusal blocked(TransportOrder order, SchedulingAssessment assessment) {
        String because = assessment.reasons().stream()
                .filter(reason -> reason.severity() == Eligibility.BLOCKED)
                .map(SchedulingReason::detail)
                .collect(Collectors.joining(" "));
        return new ReleaseRefusal("Order " + order.orderNumber() + " cannot be released for planning. " + because,
                order, assessment, false);
    }

    static ReleaseRefusal overrideRequired(TransportOrder order, SchedulingAssessment assessment) {
        String because = assessment.reasons().stream()
                .filter(SchedulingReason::requiresOverride)
                .map(SchedulingReason::detail)
                .collect(Collectors.joining(" "));
        return new ReleaseRefusal("Order " + order.orderNumber() + " can only be released with an override reason. "
                + because, order, assessment, true);
    }

    public SchedulingAssessment assessment() {
        return assessment;
    }

    public String orderNumber() {
        return orderNumber;
    }

    public boolean overrideRequired() {
        return overrideRequired;
    }

    private static Map<String, Object> properties(SchedulingAssessment assessment, boolean overrideRequired) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("eligibility", assessment.eligibility().name());
        properties.put("overrideRequired", overrideRequired);
        List<Map<String, Object>> reasons = assessment.reasons().stream()
                .map(reason -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("code", reason.code().name());
                    item.put("severity", reason.severity().name());
                    item.put("requiresOverride", reason.requiresOverride());
                    item.put("detail", reason.detail());
                    return item;
                })
                .toList();
        properties.put("reasons", reasons);
        return properties;
    }
}
