package com.ebim.tms.fleet.application;

import com.ebim.tms.fleet.domain.Driver;
import com.ebim.tms.fleet.domain.FleetComplianceEvaluator;
import com.ebim.tms.fleet.domain.FleetComplianceEvaluator.Rule;
import com.ebim.tms.fleet.domain.FleetComplianceSettings;
import com.ebim.tms.fleet.domain.FleetDocument;
import com.ebim.tms.fleet.domain.FleetDocumentPolicy;
import com.ebim.tms.fleet.domain.Vehicle;
import com.ebim.tms.fleet.infrastructure.DriverRepository;
import com.ebim.tms.fleet.infrastructure.FleetComplianceSettingsRepository;
import com.ebim.tms.fleet.infrastructure.FleetDocumentPolicyRepository;
import com.ebim.tms.fleet.infrastructure.FleetDocumentRepository;
import com.ebim.tms.fleet.infrastructure.VehicleRepository;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.reference.ComplianceFinding;
import com.ebim.tms.shared.reference.FleetCompliancePort;
import com.ebim.tms.shared.reference.FleetDocumentStatus;
import com.ebim.tms.shared.reference.FleetDocumentSubject;
import com.ebim.tms.shared.reference.FleetDocumentType;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The fleet compliance rule (migration V72): which papers each vehicle and driver has, whether they
 * allow them to work on a day, and the company's policy behind that answer.
 *
 * <p>{@code planning} reaches the rule through {@link FleetCompliancePort}; the work-assignment
 * service, in this module, calls it directly. Both ask the same {@link FleetComplianceEvaluator}, so
 * a truck is never assignable on one screen and refused by the endpoint behind another.
 */
@Service
public class FleetComplianceService implements FleetCompliancePort {

    /** The stable bucket codes of {@link FleetComplianceViews.Summary#ageing}, in display order. */
    static final List<String> AGEING_BUCKETS = List.of("EXPIRED_OVER_90", "EXPIRED_31_90", "EXPIRED_1_30",
            "DUE_0_7", "DUE_8_30", "DUE_31_90", "DUE_OVER_90", "NO_DATE");

    private final FleetDocumentRepository documentRepository;
    private final FleetDocumentPolicyRepository policyRepository;
    private final FleetComplianceSettingsRepository settingsRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverRepository driverRepository;
    private final AuditActorProvider auditActorProvider;
    private final AuditRecorder auditRecorder;

    public FleetComplianceService(FleetDocumentRepository documentRepository,
            FleetDocumentPolicyRepository policyRepository, FleetComplianceSettingsRepository settingsRepository,
            VehicleRepository vehicleRepository, DriverRepository driverRepository,
            AuditActorProvider auditActorProvider, AuditRecorder auditRecorder) {
        this.documentRepository = documentRepository;
        this.policyRepository = policyRepository;
        this.settingsRepository = settingsRepository;
        this.vehicleRepository = vehicleRepository;
        this.driverRepository = driverRepository;
        this.auditActorProvider = auditActorProvider;
        this.auditRecorder = auditRecorder;
    }

    // ------------------------------------------------------------------ the port

    @Override
    @Transactional(readOnly = true)
    public List<ComplianceFinding> findingsFor(UUID companyId, UUID vehicleId, UUID driverId, LocalDate on) {
        if (vehicleId == null && driverId == null) {
            return List.of();
        }
        Map<FleetDocumentType, Rule> rules = rulesOf(companyId);
        int alertDays = alertDaysOf(companyId);
        List<ComplianceFinding> findings = new ArrayList<>();
        if (vehicleId != null) {
            vehicleRepository.findByIdAndCompanyId(vehicleId, companyId).ifPresent(vehicle -> findings.addAll(
                    FleetComplianceEvaluator.evaluate(FleetDocumentSubject.VEHICLE, vehicle.id(),
                            vehicle.licensePlate(),
                            documentRepository.findByCompanyIdAndVehicleIdInAndActiveTrue(companyId, Set.of(vehicleId)),
                            rules, on, alertDays)));
        }
        if (driverId != null) {
            driverRepository.findByIdAndCompanyId(driverId, companyId).ifPresent(driver -> findings.addAll(
                    FleetComplianceEvaluator.evaluate(FleetDocumentSubject.DRIVER, driver.id(), driver.code(),
                            documentRepository.findByCompanyIdAndDriverIdInAndActiveTrue(companyId, Set.of(driverId)),
                            rules, on, alertDays)));
        }
        return List.copyOf(findings);
    }

    // ------------------------------------------------------------------ the company's rule

    /** The company's rules by type; a type without a row follows its default. */
    Map<FleetDocumentType, Rule> rulesOf(UUID companyId) {
        Map<FleetDocumentType, Rule> rules = new EnumMap<>(FleetDocumentType.class);
        for (FleetDocumentType type : FleetDocumentType.values()) {
            rules.put(type, Rule.defaultOf(type));
        }
        for (FleetDocumentPolicy policy : policyRepository.findByCompanyId(companyId)) {
            rules.put(policy.documentType(), new Rule(policy.mandatory(), policy.enforcement()));
        }
        return rules;
    }

    int alertDaysOf(UUID companyId) {
        return settingsRepository.findByCompanyId(companyId)
                .map(FleetComplianceSettings::expiryAlertDays)
                .orElse(FleetDocumentStatus.DEFAULT_ALERT_DAYS);
    }

    @Transactional(readOnly = true)
    public FleetComplianceViews.Policy policy(CompanyScope scope) {
        Set<FleetDocumentType> configured = policyRepository.findByCompanyId(scope.companyId()).stream()
                .map(FleetDocumentPolicy::documentType).collect(Collectors.toSet());
        Map<FleetDocumentType, Rule> rules = rulesOf(scope.companyId());
        List<FleetComplianceViews.TypePolicy> types = new ArrayList<>();
        for (FleetDocumentType type : FleetDocumentType.values()) {
            Rule rule = rules.get(type);
            types.add(new FleetComplianceViews.TypePolicy(type, type.subject(), rule.mandatory(), rule.enforcement(),
                    type.defaultMandatory(), type.defaultEnforcement(), configured.contains(type)));
        }
        return new FleetComplianceViews.Policy(alertDaysOf(scope.companyId()), types);
    }

    /**
     * Saves the alert window and every type sent. Each changed value is audited; re-saving the same
     * policy writes nothing.
     */
    @Transactional
    public FleetComplianceViews.Policy updatePolicy(CompanyScope scope, FleetComplianceViews.PolicyRequest request) {
        UUID actorId = auditActorProvider.requireAppUserId();
        UUID companyId = scope.companyId();
        Set<FleetDocumentType> seen = new HashSet<>();
        for (FleetComplianceViews.TypePolicyRequest type : request.types()) {
            if (!seen.add(type.documentType())) {
                throw new InvalidRequestException("Document type " + type.documentType() + " is listed twice.");
            }
        }

        FleetComplianceSettings settings = settingsRepository.findByCompanyId(companyId).orElse(null);
        if (settings == null) {
            if (request.alertDays() != FleetDocumentStatus.DEFAULT_ALERT_DAYS) {
                settingsRepository.save(new FleetComplianceSettings(companyId, request.alertDays(), actorId));
                auditRecorder.record(scope, AuditAggregateType.COMPANY, companyId, AuditAction.UPDATE,
                        Map.of("fleetDocumentAlertDays", request.alertDays()));
            }
        } else if (settings.change(request.alertDays(), actorId)) {
            settingsRepository.save(settings);
            auditRecorder.record(scope, AuditAggregateType.COMPANY, companyId, AuditAction.UPDATE,
                    Map.of("fleetDocumentAlertDays", request.alertDays()));
        }

        Map<FleetDocumentType, FleetDocumentPolicy> existing = policyRepository.findByCompanyId(companyId).stream()
                .collect(Collectors.toMap(FleetDocumentPolicy::documentType, Function.identity()));
        for (FleetComplianceViews.TypePolicyRequest type : request.types()) {
            FleetDocumentPolicy policy = existing.get(type.documentType());
            boolean changed;
            if (policy == null) {
                policy = new FleetDocumentPolicy(companyId, type.documentType(), type.mandatory(), type.enforcement(),
                        actorId);
                changed = true;
            } else {
                changed = policy.change(type.mandatory(), type.enforcement(), actorId);
            }
            if (changed) {
                FleetDocumentPolicy saved = policyRepository.save(policy);
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("documentType", type.documentType().name());
                details.put("mandatory", type.mandatory());
                details.put("enforcement", type.enforcement().name());
                auditRecorder.record(scope, AuditAggregateType.FLEET_DOCUMENT_POLICY, saved.id(), AuditAction.UPDATE,
                        details);
            }
        }
        return policy(scope);
    }

    // ------------------------------------------------------------------ reading the file

    /** One vehicle's or driver's file, every document active or not, and today's findings. */
    @Transactional(readOnly = true)
    public FleetComplianceViews.SubjectCompliance subject(CompanyScope scope, FleetDocumentSubject subjectType,
            UUID subjectId) {
        UUID companyId = scope.companyId();
        LocalDate today = scope.today();
        int alertDays = alertDaysOf(companyId);
        Map<FleetDocumentType, Rule> rules = rulesOf(companyId);
        String code;
        String name;
        List<FleetDocument> documents;
        if (subjectType == FleetDocumentSubject.VEHICLE) {
            Vehicle vehicle = vehicleRepository.findByIdAndCompanyId(subjectId, companyId)
                    .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found."));
            code = vehicle.licensePlate();
            name = vehicle.code();
            documents = documentRepository.findByCompanyIdAndVehicleIdOrderByDocumentTypeAscExpiresOnDesc(companyId,
                    subjectId);
        } else {
            Driver driver = driverRepository.findByIdAndCompanyId(subjectId, companyId)
                    .orElseThrow(() -> new ResourceNotFoundException("Driver not found."));
            code = driver.code();
            name = driver.fullName();
            documents = documentRepository.findByCompanyIdAndDriverIdOrderByDocumentTypeAscExpiresOnDesc(companyId,
                    subjectId);
        }
        List<FleetDocumentView> views = documents.stream()
                .map(document -> view(document, code, name, today, alertDays, rules))
                .toList();
        List<ComplianceFinding> findings = FleetComplianceEvaluator.evaluate(subjectType, subjectId, code,
                documents, rules, today, alertDays);
        return new FleetComplianceViews.SubjectCompliance(subjectType, subjectId, today, alertDays, views, findings);
    }

    /** The whole fleet's active papers, counted by status and by how far their expiry is. */
    @Transactional(readOnly = true)
    public FleetComplianceViews.Summary summary(CompanyScope scope) {
        UUID companyId = scope.companyId();
        LocalDate today = scope.today();
        int alertDays = alertDaysOf(companyId);
        List<FleetDocument> documents = documentRepository.findByCompanyIdAndActiveTrue(companyId);
        Map<FleetDocumentStatus, Long> byStatus = new EnumMap<>(FleetDocumentStatus.class);
        Map<String, Long> ageing = new LinkedHashMap<>();
        AGEING_BUCKETS.forEach(bucket -> ageing.put(bucket, 0L));
        for (FleetDocument document : documents) {
            byStatus.merge(document.statusOn(today, alertDays), 1L, Long::sum);
            ageing.merge(bucketOf(document.expiresOn(), today), 1L, Long::sum);
        }
        return new FleetComplianceViews.Summary(today, alertDays, documents.size(),
                byStatus.getOrDefault(FleetDocumentStatus.VALID, 0L),
                byStatus.getOrDefault(FleetDocumentStatus.EXPIRING_SOON, 0L),
                byStatus.getOrDefault(FleetDocumentStatus.EXPIRED, 0L),
                byStatus.getOrDefault(FleetDocumentStatus.UNRECORDED, 0L),
                missing(scope).size(),
                ageing.entrySet().stream()
                        .map(entry -> new FleetComplianceViews.AgeingBucket(entry.getKey(), entry.getValue()))
                        .toList());
    }

    static String bucketOf(LocalDate expiresOn, LocalDate today) {
        if (expiresOn == null) {
            return "NO_DATE";
        }
        long days = ChronoUnit.DAYS.between(today, expiresOn);
        if (days < -90) {
            return "EXPIRED_OVER_90";
        }
        if (days < -30) {
            return "EXPIRED_31_90";
        }
        if (days < 0) {
            return "EXPIRED_1_30";
        }
        if (days <= 7) {
            return "DUE_0_7";
        }
        if (days <= 30) {
            return "DUE_8_30";
        }
        return days <= 90 ? "DUE_31_90" : "DUE_OVER_90";
    }

    /**
     * Every active vehicle and driver without any active document of a mandatory type. Bounded by the
     * fleet (a few hundred units), so it is computed in memory from three queries.
     */
    @Transactional(readOnly = true)
    public List<FleetComplianceViews.MissingDocument> missing(CompanyScope scope) {
        UUID companyId = scope.companyId();
        Map<FleetDocumentType, Rule> rules = rulesOf(companyId);
        List<FleetDocumentType> mandatoryVehicle = mandatoryTypes(rules, FleetDocumentSubject.VEHICLE);
        List<FleetDocumentType> mandatoryDriver = mandatoryTypes(rules, FleetDocumentSubject.DRIVER);
        if (mandatoryVehicle.isEmpty() && mandatoryDriver.isEmpty()) {
            return List.of();
        }
        Set<String> onFile = documentRepository.findByCompanyIdAndActiveTrue(companyId).stream()
                .map(document -> document.subjectId() + "|" + document.documentType())
                .collect(Collectors.toSet());
        List<FleetComplianceViews.MissingDocument> missing = new ArrayList<>();
        if (!mandatoryVehicle.isEmpty()) {
            for (Vehicle vehicle : vehicleRepository.findByCompanyIdAndActiveTrue(companyId)) {
                for (FleetDocumentType type : mandatoryVehicle) {
                    if (!onFile.contains(vehicle.id() + "|" + type)) {
                        missing.add(new FleetComplianceViews.MissingDocument(FleetDocumentSubject.VEHICLE,
                                vehicle.id(), vehicle.licensePlate(), vehicle.code(), type,
                                rules.get(type).enforcement()));
                    }
                }
            }
        }
        if (!mandatoryDriver.isEmpty()) {
            for (Driver driver : driverRepository.findByCompanyIdAndActiveTrue(companyId)) {
                for (FleetDocumentType type : mandatoryDriver) {
                    if (!onFile.contains(driver.id() + "|" + type)) {
                        missing.add(new FleetComplianceViews.MissingDocument(FleetDocumentSubject.DRIVER,
                                driver.id(), driver.code(), driver.fullName(), type, rules.get(type).enforcement()));
                    }
                }
            }
        }
        missing.sort(java.util.Comparator
                .comparing(FleetComplianceViews.MissingDocument::subjectType)
                .thenComparing(FleetComplianceViews.MissingDocument::subjectCode)
                .thenComparing(FleetComplianceViews.MissingDocument::documentType));
        return List.copyOf(missing);
    }

    private static List<FleetDocumentType> mandatoryTypes(Map<FleetDocumentType, Rule> rules,
            FleetDocumentSubject subject) {
        return rules.entrySet().stream()
                .filter(entry -> entry.getKey().subject() == subject && entry.getValue().mandatory())
                .map(Map.Entry::getKey)
                .toList();
    }

    static FleetDocumentView view(FleetDocument document, String code, String name, LocalDate today, int alertDays,
            Map<FleetDocumentType, Rule> rules) {
        Rule rule = rules.getOrDefault(document.documentType(), Rule.defaultOf(document.documentType()));
        return FleetDocumentView.of(document, code, name, today, alertDays, rule.mandatory(), rule.enforcement());
    }
}
