package com.ebim.tms.fleet.application.imports;

import com.ebim.tms.fleet.domain.Carrier;
import com.ebim.tms.fleet.infrastructure.CarrierRepository;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.imports.ImportBatch;
import com.ebim.tms.shared.imports.ImportEntityType;
import com.ebim.tms.shared.imports.ImportFormat;
import com.ebim.tms.shared.imports.ImportIssue;
import com.ebim.tms.shared.imports.ImportLimits;
import com.ebim.tms.shared.imports.ImportOutcome;
import com.ebim.tms.shared.imports.ImportReport;
import com.ebim.tms.shared.imports.ImportRow;
import com.ebim.tms.shared.imports.ImportSupport;
import com.ebim.tms.shared.imports.infrastructure.ImportBatchRepository;
import com.ebim.tms.shared.security.CompanyScope;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The bulk Carrier import: parse, validate, preview, and - only when the whole file is clean -
 * apply. Same two guarantees as {@code LocationImportService}: all-or-nothing, and idempotent on
 * {@code code}.
 */
@Service
public class CarrierImportService {

    private final CarrierImportParser parser;
    private final CarrierRepository carrierRepository;
    private final ImportBatchRepository importBatchRepository;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;

    public CarrierImportService(CarrierImportParser parser, CarrierRepository carrierRepository,
            ImportBatchRepository importBatchRepository, AuditActorProvider auditActorProvider,
            AuditRecorder auditRecorder) {
        this.parser = parser;
        this.carrierRepository = carrierRepository;
        this.importBatchRepository = importBatchRepository;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
    }

    @Transactional(readOnly = true)
    public ImportReport<CarrierImportPreview> dryRun(
            CompanyScope scope, byte[] content, String fileName, ImportFormat format) {
        Evaluation evaluation = evaluate(scope, content, fileName, format);
        return report(evaluation, true, false, null);
    }

    @Transactional
    public ImportReport<CarrierImportPreview> apply(
            CompanyScope scope, byte[] content, String fileName, ImportFormat format) {
        Evaluation evaluation = evaluate(scope, content, fileName, format);
        if (!evaluation.issues().isEmpty()) {
            return report(evaluation, false, false, null);
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        List<CarrierImportCandidate> creatable =
                evaluation.candidates().stream().filter(CarrierImportCandidate::isCreatable).toList();
        persist(scope, creatable, actorId);

        int skipped = evaluation.candidates().size() - creatable.size();
        ImportBatch batch = importBatchRepository.save(new ImportBatch(scope.companyId(), ImportEntityType.CARRIER,
                fileName, evaluation.format(), ImportSupport.sha256(content), evaluation.rowCount(),
                creatable.size(), skipped, actorId));
        auditRecorder.record(scope, AuditAggregateType.MASTER_DATA_IMPORT_BATCH, batch.id(), AuditAction.IMPORT_EXECUTED,
                Map.of("entityType", ImportEntityType.CARRIER.name(), "createdCount", creatable.size(),
                        "skippedCount", skipped));

        return report(evaluation, false, true, batch.id());
    }

    private record Evaluation(String fileName, ImportFormat format, int rowCount,
            List<CarrierImportCandidate> candidates, List<ImportIssue> issues, int issueCount) {
    }

    private Evaluation evaluate(CompanyScope scope, byte[] content, String fileName, ImportFormat format) {
        ImportLimits limits = ImportLimits.standard();
        List<ImportRow> rows = parser.parse(content, format, limits);

        CarrierImportValidator.MasterSnapshot snapshot =
                new CarrierImportValidator.MasterSnapshot(existingCodes(scope, rows));

        CarrierImportValidator.Result result = CarrierImportValidator.validate(rows, snapshot);
        int issueCount = result.issues().size();
        List<ImportIssue> reported = ImportSupport.truncate(result.issues(), limits.maxReportedIssues());

        return new Evaluation(fileName, format, rows.size(), result.candidates(), reported, issueCount);
    }

    private Set<String> existingCodes(CompanyScope scope, List<ImportRow> rows) {
        Set<String> referenced = new HashSet<>();
        for (ImportRow row : rows) {
            String code = row.value(CarrierImportColumn.CODE.header());
            if (code != null) {
                referenced.add(code.trim().toUpperCase(Locale.ROOT));
            }
        }
        if (referenced.isEmpty()) {
            return Set.of();
        }
        return carrierRepository.findByCompanyIdAndCodeIn(scope.companyId(), referenced).stream()
                .map(Carrier::code)
                .collect(Collectors.toUnmodifiableSet());
    }

    private void persist(CompanyScope scope, List<CarrierImportCandidate> candidates, UUID actorId) {
        if (candidates.isEmpty()) {
            return;
        }
        List<Carrier> carriers = new ArrayList<>(candidates.size());
        for (CarrierImportCandidate candidate : candidates) {
            carriers.add(new Carrier(scope.companyId(), candidate.code(), candidate.businessName(),
                    candidate.taxIdType(), candidate.taxIdValue(), candidate.contactName(), candidate.phone(),
                    candidate.email(), candidate.externalReference(), actorId));
        }
        // A uniqueness violation here - two imports racing on the same code or tax id - surfaces
        // as a DataIntegrityViolationException, which ApiExceptionHandler turns into a 409, and
        // the whole transaction rolls back: the same all-or-nothing backstop OrderImportService
        // relies on for the same race.
        carrierRepository.saveAll(carriers);
        carrierRepository.flush();
    }

    private ImportReport<CarrierImportPreview> report(
            Evaluation evaluation, boolean dryRun, boolean applied, UUID batchId) {
        List<CarrierImportPreview> previews =
                evaluation.candidates().stream().map(CarrierImportPreview::from).toList();
        int created = (int) evaluation.candidates().stream().filter(CarrierImportCandidate::isCreatable).count();
        int skipped = (int) evaluation.candidates().stream()
                .filter(candidate -> candidate.outcome() == ImportOutcome.SKIPPED_DUPLICATE).count();
        int rejected = evaluation.candidates().size() - created - skipped;

        return new ImportReport<>(dryRun, applied, batchId, evaluation.fileName(), evaluation.format(),
                evaluation.rowCount(), evaluation.candidates().size(), created, skipped, rejected,
                evaluation.issueCount(), evaluation.issueCount() > evaluation.issues().size(), previews,
                evaluation.issues());
    }
}
