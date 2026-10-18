package com.ebim.tms.integration.application;

import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.reference.MaterialProfileIntakePort;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The material profile half of the inbound API (ADR-022 section 1, ADR-025 section 2): one profile
 * or a batch, each through {@link MaterialProfileIntakePort} and therefore through
 * {@code masterdata}, which owns the rules.
 *
 * <p>Nothing here is {@code @Transactional}, for the reason {@code IntegrationLocationService}
 * gives: each item's transaction is opened by the port, so a refused item leaves the others
 * applied.
 */
@Service
public class IntegrationMaterialProfileService {

    /** The contract's bound (section 7.1), not the generic batch size: a material master is larger. */
    static final int MAX_BATCH_SIZE = 500;

    private static final String INVALID = "INVALID";

    private final MaterialProfileIntakePort intake;

    public IntegrationMaterialProfileService(MaterialProfileIntakePort intake) {
        this.intake = intake;
    }

    public IntegrationOutcome<MaterialProfileResultV1> upsert(IntegrationPrincipal principal,
            MaterialProfileDelivery delivery) {
        MaterialProfileV1 profile = delivery.profile();
        if (profile.materialCode() != null && !profile.materialCode().equals(delivery.materialCode())) {
            throw new InvalidRequestException("The body names materialCode '" + profile.materialCode()
                    + "' but the path names '" + delivery.materialCode() + "'.");
        }
        MaterialProfileIntakePort.Result result = intake.upsert(principal.companyScope(),
                command(principal, delivery.materialCode(), profile.sourceSystem(), profile, delivery.fieldsSent()));
        MaterialProfileResultV1 body = new MaterialProfileResultV1(result.materialCode(), result.outcome().name(),
                result.version(), result.sourceUpdatedAt());
        int httpStatus = result.outcome() == MaterialProfileIntakePort.Outcome.CREATED ? 201 : 200;
        return IntegrationOutcome.single(body, httpStatus, result.id(), profile.sourceSystem(), result.materialCode());
    }

    /**
     * Every item is attempted. A business refusal becomes that item's {@code INVALID}; an unexpected
     * fault is rethrown by {@link IntegrationItemError#from}, so a bug in TMS is a 500 rather than a
     * message blaming the warehouse's data.
     */
    public IntegrationOutcome<MaterialProfileBatchResultV1> batch(IntegrationPrincipal principal,
            MaterialProfileBatchDelivery delivery) {
        int size = delivery.profiles().size();
        if (size == 0) {
            throw new InvalidRequestException("profiles must contain at least one item.");
        }
        if (size > MAX_BATCH_SIZE) {
            throw new InvalidRequestException("A batch may carry at most " + MAX_BATCH_SIZE
                    + " material profiles; this one carried " + size + ".");
        }

        List<MaterialProfileBatchResultV1.Item> items = new ArrayList<>(size);
        int applied = 0;
        int refused = 0;
        for (int index = 0; index < size; index++) {
            MaterialProfileV1 profile = delivery.profiles().get(index);
            String sourceSystem = profile.sourceSystem() != null ? profile.sourceSystem() : delivery.sourceSystem();
            try {
                MaterialProfileIntakePort.Result result = intake.upsert(principal.companyScope(),
                        command(principal, profile.materialCode(), sourceSystem, profile,
                                delivery.fieldsSent().get(index)));
                items.add(new MaterialProfileBatchResultV1.Item(index, result.materialCode(),
                        result.outcome().name(), null));
                if (result.outcome() == MaterialProfileIntakePort.Outcome.CREATED
                        || result.outcome() == MaterialProfileIntakePort.Outcome.UPDATED) {
                    applied++;
                }
            } catch (RuntimeException failure) {
                IntegrationItemError error = IntegrationItemError.from(failure);
                items.add(new MaterialProfileBatchResultV1.Item(index, profile.materialCode(), INVALID,
                        error.message()));
                refused++;
            }
        }

        MaterialProfileBatchResultV1 body = new MaterialProfileBatchResultV1(size, applied, refused, items);
        return IntegrationOutcome.batch(body, size, size - refused, refused, delivery.sourceSystem());
    }

    private static MaterialProfileIntakePort.Command command(IntegrationPrincipal principal, String materialCode,
            String sourceSystem, MaterialProfileV1 profile, List<String> fieldsSent) {
        return new MaterialProfileIntakePort.Command(principal.id(), materialCode, sourceSystem,
                profile.sourceUpdatedAt(), new HashSet<>(fieldsSent), profile.description(), profile.lengthM(),
                profile.widthM(), profile.heightM(), profile.unitWeightKg(), profile.unitVolumeM3(),
                profile.stackMode(), profile.stackMaxQuantity(), profile.nestIncrementM(), profile.maxTopLoadKg(),
                profile.orientationLocked());
    }
}
