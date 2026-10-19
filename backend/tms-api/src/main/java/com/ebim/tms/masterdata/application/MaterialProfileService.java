package com.ebim.tms.masterdata.application;

import com.ebim.tms.masterdata.domain.MaterialProfile;
import com.ebim.tms.masterdata.infrastructure.MaterialProfileRepository;
import com.ebim.tms.masterdata.infrastructure.MaterialProfileSpecifications;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.api.ResourceNotFoundException;
import com.ebim.tms.shared.security.CompanyScope;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The human read side of material profiles (ADR-022 section 1). Read only by design: the warehouse
 * owns the profile and writes it through {@link MaterialProfileIntakeService}; a screen that edited
 * it would be a second owner whose corrections the next synchronisation silently undoes.
 */
@Service
public class MaterialProfileService {

    private static final Set<String> SORTABLE_PROPERTIES =
            Set.of("materialCode", "sourceUpdatedAt", "updatedAt");

    private final MaterialProfileRepository repository;

    public MaterialProfileService(MaterialProfileRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public PageResponse<MaterialProfileView> list(CompanyScope scope, MaterialProfileFilter filter,
            PageQuery pageQuery) {
        Page<MaterialProfile> page = repository.findAll(
                MaterialProfileSpecifications.matching(scope.companyId(), filter.search()), toPageable(pageQuery));
        List<MaterialProfileView> content = page.getContent().stream().map(MaterialProfileView::from).toList();
        return new PageResponse<>(content, pageQuery.pageNumber(), pageQuery.pageSize(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public MaterialProfileView get(CompanyScope scope, UUID id) {
        return repository.findByIdAndCompanyId(id, scope.companyId())
                .map(MaterialProfileView::from)
                .orElseThrow(() -> new ResourceNotFoundException("Material profile " + id + " was not found."));
    }

    private static Pageable toPageable(PageQuery pageQuery) {
        List<PageQuery.SortTerm> terms = pageQuery.sortTerms(SORTABLE_PROPERTIES);
        Sort sort = terms.isEmpty()
                ? Sort.by(Sort.Direction.ASC, "materialCode")
                : Sort.by(terms.stream()
                        .map(term -> new Sort.Order(
                                term.descending() ? Sort.Direction.DESC : Sort.Direction.ASC, term.property()))
                        .toList());
        return PageRequest.of(pageQuery.pageNumber(), pageQuery.pageSize(), sort);
    }
}
