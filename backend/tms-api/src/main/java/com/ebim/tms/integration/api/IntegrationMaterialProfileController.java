package com.ebim.tms.integration.api;

import com.ebim.tms.integration.application.IntegrationExecution;
import com.ebim.tms.integration.application.IntegrationMaterialProfileService;
import com.ebim.tms.integration.application.IntegrationPrincipal;
import com.ebim.tms.integration.application.IntegrationRequestExecutor;
import com.ebim.tms.integration.application.MaterialProfileBatchDelivery;
import com.ebim.tms.integration.application.MaterialProfileBatchResultV1;
import com.ebim.tms.integration.application.MaterialProfileDelivery;
import com.ebim.tms.integration.application.MaterialProfileResultV1;
import com.ebim.tms.integration.application.MaterialProfileV1;
import com.ebim.tms.integration.domain.IntegrationOperation;
import com.ebim.tms.shared.api.ApiHeaders;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.config.OpenApiConfig;
import com.ebim.tms.shared.reference.MaterialProfileIntakePort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Material profiles from the warehouse ({@code WAREHOUSE_EXECUTION_V1.md} section 7.1, ADR-022).
 *
 * <p>The body is read as a JSON tree before it becomes a record, because the contract gives an
 * absent field and an explicit {@code null} different meanings (absent keeps, {@code null} clears)
 * and a record cannot tell them apart. Which optional fields the JSON named travels beside the
 * record into the executor, so it is fingerprinted with the rest.
 */
@RestController
@RequestMapping(IntegrationApiPaths.V1 + "/material-profiles")
@Tag(name = "Integration v1 - Material profiles",
        description = "The warehouse's physical profile of each material (measures, stacking, nesting). TMS "
                + "keeps a dated copy and copies it onto new order lines.")
@SecurityRequirement(name = OpenApiConfig.INTEGRATION_SCHEME)
public class IntegrationMaterialProfileController {

    private final IntegrationMaterialProfileService service;
    private final IntegrationRequestExecutor executor;
    private final ObjectMapper objectMapper;

    public IntegrationMaterialProfileController(IntegrationMaterialProfileService service,
            IntegrationRequestExecutor executor, ObjectMapper objectMapper) {
        this.service = service;
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    @PutMapping(value = "/{materialCode}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('integration.material:write')")
    @Operation(summary = "Create or update one material profile (MATERIAL_PROFILE_UPSERT)",
            description = "201 CREATED, 200 UPDATED / UNCHANGED / STALE. An absent field keeps the stored value, "
                    + "an explicit null clears it. A sourceUpdatedAt older than the stored one is STALE and "
                    + "changes nothing.")
    @Parameter(name = ApiHeaders.IDEMPOTENCY_KEY, in = ParameterIn.HEADER,
            description = "Recommended. Repeating it with the same payload replays the first response; with "
                    + "another payload it is refused with 409.")
    public ResponseEntity<MaterialProfileResultV1> upsert(
            IntegrationPrincipal principal,
            @PathVariable String materialCode,
            @RequestHeader(value = ApiHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        requireObject(body, "The body");
        MaterialProfileDelivery delivery = new MaterialProfileDelivery(materialCode, profile(body), fieldsSent(body));
        IntegrationExecution<MaterialProfileResultV1> execution = executor.execute(principal,
                IntegrationOperation.MATERIAL_PROFILE_UPSERT, idempotencyKey, delivery,
                MaterialProfileResultV1.class, () -> service.upsert(principal, delivery));
        return IntegrationResponses.of(execution);
    }

    @PostMapping(value = "/batch", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('integration.material:write')")
    @Operation(summary = "Create or update up to 500 material profiles (MATERIAL_PROFILE_BATCH)",
            description = "Not atomic: each item stands alone. 200 when no item was INVALID, 207 otherwise, with "
                    + "the outcome of every item at the index it was sent.")
    @Parameter(name = ApiHeaders.IDEMPOTENCY_KEY, in = ParameterIn.HEADER, description = "Recommended.")
    public ResponseEntity<MaterialProfileBatchResultV1> batch(
            IntegrationPrincipal principal,
            @RequestHeader(value = ApiHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody JsonNode body) {
        requireObject(body, "The body");
        JsonNode items = body.get("profiles");
        if (items == null || !items.isArray()) {
            throw new InvalidRequestException("profiles must be an array of material profiles.");
        }
        List<MaterialProfileV1> profiles = new ArrayList<>(items.size());
        List<List<String>> fieldsSent = new ArrayList<>(items.size());
        for (int index = 0; index < items.size(); index++) {
            JsonNode item = items.get(index);
            requireObject(item, "profiles[" + index + "]");
            profiles.add(profile(item));
            fieldsSent.add(fieldsSent(item));
        }
        JsonNode sourceSystem = body.get("sourceSystem");
        MaterialProfileBatchDelivery delivery = new MaterialProfileBatchDelivery(
                sourceSystem == null || sourceSystem.isNull() ? null : sourceSystem.asString(), profiles, fieldsSent);
        IntegrationExecution<MaterialProfileBatchResultV1> execution = executor.execute(principal,
                IntegrationOperation.MATERIAL_PROFILE_BATCH, idempotencyKey, delivery,
                MaterialProfileBatchResultV1.class, () -> service.batch(principal, delivery));
        return IntegrationResponses.of(execution);
    }

    private MaterialProfileV1 profile(JsonNode node) {
        try {
            return objectMapper.treeToValue(node, MaterialProfileV1.class);
        } catch (JacksonException malformed) {
            throw new InvalidRequestException("The body is not a valid material profile: "
                    + malformed.getOriginalMessage());
        }
    }

    /** The optional fields the JSON named, sorted: absent keeps, named with null clears. */
    private static List<String> fieldsSent(JsonNode node) {
        return MaterialProfileIntakePort.OPTIONAL_FIELDS.stream().filter(node::has).sorted().toList();
    }

    private static void requireObject(JsonNode node, String what) {
        if (node == null || !node.isObject()) {
            throw new InvalidRequestException(what + " must be a JSON object.");
        }
    }
}
