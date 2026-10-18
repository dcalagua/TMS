package com.ebim.tms.shared.geometry;

import java.util.Arrays;
import java.util.Optional;

/**
 * How units of one material pile up in a load (ADR-022). The vocabulary of the warehouse's
 * {@code md_item}, mirrored by {@code ck_material_profile_stack_mode} and
 * {@code ck_transport_order_line_stack_mode} (V59).
 *
 * <p>There is no "unknown" constant: a mode nobody declared is {@code null}, and nothing downstream
 * estimates one (EWM rule 199).
 */
public enum StackMode {

    /** One unit per floor position; nothing goes on top. */
    NONE,

    /** Units sit on top of each other: a stack of n is n unit heights. */
    STACKABLE,

    /** Units fit inside each other: a stack of n is one height plus (n - 1) nest increments. */
    NESTABLE;

    /** The constant for a code, exactly as sent (no case folding); empty for anything else. */
    public static Optional<StackMode> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(mode -> mode.name().equals(code)).findFirst();
    }

    /** The accepted codes, for a refusal message. */
    public static String codes() {
        return String.join(", ", Arrays.stream(values()).map(Enum::name).toList());
    }
}
