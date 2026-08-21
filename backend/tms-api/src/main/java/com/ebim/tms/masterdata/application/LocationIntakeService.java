package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.Location;
import com.ebim.tms.masterdata.domain.LocationRole;
import com.ebim.tms.masterdata.domain.LocationType;
import com.ebim.tms.masterdata.domain.Zone;
import com.ebim.tms.masterdata.infrastructure.LocationRepository;
import com.ebim.tms.masterdata.infrastructure.ZoneRepository;
import com.ebim.tms.shared.api.ConflictException;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.reference.IntakeOutcome;
import com.ebim.tms.shared.reference.LocationIntakeCommand;
import com.ebim.tms.shared.reference.LocationIntakePort;
import com.ebim.tms.shared.reference.LocationIntakeResult;
import com.ebim.tms.shared.security.CompanyScope;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The inbound integration's door into Locations: {@code masterdata}'s implementation of
 * {@link LocationIntakePort}.
 *
 * <p>It translates and it decides create-or-update. It does not validate business rules and it
 * does not touch the repository to write - every change goes through {@link LocationService}, so
 * an integration and the Locations screen cannot drift apart. That matters more here than
 * anywhere else in the product: an integration writes unattended, and a second, laxer write path
 * would be discovered months later as bad master data rather than immediately as a rejected form.
 *
 * <p>Field-level validation is not restated either. The command is mapped to the very
 * {@link LocationRequest} the controller binds and then run through the same Bean Validation
 * constraints Spring would have applied, so the code pattern, the length limits and the
 * coordinate bounds are enforced once and can never diverge between the two surfaces.
 */
@Service
public class LocationIntakeService implements LocationIntakePort {

    private final LocationService locationService;
    private final LocationRepository locationRepository;
    private final ZoneRepository zoneRepository;
    private final Validator validator;

    public LocationIntakeService(LocationService locationService, LocationRepository locationRepository,
            ZoneRepository zoneRepository, Validator validator) {
        this.locationService = locationService;
        this.locationRepository = locationRepository;
        this.zoneRepository = zoneRepository;
        this.validator = validator;
    }

    @Override
    @Transactional
    public LocationIntakeResult upsert(CompanyScope scope, LocationIntakeCommand command) {
        ExternalKey externalKey = externalKey(command);
        Optional<Location> existing = findExisting(scope, command, externalKey);
        LocationRequest request = toRequest(scope, command, existing.orElse(null));
        validate(request);

        if (existing.isEmpty()) {
            LocationView created = locationService.create(scope, request);
            applyActivation(scope, created.id(), created.active(), command.active());
            return new LocationIntakeResult(created.id(), created.code(), IntakeOutcome.CREATED);
        }

        Location current = existing.get();
        boolean changesAnything = differs(current, request) || activationChanges(current.active(), command.active());
        if (!changesAnything) {
            return new LocationIntakeResult(current.id(), current.code(), IntakeOutcome.UNCHANGED);
        }

        LocationView updated = locationService.update(scope, current.id(), request);
        applyActivation(scope, updated.id(), updated.active(), command.active());
        return new LocationIntakeResult(updated.id(), updated.code(), IntakeOutcome.UPDATED);
    }

    /**
     * Which row the payload is about.
     *
     * <p>The external key wins when the payload carries one, because it is the sending system's
     * own identity and survives a rename of the TMS code. When it resolves nothing, the code is
     * tried - that is how a location an operator created by hand gets adopted by the integration
     * on the first delivery instead of being duplicated.
     *
     * <p>The one case that is refused rather than resolved: the external key names row A and the
     * code names row B. Either answer would be wrong, and picking one silently would merge two
     * real places or steal an identity from one of them.
     */
    private Optional<Location> findExisting(CompanyScope scope, LocationIntakeCommand command, ExternalKey key) {
        Optional<Location> byCode = codeOf(command)
                .flatMap(code -> locationRepository.findByCompanyIdAndCode(scope.companyId(), code));
        if (key == null) {
            return byCode;
        }

        Optional<Location> byExternalKey = locationRepository.findByCompanyIdAndExternalSystemAndExternalReference(
                scope.companyId(), key.system(), key.reference());
        if (byExternalKey.isPresent() && byCode.isPresent()
                && !byExternalKey.get().id().equals(byCode.get().id())) {
            throw new ConflictException("externalReference '" + key.reference() + "' already identifies location '"
                    + byExternalKey.get().code() + "', but code '" + byCode.get().code()
                    + "' identifies a different one. Resolve the duplicate before resending.");
        }
        return byExternalKey.isPresent() ? byExternalKey : byCode;
    }

    private LocationRequest toRequest(CompanyScope scope, LocationIntakeCommand command, Location existing) {
        UUID zoneId = resolveZoneId(scope, command, existing);
        return new LocationRequest(
                trimmed(command.code()),
                trimmed(command.name()),
                parseType(command.type()),
                parseRoles(command.roles()),
                trimmed(command.address()),
                trimmed(command.addressReference()),
                trimmed(command.district()),
                trimmed(command.province()),
                trimmed(command.department()),
                trimmed(command.country()),
                trimmed(command.timeZone()),
                command.latitude(),
                command.longitude(),
                zoneId,
                command.serviceTimeMinutes(),
                trimmed(command.externalSystem()),
                trimmed(command.externalReference()));
    }

    /**
     * A zone the payload omits keeps the one the row already has, rather than clearing it. A
     * partner that only synchronises addresses must not silently undo an operator's zoning.
     */
    private UUID resolveZoneId(CompanyScope scope, LocationIntakeCommand command, Location existing) {
        String zoneCode = trimmed(command.zoneCode());
        if (zoneCode == null) {
            return existing != null ? existing.zoneId() : null;
        }
        Zone zone = zoneRepository.findByCompanyIdAndCode(scope.companyId(), zoneCode.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new InvalidRequestException(
                        "zoneCode '" + zoneCode + "' does not name a zone in this company."));
        return zone.id();
    }

    /**
     * Activation is a separate transition on {@link LocationService} - and deliberately so, since
     * deactivating also deactivates the origin/destination projections. The command's tri-state
     * {@code active} is mapped onto it here: null changes nothing.
     */
    private void applyActivation(CompanyScope scope, UUID id, boolean currentlyActive, Boolean requested) {
        if (!activationChanges(currentlyActive, requested)) {
            return;
        }
        if (requested) {
            locationService.activate(scope, id);
        } else {
            locationService.deactivate(scope, id);
        }
    }

    private static boolean activationChanges(boolean currentlyActive, Boolean requested) {
        return requested != null && requested != currentlyActive;
    }

    /**
     * Whether the payload asks for anything this module would actually write. Used only to report
     * {@link IntakeOutcome#UNCHANGED} honestly; a false negative would merely cost one redundant
     * update, never a lost change.
     */
    private static boolean differs(Location current, LocationRequest request) {
        return !equalsNormalized(current.code(), request.code())
                || !equalsNormalized(current.name(), request.name())
                || current.type() != request.type()
                || !current.roles().equals(request.roles())
                || !equalsNormalized(current.address(), request.address())
                || !equalsNormalized(current.addressReference(), request.addressReference())
                || !equalsNormalized(current.district(), request.district())
                || !equalsNormalized(current.province(), request.province())
                || !equalsNormalized(current.department(), request.department())
                || !equalsNormalized(current.country(), request.country())
                || !equalsNormalized(current.timeZone(), request.timeZone())
                || !numbersEqual(current.latitude(), request.latitude())
                || !numbersEqual(current.longitude(), request.longitude())
                || !java.util.Objects.equals(current.zoneId(), request.zoneId())
                || current.serviceTimeMinutes() != orZero(request.serviceTimeMinutes())
                || !equalsNormalized(current.externalSystem(), request.externalSystem())
                || !equalsNormalized(current.externalReference(), request.externalReference());
    }

    private static boolean equalsNormalized(String persisted, String requested) {
        String left = persisted == null || persisted.isBlank() ? null : persisted.trim();
        String right = requested == null || requested.isBlank() ? null : requested.trim();
        if (left == null || right == null) {
            return left == right;
        }
        return left.equalsIgnoreCase(right);
    }

    /** {@code compareTo}, not {@code equals}: 1.50 and 1.5 are the same coordinate. */
    private static boolean numbersEqual(java.math.BigDecimal persisted, java.math.BigDecimal requested) {
        if (persisted == null || requested == null) {
            return persisted == requested;
        }
        return persisted.compareTo(requested) == 0;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private void validate(LocationRequest request) {
        Set<ConstraintViolation<LocationRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    private static LocationType parseType(String type) {
        String candidate = trimmed(type);
        if (candidate == null) {
            // Left to Bean Validation's @NotNull, so the caller gets a field-level error rather
            // than a message about an enum they never sent.
            return null;
        }
        return parseEnum(LocationType.class, candidate, "type");
    }

    private static Set<LocationRole> parseRoles(Set<String> roles) {
        return roles.stream()
                .map(role -> parseEnum(LocationRole.class, role, "roles"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        String candidate = trimmed(value);
        try {
            return Enum.valueOf(type, candidate == null ? "" : candidate.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new InvalidRequestException(field + " must be one of "
                    + Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "))
                    + " (received: " + value + ").");
        }
    }

    private static Optional<String> codeOf(LocationIntakeCommand command) {
        return Optional.ofNullable(trimmed(command.code()));
    }

    private static ExternalKey externalKey(LocationIntakeCommand command) {
        String system = trimmed(command.externalSystem());
        String reference = trimmed(command.externalReference());
        if (reference == null) {
            return null;
        }
        if (system == null) {
            throw new InvalidRequestException("externalSystem is required whenever externalReference is provided.");
        }
        return new ExternalKey(system, reference);
    }

    private record ExternalKey(String system, String reference) {}

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
