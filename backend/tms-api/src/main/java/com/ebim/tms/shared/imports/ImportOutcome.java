package com.ebim.tms.shared.imports;

/** What an import decided about one row/item. Shared shape for every entity import. */
public enum ImportOutcome {

    /** Will be created (dry run) or was created (apply). */
    CREATE,

    /**
     * A row already exists in this company (matched by code, or by an external system/reference
     * pair), so the import leaves it untouched. Deliberately not an error: idempotency means a
     * repeat upload is a no-op, not a failure - re-uploading the same file after a network hiccup
     * must be safe and boring.
     */
    SKIPPED_DUPLICATE,

    /** At least one problem was found on this row, so nothing about it is applied. */
    REJECTED
}
