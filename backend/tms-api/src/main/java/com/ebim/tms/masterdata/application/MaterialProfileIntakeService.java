package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.MaterialProfile;
import com.ebim.tms.masterdata.infrastructure.MaterialProfileRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.geometry.PhysicalBlock;
import com.ebim.tms.shared.geometry.StackMode;
import com.ebim.tms.shared.reference.MaterialProfileIntakePort;
import com.ebim.tms.shared.security.CompanyScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The warehouse's door into material profiles: {@code masterdata}'s implementation of
 * {@link MaterialProfileIntakePort} (ADR-022 section 1, {@code WAREHOUSE_EXECUTION_V1.md} 7.1).
 *
 * <h2>The rules, in the order they are applied</h2>
 *
 * <ol>
 *   <li><b>Stale protection.</b> A write whose {@code sourceUpdatedAt} is older than the stored one
 *       is {@code STALE}: nothing is applied and the stored copy is reported back. Deliveries may
 *       arrive out of order; the owner's clock decides, not the arrival order.</li>
 *   <li><b>Merge.</b> An absent field keeps the stored value, an explicit {@code null} clears it.
 *       On a first write there is nothing to keep, so absent is empty.</li>
 *   <li><b>Validation of the result</b>, not of the payload: a PUT that only sends
 *       {@code stackMode: "NESTABLE"} is refused when the stored copy has no nest increment, because
 *       what would be stored is what must be valid.</li>
 *   <li><b>Change detection.</b> The same content is {@code UNCHANGED} and writes nothing - not even
 *       a newer {@code sourceUpdatedAt}, so a redelivery never bumps the version an order line
 *       records.</li>
 * </ol>
 *
 * <p>Numbers are rounded to the column's scale before they are compared, so a value the database
 * would round on the way in does not make every redelivery look like a change.
 *
 * <p>Transactional per call, deliberately: the batch endpoint calls this once per item and the
 * contract says each item stands alone.
 */
@Service
public class MaterialProfileIntakeService implements MaterialProfileIntakePort {

    private static final Pattern SOURCE_SYSTEM = Pattern.compile("^[A-Z0-9_]{2,40}$");
    private static final int MAX_CODE_LENGTH = 64;
    private static final int MAX_DESCRIPTION_LENGTH = 200;

    private final MaterialProfileRepository repository;
    private final AuditRecorder auditRecorder;

    /** numeric(10,3) and numeric(10,4) (V59). */
    private static final BigDecimal MAX_UNIT_WEIGHT_KG = new BigDecimal("9999999.999");
    private static final BigDecimal MAX_UNIT_VOLUME_M3 = new BigDecimal("999999.9999");

    private static final Pattern CONSTRAINT_NAME = Pattern.compile("constraint \"([a-z0-9_]+)\"");

    public MaterialProfileIntakeService(MaterialProfileRepository repository, AuditRecorder auditRecorder) {
        this.repository = repository;
        this.auditRecorder = auditRecorder;
    }

    @Override
    @Transactional
    public Result upsert(CompanyScope scope, Command command) {
        String materialCode = requireCode(command.materialCode());
        requireHeader(command);

        Optional<MaterialProfile> existing = repository.findByCompanyIdAndMaterialCode(scope.companyId(), materialCode);
        if (existing.isPresent() && existing.get().isNewerThan(command.sourceUpdatedAt())) {
            MaterialProfile stored = existing.get();
            return new Result(stored.id(), materialCode, Outcome.STALE, stored.version(), stored.sourceUpdatedAt());
        }

        MaterialProfile.Content content = merge(command, existing.map(MaterialProfile::content).orElse(null));
        List<String> violations = new ArrayList<>(content.block().violations());
        // The unit figures obey V59's CHECKs too (positive, numeric(10,3) and numeric(10,4)). Refused
        // here with a sentence: left to the database they became a constraint violation that save()
        // reported as a concurrent write, and the sender retried a payload that can never be stored.
        unitFigure(violations, "unitWeightKg", content.unitWeightKg(), MAX_UNIT_WEIGHT_KG, 3);
        unitFigure(violations, "unitVolumeM3", content.unitVolumeM3(), MAX_UNIT_VOLUME_M3, 4);
        if (!violations.isEmpty()) {
            throw new InvalidRequestException("The material profile '" + materialCode + "' is not valid: "
                    + String.join("; ", violations) + ".");
        }

        if (existing.isEmpty()) {
            MaterialProfile created = save(new MaterialProfile(scope.companyId(), materialCode, content,
                    command.sourceUpdatedAt(), command.integrationClientId()), materialCode);
            audit(scope, created, AuditAction.MATERIAL_PROFILE_CREATED);
            return result(created, Outcome.CREATED);
        }

        MaterialProfile stored = existing.get();
        if (stored.content().sameAs(content)) {
            return result(stored, Outcome.UNCHANGED);
        }
        stored.apply(content, command.sourceUpdatedAt(), command.integrationClientId());
        MaterialProfile updated = save(stored, materialCode);
        audit(scope, updated, AuditAction.MATERIAL_PROFILE_UPDATED);
        return result(updated, Outcome.UPDATED);
    }

    /** The stored copy after this write: each optional field from the payload when it was sent. */
    private static MaterialProfile.Content merge(Command command, MaterialProfile.Content stored) {
        PhysicalBlock storedBlock = stored == null ? null : stored.block();
        String description = pick(command, "description", trimmedDescription(command.description()),
                stored, MaterialProfile.Content::description);
        BigDecimal unitWeightKg = pick(command, "unitWeightKg", scaled(command.unitWeightKg(), 3),
                stored, MaterialProfile.Content::unitWeightKg);
        BigDecimal unitVolumeM3 = pick(command, "unitVolumeM3", scaled(command.unitVolumeM3(), 4),
                stored, MaterialProfile.Content::unitVolumeM3);
        PhysicalBlock block = new PhysicalBlock(
                pick(command, "lengthM", scaled(command.lengthM(), 4), storedBlock, PhysicalBlock::lengthM),
                pick(command, "widthM", scaled(command.widthM(), 4), storedBlock, PhysicalBlock::widthM),
                pick(command, "heightM", scaled(command.heightM(), 4), storedBlock, PhysicalBlock::heightM),
                pick(command, "stackMode", stackMode(command.stackMode()), storedBlock, PhysicalBlock::stackMode),
                pick(command, "stackMaxQuantity", command.stackMaxQuantity(), storedBlock,
                        PhysicalBlock::stackMaxQuantity),
                pick(command, "nestIncrementM", scaled(command.nestIncrementM(), 4), storedBlock,
                        PhysicalBlock::nestIncrementM),
                pick(command, "maxTopLoadKg", scaled(command.maxTopLoadKg(), 3), storedBlock,
                        PhysicalBlock::maxTopLoadKg),
                Boolean.TRUE.equals(pick(command, "orientationLocked", command.orientationLocked(), storedBlock,
                        PhysicalBlock::orientationLocked)));
        return new MaterialProfile.Content(description, command.sourceSystem(), unitWeightKg, unitVolumeM3, block);
    }

    private static <S, T> T pick(Command command, String field, T sent, S stored, Function<S, T> storedValue) {
        if (stored == null || command.sent(field)) {
            return sent;
        }
        return storedValue.apply(stored);
    }

    private static String requireCode(String materialCode) {
        if (materialCode == null || materialCode.isBlank()) {
            throw new InvalidRequestException("materialCode is required.");
        }
        if (materialCode.length() > MAX_CODE_LENGTH) {
            throw new InvalidRequestException("materialCode may be at most " + MAX_CODE_LENGTH + " characters.");
        }
        return materialCode;
    }

    private static void requireHeader(Command command) {
        if (command.sourceSystem() == null || !SOURCE_SYSTEM.matcher(command.sourceSystem()).matches()) {
            throw new InvalidRequestException("sourceSystem is required and must match " + SOURCE_SYSTEM.pattern() + ".");
        }
        if (command.sourceUpdatedAt() == null) {
            throw new InvalidRequestException("sourceUpdatedAt is required: it is how an older delivery is told apart.");
        }
        if (command.description() != null && command.description().trim().length() > MAX_DESCRIPTION_LENGTH) {
            throw new InvalidRequestException("description may be at most " + MAX_DESCRIPTION_LENGTH + " characters.");
        }
    }

    private static StackMode stackMode(String code) {
        if (code == null) {
            return null;
        }
        return StackMode.fromCode(code).orElseThrow(() -> new InvalidRequestException(
                "stackMode '" + code + "' is not one of " + StackMode.codes() + " (or null when not declared)."));
    }

    private static String trimmedDescription(String description) {
        return description == null || description.isBlank() ? null : description.trim();
    }

    private static BigDecimal scaled(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }

    private static void unitFigure(List<String> found, String field, BigDecimal value, BigDecimal max, int scale) {
        if (value != null && (value.signum() <= 0 || value.compareTo(max) > 0)) {
            found.add(field + " must be greater than zero and at most " + max.toPlainString() + " (" + scale
                    + " decimals)");
        }
    }

    /**
     * Only a real race is a conflict: another delivery updated the row (optimistic lock) or inserted the
     * same material first ({@code uq_material_profile_code}). Any other constraint is a payload the
     * database refuses and will go on refusing, so it is invalid - a "send it again" would loop.
     */
    private MaterialProfile save(MaterialProfile profile, String materialCode) {
        try {
            return repository.saveAndFlush(profile);
        } catch (ObjectOptimisticLockingFailureException raced) {
            throw concurrentWrite(materialCode);
        } catch (DataIntegrityViolationException refused) {
            String message = String.valueOf(refused.getMostSpecificCause().getMessage());
            if (message.contains("uq_material_profile_code")) {
                throw concurrentWrite(materialCode);
            }
            throw new InvalidRequestException("The material profile '" + materialCode
                    + "' is not valid: the database refused it (" + constraintOf(message) + ").");
        }
    }

    private static ConflictException concurrentWrite(String materialCode) {
        return new ConflictException("The material profile '" + materialCode
                + "' was written concurrently by another delivery. Send it again.");
    }

    /** The constraint a PostgreSQL message names, never the row values it may quote. */
    private static String constraintOf(String message) {
        Matcher constraint = CONSTRAINT_NAME.matcher(message);
        return constraint.find() ? constraint.group(1) : "constraint violation";
    }

    private void audit(CompanyScope scope, MaterialProfile profile, AuditAction action) {
        auditRecorder.record(scope, AuditAggregateType.MATERIAL_PROFILE, profile.id(), action,
                Map.of("materialCode", profile.materialCode(), "sourceSystem", profile.sourceSystem(),
                        "version", profile.version()));
    }

    private static Result result(MaterialProfile profile, Outcome outcome) {
        return new Result(profile.id(), profile.materialCode(), outcome, profile.version(), profile.sourceUpdatedAt());
    }
}
