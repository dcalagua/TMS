package com.ebim.tms.masterdata.application.imports;

import com.ebim.tms.masterdata.domain.Location;
import com.ebim.tms.masterdata.domain.Zone;
import com.ebim.tms.masterdata.infrastructure.LocationRepository;
import com.ebim.tms.masterdata.infrastructure.ZoneRepository;
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
import com.ebim.tms.shared.settings.CompanySettingsPort;
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
 * The bulk Location import: parse, validate, preview, and - only when the whole file is clean -
 * apply. Mirrors {@code OrderImportService}'s two guarantees:
 *
 * <ol>
 *   <li><b>All or nothing.</b> {@link #apply} runs in one transaction and writes nothing at all
 *       if a single row has an issue.</li>
 *   <li><b>Idempotent.</b> Identity is {@code code}, unique per company
 *       ({@code uq_location_company_code}). A code already present in the company is skipped, not
 *       duplicated and not treated as an error, so re-uploading the same file is safe.</li>
 * </ol>
 *
 * <p>{@link #dryRun} and {@link #apply} share {@link #evaluate}, so a preview cannot describe an
 * outcome different from the one applying produces. A location created here is immediately
 * usable as a route origin or an order destination, because since V23 saving the location is
 * the whole write - there is no projection to keep in step.
 */
@Service
public class LocationImportService {

    private final LocationImportParser parser;
    private final LocationRepository locationRepository;
    private final ZoneRepository zoneRepository;
    private final ImportBatchRepository importBatchRepository;
    private final CompanySettingsPort companySettingsPort;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;

    public LocationImportService(LocationImportParser parser, LocationRepository locationRepository,
            ZoneRepository zoneRepository, ImportBatchRepository importBatchRepository,
            CompanySettingsPort companySettingsPort, AuditActorProvider auditActorProvider,
            AuditRecorder auditRecorder) {
        this.parser = parser;
        this.locationRepository = locationRepository;
        this.zoneRepository = zoneRepository;
        this.importBatchRepository = importBatchRepository;
        this.companySettingsPort = companySettingsPort;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
    }

    @Transactional(readOnly = true)
    public ImportReport<LocationImportPreview> dryRun(
            CompanyScope scope, byte[] content, String fileName, ImportFormat format) {
        Evaluation evaluation = evaluate(scope, content, fileName, format);
        return report(evaluation, true, false, null);
    }

    @Transactional
    public ImportReport<LocationImportPreview> apply(
            CompanyScope scope, byte[] content, String fileName, ImportFormat format) {
        Evaluation evaluation = evaluate(scope, content, fileName, format);
        if (!evaluation.issues().isEmpty()) {
            return report(evaluation, false, false, null);
        }

        UUID actorId = auditActorProvider.requireAppUserId();
        List<LocationImportCandidate> creatable =
                evaluation.candidates().stream().filter(LocationImportCandidate::isCreatable).toList();
        persist(scope, creatable, actorId);

        int skipped = evaluation.candidates().size() - creatable.size();
        ImportBatch batch = importBatchRepository.save(new ImportBatch(scope.companyId(), ImportEntityType.LOCATION,
                fileName, evaluation.format(), ImportSupport.sha256(content), evaluation.rowCount(),
                creatable.size(), skipped, actorId));
        auditRecorder.record(scope, AuditAggregateType.MASTER_DATA_IMPORT_BATCH, batch.id(), AuditAction.IMPORT_EXECUTED,
                Map.of("entityType", ImportEntityType.LOCATION.name(), "createdCount", creatable.size(),
                        "skippedCount", skipped));

        return report(evaluation, false, true, batch.id());
    }

    private record Evaluation(String fileName, ImportFormat format, int rowCount,
            List<LocationImportCandidate> candidates, List<ImportIssue> issues, int issueCount) {
    }

    private Evaluation evaluate(CompanyScope scope, byte[] content, String fileName, ImportFormat format) {
        ImportLimits limits = ImportLimits.standard();
        List<ImportRow> rows = parser.parse(content, format, limits);

        LocationImportValidator.MasterSnapshot snapshot = new LocationImportValidator.MasterSnapshot(
                resolveZoneCodes(scope, LocationImportValidator.referencedZoneCodes(rows)),
                existingCodes(scope, rows));

        // The two blank-cell defaults come from the tenant, not from the launch market: the zone
        // from tms.company.time_zone and the country from tms.company_settings (migration V34).
        // Both are resolved once for the file, and both are applied only to a cell the operator
        // left empty - an import never overwrites a country somebody typed.
        LocationImportValidator.Result result = LocationImportValidator.validate(rows, snapshot,
                scope.timeZone(), companySettingsPort.settingsOf(scope.companyId()).defaultCountry());
        int issueCount = result.issues().size();
        List<ImportIssue> reported = ImportSupport.truncate(result.issues(), limits.maxReportedIssues());

        return new Evaluation(fileName, format, rows.size(), result.candidates(), reported, issueCount);
    }

    private Map<String, UUID> resolveZoneCodes(CompanyScope scope, Set<String> codes) {
        if (codes.isEmpty()) {
            return Map.of();
        }
        return zoneRepository.findByCompanyIdAndCodeIn(scope.companyId(), codes).stream()
                .collect(Collectors.toUnmodifiableMap(zone -> zone.code().toUpperCase(Locale.ROOT), Zone::id));
    }

    /** Which of the file's codes this company already holds - asked once, for the codes the file actually mentions. */
    private Set<String> existingCodes(CompanyScope scope, List<ImportRow> rows) {
        Set<String> referenced = new HashSet<>();
        for (ImportRow row : rows) {
            String code = row.value(LocationImportColumn.CODE.header());
            if (code != null) {
                referenced.add(code.trim().toUpperCase(Locale.ROOT));
            }
        }
        if (referenced.isEmpty()) {
            return Set.of();
        }
        return locationRepository.findByCompanyIdAndCodeIn(scope.companyId(), referenced).stream()
                .map(Location::code)
                .collect(Collectors.toUnmodifiableSet());
    }

    private void persist(CompanyScope scope, List<LocationImportCandidate> candidates, UUID actorId) {
        List<Location> saved = new ArrayList<>(candidates.size());
        for (LocationImportCandidate candidate : candidates) {
            Location location = new Location(scope.companyId(), candidate.code(), candidate.name(), candidate.type(),
                    candidate.address(), candidate.addressReference(), candidate.district(), candidate.province(),
                    candidate.department(), candidate.country(), candidate.timeZone(), candidate.latitude(),
                    candidate.longitude(), candidate.zoneId(), candidate.serviceTimeMinutes(),
                    candidate.externalSystem(), candidate.externalReference(), actorId);
            location.replaceRoles(candidate.roles());
            saved.add(location);
        }
        if (saved.isEmpty()) {
            return;
        }
        // saveAll, then one flush: a uniqueness violation here - two imports racing on the same
        // code - surfaces as a DataIntegrityViolationException, which ApiExceptionHandler turns
        // into a 409, and the whole transaction rolls back (all-or-nothing) - the same idiom
        // TransportOrderRepository.saveAll/flush uses.
        locationRepository.saveAll(saved);
        locationRepository.flush();
    }

    private ImportReport<LocationImportPreview> report(
            Evaluation evaluation, boolean dryRun, boolean applied, UUID batchId) {
        List<LocationImportPreview> previews =
                evaluation.candidates().stream().map(LocationImportPreview::from).toList();
        int created = (int) evaluation.candidates().stream().filter(LocationImportCandidate::isCreatable).count();
        int skipped = (int) evaluation.candidates().stream()
                .filter(candidate -> candidate.outcome() == ImportOutcome.SKIPPED_DUPLICATE).count();
        int rejected = evaluation.candidates().size() - created - skipped;

        return new ImportReport<>(dryRun, applied, batchId, evaluation.fileName(), evaluation.format(),
                evaluation.rowCount(), evaluation.candidates().size(), created, skipped, rejected,
                evaluation.issueCount(), evaluation.issueCount() > evaluation.issues().size(), previews,
                evaluation.issues());
    }
}
