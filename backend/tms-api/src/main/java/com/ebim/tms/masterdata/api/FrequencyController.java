package com.ebim.tms.masterdata.api;

import com.ebim.tms.masterdata.application.FrequencyExceptionRequest;
import com.ebim.tms.masterdata.application.FrequencyExceptionView;
import com.ebim.tms.masterdata.application.FrequencyFilter;
import com.ebim.tms.masterdata.application.FrequencyRequest;
import com.ebim.tms.masterdata.application.FrequencyService;
import com.ebim.tms.masterdata.application.FrequencyView;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Company-scoped CRUD for frequencies, including the weekly rule grid (part of the main
 * request/response body) and the date-exception sub-resource. Follows the
 * {@code OriginController} template.
 *
 * <p>There is no delete endpoint for a frequency itself - deactivated, never removed, same as
 * every other master here. Exceptions are different: each row is an independent calendar fact
 * (an added or blacked-out date), not a master record other data refers to, so they support a
 * real delete.
 */
@RestController
@RequestMapping("${tms.api.base-path}/masterdata/frequencies")
@Tag(name = "Frequencies", description = "Delivery schedules: weekly cadence and date exceptions")
public class FrequencyController {

    private final FrequencyService frequencyService;

    public FrequencyController(FrequencyService frequencyService) {
        this.frequencyService = frequencyService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('masterdata.frequency:read')")
    @Operation(summary = "List frequencies, filtered and paginated within the selected company")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<FrequencyView> list(
            CompanyScope scope, @ModelAttribute FrequencyFilter filter, @ModelAttribute PageQuery pageQuery) {
        return frequencyService.list(scope, filter, pageQuery);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('masterdata.frequency:read')")
    @Operation(summary = "Get one frequency, including its weekly rules")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public FrequencyView get(CompanyScope scope, @PathVariable UUID id) {
        return frequencyService.get(scope, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('masterdata.frequency:manage')")
    @Operation(summary = "Create a frequency with its weekly rules")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public FrequencyView create(CompanyScope scope, @Valid @RequestBody FrequencyRequest request) {
        return frequencyService.create(scope, request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('masterdata.frequency:manage')")
    @Operation(summary = "Update a frequency, transactionally replacing its weekly rules")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public FrequencyView update(CompanyScope scope, @PathVariable UUID id, @Valid @RequestBody FrequencyRequest request) {
        return frequencyService.update(scope, id, request);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('masterdata.frequency:manage')")
    @Operation(summary = "Reactivate a frequency")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public FrequencyView activate(CompanyScope scope, @PathVariable UUID id) {
        return frequencyService.activate(scope, id);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('masterdata.frequency:manage')")
    @Operation(summary = "Deactivate a frequency")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public FrequencyView deactivate(CompanyScope scope, @PathVariable UUID id) {
        return frequencyService.deactivate(scope, id);
    }

    @GetMapping("/{id}/exceptions")
    @PreAuthorize("hasAuthority('masterdata.frequency:read')")
    @Operation(summary = "List the date exceptions of a frequency")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public List<FrequencyExceptionView> listExceptions(CompanyScope scope, @PathVariable UUID id) {
        return frequencyService.listExceptions(scope, id);
    }

    @PostMapping("/{id}/exceptions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('masterdata.frequency:manage')")
    @Operation(summary = "Add a date exception (an extra service date or a blackout)")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public FrequencyExceptionView createException(
            CompanyScope scope, @PathVariable UUID id, @Valid @RequestBody FrequencyExceptionRequest request) {
        return frequencyService.createException(scope, id, request);
    }

    @DeleteMapping("/{id}/exceptions/{exceptionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('masterdata.frequency:manage')")
    @Operation(summary = "Remove a date exception")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public void deleteException(CompanyScope scope, @PathVariable UUID id, @PathVariable UUID exceptionId) {
        frequencyService.deleteException(scope, id, exceptionId);
    }
}
