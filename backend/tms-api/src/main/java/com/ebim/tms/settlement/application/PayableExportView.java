package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.PayableExport;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The obligation as it was handed to whoever pays (migration V46).
 *
 * @param alreadyExported true when this export existed before the request. <b>Not an error</b>: two
 *                        clicks must not create two obligations, so the second returns the first's
 *                        reference, and the screen says "already exported" rather than pretending
 *                        it just happened
 */
public record PayableExportView(
        UUID id,
        String exportReference,
        PayableExport.Format format,
        String payload,
        OffsetDateTime exportedAt,
        boolean alreadyExported) {

    public static PayableExportView of(PayableExport export, boolean alreadyExported) {
        return new PayableExportView(export.id(), export.exportReference(), export.format(),
                export.payload(), export.exportedAt(), alreadyExported);
    }
}
