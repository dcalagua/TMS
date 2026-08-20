package com.ebim.tms.orders.application.imports;

import com.ebim.tms.orders.domain.OrderImportBatch;
import com.ebim.tms.orders.domain.OrderNumbers;
import com.ebim.tms.orders.domain.OrderTotals;
import com.ebim.tms.orders.domain.TransportOrder;
import com.ebim.tms.orders.infrastructure.OrderImportBatchRepository;
import com.ebim.tms.orders.infrastructure.TransportOrderRepository;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.reference.DestinationLookupPort;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.OriginLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The bulk order import: parse, validate, preview, and - only when the whole file is clean -
 * apply.
 *
 * <h2>The two guarantees</h2>
 *
 * <ol>
 *   <li><b>All or nothing.</b> {@link #apply} runs in one transaction and refuses to write
 *       anything at all if a single row has an issue. There is no mode in which some orders of a
 *       file land and others do not, so an operator never has to work out which half of their
 *       file is now in the system.</li>
 *   <li><b>Idempotent.</b> Identity is {@code (externalSource, externalReference)} - the pair
 *       {@code uq_transport_order_external} indexes. An order whose reference is already present
 *       in the company is skipped, not duplicated and not treated as an error, so re-uploading
 *       the same file after a network failure is safe and boring. The unique index is the
 *       backstop for two imports racing.</li>
 * </ol>
 *
 * <p>{@link #dryRun} and {@link #apply} share {@link #evaluate}, so a preview cannot describe an
 * outcome different from the one applying produces. What differs between them is only whether
 * the result is written.
 *
 * <p>Everything tenant-related happens here rather than in {@link OrderImportValidator}: the
 * masters are resolved through the company-scoped lookup ports, and the existing-reference check
 * is company-scoped too, so a file naming another tenant's origin code sees exactly what a file
 * naming a nonexistent one sees.
 */
@Service
public class OrderImportService {

    private final OrderImportParser parser;
    private final TransportOrderRepository transportOrderRepository;
    private final OrderImportBatchRepository orderImportBatchRepository;
    private final OriginLookupPort originLookupPort;
    private final DestinationLookupPort destinationLookupPort;
    private final AuditActorProvider auditActorProvider;

    public OrderImportService(OrderImportParser parser, TransportOrderRepository transportOrderRepository,
            OrderImportBatchRepository orderImportBatchRepository, OriginLookupPort originLookupPort,
            DestinationLookupPort destinationLookupPort, AuditActorProvider auditActorProvider) {
        this.parser = parser;
        this.transportOrderRepository = transportOrderRepository;
        this.orderImportBatchRepository = orderImportBatchRepository;
        this.originLookupPort = originLookupPort;
        this.destinationLookupPort = destinationLookupPort;
        this.auditActorProvider = auditActorProvider;
    }

    /** Reads the file and reports what importing it would do. Writes nothing, ever. */
    @Transactional(readOnly = true)
    public OrderImportReport dryRun(CompanyScope scope, String externalSource, byte[] content, String fileName) {
        Evaluation evaluation = evaluate(scope, externalSource, content, fileName);
        return report(evaluation, true, false, null);
    }

    /**
     * Applies the file. Returns the same report {@link #dryRun} would, with {@code applied} true
     * when every order was committed and false - having written nothing - when any row had an
     * issue. See {@link OrderImportReport} for why an unusable file is not an error status.
     */
    @Transactional
    public OrderImportReport apply(CompanyScope scope, String externalSource, byte[] content, String fileName) {
        Evaluation evaluation = evaluate(scope, externalSource, content, fileName);
        if (!evaluation.issues().isEmpty()) {
            return report(evaluation, false, false, null);
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        List<OrderImportCandidate> creatable = evaluation.candidates().stream()
                .filter(OrderImportCandidate::isCreatable)
                .toList();
        Map<String, String> orderNumbers = persist(scope, evaluation.externalSource(), creatable, actorId);

        int skipped = evaluation.candidates().size() - creatable.size();
        OrderImportBatch batch = orderImportBatchRepository.save(new OrderImportBatch(scope.companyId(),
                evaluation.externalSource(), fileName, evaluation.format().name(), sha256(content),
                evaluation.rowCount(), creatable.size(), skipped, actorId));

        return report(evaluation, false, true, batch.id(), orderNumbers);
    }

    /** What both entry points compute: everything except whether it is written. */
    private record Evaluation(
            String externalSource,
            String fileName,
            OrderImportFormat format,
            int rowCount,
            List<OrderImportCandidate> candidates,
            List<OrderImportReport.Issue> issues,
            int issueCount) {
    }

    private Evaluation evaluate(CompanyScope scope, String externalSource, byte[] content, String fileName) {
        String source = requireExternalSource(externalSource);
        OrderImportFormat format = requireSupportedFile(content, fileName);

        OrderImportParser.ParsedFile parsed = parser.parse(content, format);
        List<OrderImportRow> rows = parsed.rows();

        OrderImportValidator.MasterSnapshot snapshot = new OrderImportValidator.MasterSnapshot(
                originLookupPort.findActiveByCodesInCompany(
                        OrderImportValidator.referencedCodes(rows, OrderImportColumn.ORIGIN_CODE), scope.companyId()),
                destinationLookupPort.findActiveByCodesInCompany(
                        OrderImportValidator.referencedCodes(rows, OrderImportColumn.DESTINATION_CODE),
                        scope.companyId()),
                existingReferences(scope, source, rows));

        OrderImportValidator.Result result = OrderImportValidator.validate(rows, snapshot);
        int issueCount = result.issues().size();
        List<OrderImportReport.Issue> reported = issueCount > OrderImportLimits.MAX_REPORTED_ISSUES
                ? result.issues().subList(0, OrderImportLimits.MAX_REPORTED_ISSUES)
                : result.issues();

        return new Evaluation(source, fileName, format, rows.size(), result.candidates(), reported, issueCount);
    }

    /**
     * Which of the file's references this company already holds under this source. Asked once for
     * the whole file, and only about references the file actually mentions - never "list every
     * order of this company", which would grow with the tenant rather than with the upload.
     */
    private Set<String> existingReferences(CompanyScope scope, String externalSource, List<OrderImportRow> rows) {
        Set<String> referenced = new HashSet<>();
        for (OrderImportRow row : rows) {
            String reference = row.value(OrderImportColumn.EXTERNAL_REFERENCE);
            if (reference != null) {
                referenced.add(reference);
            }
        }
        if (referenced.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(
                transportOrderRepository.findExistingExternalReferences(scope.companyId(), externalSource, referenced));
    }

    /**
     * Inserts every creatable candidate, returning each one's assigned order number by external
     * reference so the report can show it. The sequence values are fetched in a single query -
     * see {@code TransportOrderRepository.nextOrderNumberValues}.
     */
    private Map<String, String> persist(CompanyScope scope, String externalSource,
            List<OrderImportCandidate> candidates, UUID actorId) {
        if (candidates.isEmpty()) {
            return Map.of();
        }
        List<Long> sequenceValues = transportOrderRepository.nextOrderNumberValues(candidates.size());
        List<TransportOrder> orders = new ArrayList<>(candidates.size());
        Map<String, String> numbersByReference = new LinkedHashMap<>();

        for (int index = 0; index < candidates.size(); index++) {
            OrderImportCandidate candidate = candidates.get(index);
            String orderNumber = OrderNumbers.format(sequenceValues.get(index));
            TransportOrder order = new TransportOrder(scope.companyId(), orderNumber, externalSource,
                    candidate.externalReference(), candidate.origin().id(), candidate.destination().id(),
                    candidate.customerName(), candidate.customerReference(), candidate.serviceDate(),
                    candidate.priority(), candidate.windowStart(), candidate.windowEnd(), actorId);
            order.applyLines(candidate.lines(), candidate.declaredTotals(), actorId);
            orders.add(order);
            numbersByReference.put(candidate.externalReference(), orderNumber);
        }

        // saveAll, then one flush: Hibernate batches the inserts (hibernate.jdbc.batch_size=50)
        // instead of issuing a round trip per order. A uniqueness violation here - two imports
        // racing on the same reference - surfaces as a DataIntegrityViolationException, which
        // ApiExceptionHandler turns into a 409, and the whole transaction rolls back, which is
        // exactly the all-or-nothing behaviour this method promises.
        transportOrderRepository.saveAll(orders);
        transportOrderRepository.flush();
        return Map.copyOf(numbersByReference);
    }

    private static String requireExternalSource(String externalSource) {
        String source = externalSource == null ? "" : externalSource.trim();
        if (source.isEmpty()) {
            throw new InvalidRequestException("An external source is required: it identifies the system this file "
                    + "came from and, with each order's external reference, is what makes the import idempotent.");
        }
        if (source.length() > 64) {
            throw new InvalidRequestException("The external source must be 64 characters or fewer.");
        }
        return source;
    }

    private static OrderImportFormat requireSupportedFile(byte[] content, String fileName) {
        if (content == null || content.length == 0) {
            throw new InvalidRequestException("The uploaded file is empty.");
        }
        if (content.length > OrderImportLimits.MAX_FILE_BYTES) {
            throw new InvalidRequestException("The file is larger than "
                    + OrderImportLimits.MAX_FILE_BYTES / (1024 * 1024) + " MB.");
        }
        OrderImportFormat format = OrderImportFormat.detect(content, fileName);
        if (format == null) {
            throw new InvalidRequestException(
                    "Only .xlsx and .csv files can be imported. Download the template to see the expected shape.");
        }
        return format;
    }

    private OrderImportReport report(Evaluation evaluation, boolean dryRun, boolean applied, UUID batchId) {
        return report(evaluation, dryRun, applied, batchId, Map.of());
    }

    private OrderImportReport report(Evaluation evaluation, boolean dryRun, boolean applied, UUID batchId,
            Map<String, String> orderNumbers) {
        List<OrderImportReport.OrderPreview> previews = evaluation.candidates().stream()
                .map(candidate -> toPreview(candidate, orderNumbers.get(candidate.externalReference())))
                .toList();

        int created = (int) evaluation.candidates().stream().filter(OrderImportCandidate::isCreatable).count();
        int skipped = (int) evaluation.candidates().stream()
                .filter(candidate -> candidate.outcome() == OrderImportReport.Outcome.SKIPPED_DUPLICATE).count();
        int rejected = evaluation.candidates().size() - created - skipped;

        return new OrderImportReport(dryRun, applied, batchId, evaluation.fileName(), evaluation.format(),
                evaluation.externalSource(),
                evaluation.rowCount(), evaluation.candidates().size(), created, skipped, rejected,
                evaluation.issueCount(), evaluation.issueCount() > evaluation.issues().size(), previews,
                evaluation.issues());
    }

    /**
     * The preview of one candidate, with its totals resolved through the same {@link OrderTotals}
     * the persisted order will use - so what the operator approves is the number that will be
     * stored, not a second calculation that could differ.
     */
    private static OrderImportReport.OrderPreview toPreview(OrderImportCandidate candidate, String orderNumber) {
        OrderTotals totals = OrderTotals.resolve(candidate.lines(), candidate.declaredTotals());
        MasterReference origin = candidate.origin();
        MasterReference destination = candidate.destination();
        return new OrderImportReport.OrderPreview(candidate.externalReference(), candidate.outcome(),
                candidate.firstRowNumber(), origin == null ? null : origin.code(),
                origin == null ? null : origin.name(), destination == null ? null : destination.code(),
                destination == null ? null : destination.name(), candidate.customerName(), candidate.serviceDate(),
                candidate.priority(), candidate.lines().size(), scaleOrNull(totals.weightKg()),
                scaleOrNull(totals.volumeM3()), scaleOrNull(totals.pallets()), totals.source(), orderNumber);
    }

    private static BigDecimal scaleOrNull(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }

    /** SHA-256 of the uploaded bytes, for {@code order_import_batch.file_sha256}. */
    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }
}
