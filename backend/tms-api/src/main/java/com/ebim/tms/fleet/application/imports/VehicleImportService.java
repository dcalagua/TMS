package com.ebim.tms.fleet.application.imports;

import com.ebim.tms.fleet.domain.Carrier;
import com.ebim.tms.fleet.domain.Vehicle;
import com.ebim.tms.fleet.domain.VehicleType;
import com.ebim.tms.fleet.infrastructure.CarrierRepository;
import com.ebim.tms.fleet.infrastructure.VehicleRepository;
import com.ebim.tms.fleet.infrastructure.VehicleTypeRepository;
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
 * The bulk Vehicle import: parse, validate, preview, and - only when the whole file is clean -
 * apply. Same two guarantees as {@code LocationImportService}, plus a second idempotency key:
 * identity is {@code code} <em>or</em> {@code licensePlate} - a row matching either an existing
 * code or an existing plate is skipped, matching {@code VehicleService}'s own two independent
 * unique constraints ({@code uq_vehicle_company_code}, {@code uq_vehicle_company_license_plate}).
 *
 * <p>{@code carrierCode}/{@code vehicleTypeCode} are resolved against {@code CarrierRepository}/
 * {@code VehicleTypeRepository} directly, exactly as {@code VehicleService.create} resolves
 * {@code carrierId}/{@code vehicleTypeId} - Carrier, VehicleType and Vehicle all live in the same
 * {@code fleet} module, so no cross-module lookup port is needed here.
 */
@Service
public class VehicleImportService {

    private final VehicleImportParser parser;
    private final VehicleRepository vehicleRepository;
    private final CarrierRepository carrierRepository;
    private final VehicleTypeRepository vehicleTypeRepository;
    private final ImportBatchRepository importBatchRepository;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;

    public VehicleImportService(VehicleImportParser parser, VehicleRepository vehicleRepository,
            CarrierRepository carrierRepository, VehicleTypeRepository vehicleTypeRepository,
            ImportBatchRepository importBatchRepository, AuditActorProvider auditActorProvider,
            AuditRecorder auditRecorder) {
        this.parser = parser;
        this.vehicleRepository = vehicleRepository;
        this.carrierRepository = carrierRepository;
        this.vehicleTypeRepository = vehicleTypeRepository;
        this.importBatchRepository = importBatchRepository;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
    }

    @Transactional(readOnly = true)
    public ImportReport<VehicleImportPreview> dryRun(
            CompanyScope scope, byte[] content, String fileName, ImportFormat format) {
        Evaluation evaluation = evaluate(scope, content, fileName, format);
        return report(evaluation, true, false, null);
    }

    @Transactional
    public ImportReport<VehicleImportPreview> apply(
            CompanyScope scope, byte[] content, String fileName, ImportFormat format) {
        Evaluation evaluation = evaluate(scope, content, fileName, format);
        if (!evaluation.issues().isEmpty()) {
            return report(evaluation, false, false, null);
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        List<VehicleImportCandidate> creatable =
                evaluation.candidates().stream().filter(VehicleImportCandidate::isCreatable).toList();
        persist(scope, creatable, actorId);

        int skipped = evaluation.candidates().size() - creatable.size();
        ImportBatch batch = importBatchRepository.save(new ImportBatch(scope.companyId(), ImportEntityType.VEHICLE,
                fileName, evaluation.format(), ImportSupport.sha256(content), evaluation.rowCount(),
                creatable.size(), skipped, actorId));
        auditRecorder.record(scope, AuditAggregateType.MASTER_DATA_IMPORT_BATCH, batch.id(), AuditAction.IMPORT_EXECUTED,
                Map.of("entityType", ImportEntityType.VEHICLE.name(), "createdCount", creatable.size(),
                        "skippedCount", skipped));

        return report(evaluation, false, true, batch.id());
    }

    private record Evaluation(String fileName, ImportFormat format, int rowCount,
            List<VehicleImportCandidate> candidates, List<ImportIssue> issues, int issueCount) {
    }

    private Evaluation evaluate(CompanyScope scope, byte[] content, String fileName, ImportFormat format) {
        ImportLimits limits = ImportLimits.standard();
        List<ImportRow> rows = parser.parse(content, format, limits);

        VehicleImportValidator.MasterSnapshot snapshot = new VehicleImportValidator.MasterSnapshot(
                resolveCarrierCodes(scope, VehicleImportValidator.referencedCarrierCodes(rows)),
                resolveVehicleTypeCodes(scope, VehicleImportValidator.referencedVehicleTypeCodes(rows)),
                existingCodes(scope, rows), existingLicensePlates(scope, rows));

        VehicleImportValidator.Result result = VehicleImportValidator.validate(rows, snapshot);
        int issueCount = result.issues().size();
        List<ImportIssue> reported = ImportSupport.truncate(result.issues(), limits.maxReportedIssues());

        return new Evaluation(fileName, format, rows.size(), result.candidates(), reported, issueCount);
    }

    private Map<String, UUID> resolveCarrierCodes(CompanyScope scope, Set<String> codes) {
        if (codes.isEmpty()) {
            return Map.of();
        }
        return carrierRepository.findByCompanyIdAndCodeIn(scope.companyId(), codes).stream()
                .collect(Collectors.toUnmodifiableMap(carrier -> carrier.code().toUpperCase(Locale.ROOT), Carrier::id));
    }

    private Map<String, UUID> resolveVehicleTypeCodes(CompanyScope scope, Set<String> codes) {
        if (codes.isEmpty()) {
            return Map.of();
        }
        return vehicleTypeRepository.findByCompanyIdAndCodeIn(scope.companyId(), codes).stream()
                .collect(Collectors.toUnmodifiableMap(type -> type.code().toUpperCase(Locale.ROOT), VehicleType::id));
    }

    private Set<String> existingCodes(CompanyScope scope, List<ImportRow> rows) {
        Set<String> referenced = new HashSet<>();
        for (ImportRow row : rows) {
            String code = row.value(VehicleImportColumn.CODE.header());
            if (code != null) {
                referenced.add(code.trim().toUpperCase(Locale.ROOT));
            }
        }
        if (referenced.isEmpty()) {
            return Set.of();
        }
        return vehicleRepository.findByCompanyIdAndCodeIn(scope.companyId(), referenced).stream()
                .map(Vehicle::code)
                .collect(Collectors.toUnmodifiableSet());
    }

    private Set<String> existingLicensePlates(CompanyScope scope, List<ImportRow> rows) {
        Set<String> referenced = new HashSet<>();
        for (ImportRow row : rows) {
            String plate = row.value(VehicleImportColumn.LICENSE_PLATE.header());
            if (plate != null) {
                referenced.add(plate.trim().toUpperCase(Locale.ROOT));
            }
        }
        if (referenced.isEmpty()) {
            return Set.of();
        }
        return vehicleRepository.findByCompanyIdAndLicensePlateIn(scope.companyId(), referenced).stream()
                .map(Vehicle::licensePlate)
                .collect(Collectors.toUnmodifiableSet());
    }

    private void persist(CompanyScope scope, List<VehicleImportCandidate> candidates, UUID actorId) {
        if (candidates.isEmpty()) {
            return;
        }
        List<Vehicle> vehicles = new ArrayList<>(candidates.size());
        for (VehicleImportCandidate candidate : candidates) {
            vehicles.add(new Vehicle(scope.companyId(), candidate.code(), candidate.licensePlate(),
                    candidate.carrierId(), candidate.vehicleTypeId(), candidate.maxWeightOverrideKg(),
                    candidate.maxVolumeOverrideM3(), candidate.maxPalletsOverride(), candidate.availabilityStatus(),
                    candidate.externalReference(), actorId));
        }
        vehicleRepository.saveAll(vehicles);
        vehicleRepository.flush();
    }

    private ImportReport<VehicleImportPreview> report(
            Evaluation evaluation, boolean dryRun, boolean applied, UUID batchId) {
        List<VehicleImportPreview> previews =
                evaluation.candidates().stream().map(VehicleImportPreview::from).toList();
        int created = (int) evaluation.candidates().stream().filter(VehicleImportCandidate::isCreatable).count();
        int skipped = (int) evaluation.candidates().stream()
                .filter(candidate -> candidate.outcome() == ImportOutcome.SKIPPED_DUPLICATE).count();
        int rejected = evaluation.candidates().size() - created - skipped;

        return new ImportReport<>(dryRun, applied, batchId, evaluation.fileName(), evaluation.format(),
                evaluation.rowCount(), evaluation.candidates().size(), created, skipped, rejected,
                evaluation.issueCount(), evaluation.issueCount() > evaluation.issues().size(), previews,
                evaluation.issues());
    }
}
