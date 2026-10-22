package com.ebim.tms.planning.application;

import com.ebim.tms.planning.domain.DeliveryResult;
import com.ebim.tms.shared.reference.OrderAmounts;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * What a carrier records about one order at one stop from the portal (V70): the office's
 * {@link DeliveryResultRequest} as a driver types it, plus the evidence in the same request.
 *
 * <p>Not a {@code MultipartFile} anywhere, for {@link EvidenceUpload}'s reason: the controller is the
 * only place that knows about multipart.
 *
 * @param deliveredAt when it happened at the door - the phone's time, so a report replayed from the
 *     offline queue keeps the moment it was made; null means now
 * @param refusedWeightKg what the customer refused, for a {@code PARTIAL} delivery only; any of the
 *     three may be null and counts as zero, but a partial delivery must carry at least one
 * @param photos zero to five
 * @param signature optional
 */
public record PortalDeliveryCommand(DeliveryResult result, OffsetDateTime deliveredAt, String receiverName,
        String receiverDocument, String notes, BigDecimal refusedWeightKg, BigDecimal refusedVolumeM3,
        BigDecimal refusedPallets, List<EvidenceUpload> photos, EvidenceUpload signature) {

    public PortalDeliveryCommand {
        photos = photos == null ? List.of() : List.copyOf(photos);
    }

    /** Whether any refused amount was sent. */
    public boolean hasRefusedAmounts() {
        return refusedWeightKg != null || refusedVolumeM3 != null || refusedPallets != null;
    }

    /** The refused amounts, a missing one as zero. */
    public OrderAmounts refused() {
        return new OrderAmounts(refusedWeightKg, refusedVolumeM3, refusedPallets);
    }

    public boolean hasEvidence() {
        return !photos.isEmpty() || signature != null;
    }
}
