package com.ebim.tms.fleet.application.imports;

import com.ebim.tms.fleet.domain.VehicleType;
import com.ebim.tms.fleet.infrastructure.VehicleTypeRepository;
import com.ebim.tms.shared.audit.AuditActorProvider;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The bulk Vehicle Type import: parse, validate, preview, and - only when the whole file is clean
 * - apply. Same two guarantees as {@code LocationImportService}: all-or-nothing, and idempotent
 * on {@code code}.
 */
@Service
public class VehicleTypeImportService {

    private final VehicleTypeImportParser parser;
    private final VehicleTypeRepository vehicleTypeRepository;
    private final ImportBatchRepository importBatchRepository;
    private final AuditActorProvider auditActorProvider;

    public VehicleTypeImportService(VehicleTypeImportParser parser, VehicleTypeRepository vehicleTypeRepository,
            ImportBatchRepository importBatchRepository, AuditActorProvider auditActorProvider) {
        this.parser = parser;
        this.vehicleTypeRepository = vehicleTypeRepository;
        this.importBatchRepository = importBatchRepository;
        this.auditActorProvider = auditActorProvider;
    }

    @Transactional(readOnly = true)
    public ImportReport<VehicleTypeImportPreview> dryRun(
            CompanyScope scope, byte[] content, String fileName, ImportFormat format) {
        Evaluation evaluation = evaluate(scope, content, fileName, format);
        return report(evaluation, true, false, null);
    }

    @Transactional
    public ImportReport<VehicleTypeImportPreview> apply(
            CompanyScope scope, byte[] content, String fileName, ImportFormat format) {
        Evaluation evaluation = evaluate(scope, content, fileName, format);
        if (!evaluation.issues().isEmpty()) {
            return report(evaluation, false, false, null);
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        List<VehicleTypeImportCandidate> creatable =
                evaluation.candidates().stream().filter(VehicleTypeImportCandidate::isCreatable).toList();
        persist(scope, creatable, actorId);

        int skipped = evaluation.candidates().size() - creatable.size();
        ImportBatch batch = importBatchRepository.save(new ImportBatch(scope.companyId(),
                ImportEntityType.VEHICLE_TYPE, fileName, evaluation.format(), ImportSupport.sha256(content),
                evaluation.rowCount(), creatable.size(), skipped, actorId));

        return report(evaluation, false, true, batch.id());
    }

    private record Evaluation(String fileName, ImportFormat format, int rowCount,
            List<VehicleTypeImportCandidate> candidates, List<ImportIssue> issues, int issueCount) {
    }

    private Evaluation evaluate(CompanyScope scope, byte[] content, String fileName, ImportFormat format) {
        ImportLimits limits = ImportLimits.standard();
        List<ImportRow> rows = parser.parse(content, format, limits);

        VehicleTypeImportValidator.MasterSnapshot snapshot =
                new VehicleTypeImportValidator.MasterSnapshot(existingCodes(scope, rows));

        VehicleTypeImportValidator.Result result = VehicleTypeImportValidator.validate(rows, snapshot);
        int issueCount = result.issues().size();
        List<ImportIssue> reported = ImportSupport.truncate(result.issues(), limits.maxReportedIssues());

        return new Evaluation(fileName, format, rows.size(), result.candidates(), reported, issueCount);
    }

    private Set<String> existingCodes(CompanyScope scope, List<ImportRow> rows) {
        Set<String> referenced = new HashSet<>();
        for (ImportRow row : rows) {
            String code = row.value(VehicleTypeImportColumn.CODE.header());
            if (code != null) {
                referenced.add(code.trim().toUpperCase(Locale.ROOT));
            }
        }
        if (referenced.isEmpty()) {
            return Set.of();
        }
        return vehicleTypeRepository.findByCompanyIdAndCodeIn(scope.companyId(), referenced).stream()
                .map(VehicleType::code)
                .collect(Collectors.toUnmodifiableSet());
    }

    private void persist(CompanyScope scope, List<VehicleTypeImportCandidate> candidates, UUID actorId) {
        if (candidates.isEmpty()) {
            return;
        }
        List<VehicleType> vehicleTypes = new ArrayList<>(candidates.size());
        for (VehicleTypeImportCandidate candidate : candidates) {
            vehicleTypes.add(new VehicleType(scope.companyId(), candidate.code(), candidate.name(),
                    candidate.maxWeightKg(), candidate.maxVolumeM3(), candidate.maxPallets(), candidate.lengthM(),
                    candidate.widthM(), candidate.heightM(), candidate.bodyType(), candidate.temperatureControlled(),
                    candidate.minTemperatureCelsius(), candidate.maxTemperatureCelsius(), candidate.axles(), actorId));
        }
        vehicleTypeRepository.saveAll(vehicleTypes);
        vehicleTypeRepository.flush();
    }

    private ImportReport<VehicleTypeImportPreview> report(
            Evaluation evaluation, boolean dryRun, boolean applied, UUID batchId) {
        List<VehicleTypeImportPreview> previews =
                evaluation.candidates().stream().map(VehicleTypeImportPreview::from).toList();
        int created = (int) evaluation.candidates().stream().filter(VehicleTypeImportCandidate::isCreatable).count();
        int skipped = (int) evaluation.candidates().stream()
                .filter(candidate -> candidate.outcome() == ImportOutcome.SKIPPED_DUPLICATE).count();
        int rejected = evaluation.candidates().size() - created - skipped;

        return new ImportReport<>(dryRun, applied, batchId, evaluation.fileName(), evaluation.format(),
                evaluation.rowCount(), evaluation.candidates().size(), created, skipped, rejected,
                evaluation.issueCount(), evaluation.issueCount() > evaluation.issues().size(), previews,
                evaluation.issues());
    }
}
