package com.ebim.tms.audit.application;

import com.ebim.tms.audit.domain.AuditEvent;
import com.ebim.tms.audit.infrastructure.AuditEventRepository;
import com.ebim.tms.audit.infrastructure.AuditEventSpecifications;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.api.PageQuery.SortTerm;
import com.ebim.tms.shared.security.CompanyScope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Reading the audit trail. The counterpart to {@code AuditEventRecorder}, and deliberately a
 * second class rather than two halves of one: the write side runs inside somebody else's
 * transaction on every business action in the product, and giving it a query method would put a
 * reporting concern on the hottest path there is.
 *
 * <p><b>It is read-only, and there is no other kind of method here.</b> Not a convention - the
 * table is append-only and migration V22 revokes UPDATE and DELETE from the runtime role, so
 * there is no correction endpoint to write even if somebody wanted one. A trail that can be edited
 * answers a different question from the one an auditor is asking.
 *
 * <p><b>The tenant comes from the scope, never from the caller.</b> {@code AuditFilter} has no
 * {@code companyId} field and {@link AuditEventSpecifications#matching} takes it as a separate
 * argument, so the company predicate cannot be left off by forgetting to set something. RLS
 * (ADR-005) is the second line behind that, not the first.
 */
@Service
public class AuditQueryService {

    private static final Logger log = LoggerFactory.getLogger(AuditQueryService.class);

    /**
     * What a caller may sort by. {@code occurredAt} is the only one anybody has asked for, and
     * every other column here is backed by the same index; a sort over {@code metadata} would be
     * a full scan of an append-only table, which is why the allow-list exists at all.
     */
    private static final Set<String> SORTABLE_PROPERTIES =
            Set.of("occurredAt", "aggregateType", "action", "actorEmail");

    private final AuditEventRepository auditEventRepository;
    private final ObjectMapper objectMapper;

    public AuditQueryService(AuditEventRepository auditEventRepository, ObjectMapper objectMapper) {
        this.auditEventRepository = auditEventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * One page of this company's audit trail, newest first.
     *
     * <p>Newest-first is the default rather than an option a screen has to remember: an audit
     * trail is read to answer "what just happened", and a page of the oldest entries in a table
     * that grows forever is never the answer.
     */
    @Transactional(readOnly = true)
    public PageResponse<AuditEventView> list(CompanyScope scope, AuditFilter filter, PageQuery pageQuery) {
        requireOrderedRange(filter);

        Page<AuditEvent> page = auditEventRepository.findAll(
                AuditEventSpecifications.matching(scope.companyId(), filter), toPageable(pageQuery));

        List<AuditEventView> content = page.getContent().stream()
                .map(event -> AuditEventView.from(event, readMetadata(event)))
                .toList();
        return new PageResponse<>(content, pageQuery.pageNumber(), pageQuery.pageSize(), page.getTotalElements());
    }

    /**
     * A window that ends before it starts returns nothing, which looks exactly like "nothing
     * happened" - the one answer an audit screen must never give by accident. Refused instead.
     */
    private static void requireOrderedRange(AuditFilter filter) {
        if (filter.from() != null && filter.to() != null && filter.to().isBefore(filter.from())) {
            throw new InvalidRequestException("'to' must not be earlier than 'from'.");
        }
    }

    private Pageable toPageable(PageQuery pageQuery) {
        List<SortTerm> terms = pageQuery.sortTerms(SORTABLE_PROPERTIES);
        Sort sort = terms.isEmpty()
                ? Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"))
                : Sort.by(terms.stream()
                        .map(term -> term.descending()
                                ? Sort.Order.desc(term.property())
                                : Sort.Order.asc(term.property()))
                        .toList())
                // `id` after whatever was asked for: two entries written in the same millisecond
                // would otherwise page in an arbitrary order, and an audit trail that shows the
                // same row on pages 2 and 3 is not one anybody can work from.
                .and(Sort.by(Sort.Order.desc("id")));
        return PageRequest.of(pageQuery.pageNumber(), pageQuery.pageSize(), sort);
    }

    /**
     * The entry's annotation as a map, or empty when it cannot be read.
     *
     * <p>Empty rather than an exception. {@code AuditEventRecorder} truncates metadata past 4000
     * characters, which produces invalid JSON, and refusing the whole page because one entry from
     * six months ago was truncated would lose the trail to protect its footnotes.
     */
    private Map<String, String> readMetadata(AuditEvent event) {
        String metadata = event.metadata();
        if (metadata == null || metadata.isBlank()) {
            return Map.of();
        }
        try {
            Map<?, ?> parsed = objectMapper.readValue(metadata, Map.class);
            Map<String, String> values = new LinkedHashMap<>();
            parsed.forEach((key, value) -> values.put(String.valueOf(key), value == null ? null : String.valueOf(value)));
            return Map.copyOf(values);
        } catch (JacksonException unreadable) {
            log.warn("Audit event {} has metadata that could not be parsed; serving the entry without it.",
                    event.id());
            return Map.of();
        }
    }
}
