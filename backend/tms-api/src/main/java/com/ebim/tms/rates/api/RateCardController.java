package com.ebim.tms.rates.api;

import com.ebim.tms.rates.application.RateCardFilter;
import com.ebim.tms.rates.application.RateCardRequest;
import com.ebim.tms.rates.application.RateCardService;
import com.ebim.tms.rates.application.RateCardView;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * Company-scoped CRUD for rate cards, following the {@code CarrierController} template: the
 * {@link CompanyScope} parameter is only ever supplied by the framework once
 * {@code CompanyScopeFilter} has validated {@code X-Company-Id} against an active membership.
 *
 * <p>There is no delete endpoint. A card is deactivated, never removed - an estimate calculated
 * from it keeps pointing at it, and {@code fk_trip_cost_rate_card} makes deletion impossible while
 * one does.
 */
@RestController
@RequestMapping("${tms.api.base-path}/rates/rate-cards")
@Tag(name = "Rate cards", description = "What a carrier charges, between two dates")
public class RateCardController {

    private final RateCardService rateCardService;

    public RateCardController(RateCardService rateCardService) {
        this.rateCardService = rateCardService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('rates.rate_card:read')")
    @Operation(summary = "List rate cards, filtered and paginated within the selected company")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<RateCardView> list(
            CompanyScope scope, @ModelAttribute RateCardFilter filter, @ModelAttribute PageQuery pageQuery) {
        return rateCardService.list(scope, filter, pageQuery);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('rates.rate_card:read')")
    @Operation(summary = "Get one rate card")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public RateCardView get(CompanyScope scope, @PathVariable UUID id) {
        return rateCardService.get(scope, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('rates.rate_card:manage')")
    @Operation(summary = "Create a rate card")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public RateCardView create(CompanyScope scope, @Valid @RequestBody RateCardRequest request) {
        return rateCardService.create(scope, request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('rates.rate_card:manage')")
    @Operation(summary = "Update a rate card; its carrier cannot be changed")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public RateCardView update(CompanyScope scope, @PathVariable UUID id, @Valid @RequestBody RateCardRequest request) {
        return rateCardService.update(scope, id, request);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('rates.rate_card:manage')")
    @Operation(summary = "Bring a rate card back into force, re-checking that nothing overlaps it")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public RateCardView activate(CompanyScope scope, @PathVariable UUID id) {
        return rateCardService.activate(scope, id);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('rates.rate_card:manage')")
    @Operation(summary = "Take a rate card out of force")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public RateCardView deactivate(CompanyScope scope, @PathVariable UUID id) {
        return rateCardService.deactivate(scope, id);
    }
}
