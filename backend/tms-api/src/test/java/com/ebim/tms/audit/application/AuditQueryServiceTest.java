package com.ebim.tms.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ebim.tms.audit.domain.AuditEvent;
import com.ebim.tms.audit.infrastructure.AuditEventRepository;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.security.CompanyScope;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import tools.jackson.databind.json.JsonMapper;

/**
 * The read side of the audit trail: what a compliance screen is allowed to ask for, what it gets
 * back, and - the part worth a suite of its own - what it can never be talked into.
 *
 * <p>No database. The specification the service builds cannot be executed here, so the tenancy
 * assertions check the shape of what leaves the service (which company it was built for, what page
 * and sort it asked for) rather than the rows a query would return; the query itself is covered by
 * the Testcontainers suite, which is skipped where Docker is unavailable. That split is the point:
 * "the company predicate was applied" is provable without a database and is the assertion that
 * matters most.
 */
class AuditQueryServiceTest {

    private static final UUID COMPANY = id("company");
    private static final UUID OTHER_COMPANY = id("other-company");
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 8, 21, 9, 0, 0, 0, ZoneOffset.UTC);

    private AuditEventRepository repository;
    private AuditQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuditEventRepository.class);
        // A real mapper: half of what this service does on the way out is parse stored metadata,
        // and a stubbed parser would test nothing.
        service = new AuditQueryService(repository, JsonMapper.builder().build());
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
    }

    private static UUID id(String label) {
        return UUID.nameUUIDFromBytes(label.getBytes(StandardCharsets.UTF_8));
    }

    private static CompanyScope scope(UUID companyId) {
        return new CompanyScope(companyId, "CO", "Company", "America/Lima",
                id("organization"), "ORG", "Organization", Set.of());
    }

    private static AuditEvent event(String metadata) {
        return new AuditEvent(COMPANY, id("actor"), "ana@ebim.test", null, AuditAggregateType.TRIP,
                id("trip"), AuditAction.CREATE, "corr-1", metadata);
    }

    private void returns(AuditEvent... events) {
        Page<AuditEvent> page = new PageImpl<>(List.of(events), PageRequest.of(0, 25), events.length);
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);
    }

    private Pageable capturedPageable() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(repository).findAll(any(Specification.class), pageable.capture());
        return pageable.getValue();
    }

    // --- tenancy ------------------------------------------------------------------------------

    @Nested
    @DisplayName("the company is the caller's, never the request's")
    class Tenancy {

        @Test
        @DisplayName("a filter cannot name a company: there is no field for one")
        void filterHasNoCompanyField() {
            // Asserted structurally rather than behaviourally, because the protection is
            // structural: if somebody adds `companyId` to AuditFilter, that is the moment the
            // tenant becomes something a caller can send, and this test is what says so.
            assertThat(AuditFilter.class.getRecordComponents())
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .doesNotContain("companyId", "company", "tenantId");
        }

        @Test
        @DisplayName("two scopes produce two different queries")
        void scopesDoNotShareASpecification() {
            service.list(scope(COMPANY), AuditFilter.none(), PageQuery.unpaged());
            service.list(scope(OTHER_COMPANY), AuditFilter.none(), PageQuery.unpaged());

            ArgumentCaptor<Specification<AuditEvent>> specifications = ArgumentCaptor.captor();
            org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(2))
                    .findAll(specifications.capture(), any(Pageable.class));
            assertThat(specifications.getAllValues()).hasSize(2);
            assertThat(specifications.getAllValues().get(0)).isNotSameAs(specifications.getAllValues().get(1));
        }
    }

    // --- ordering and paging -------------------------------------------------------------------

    @Nested
    @DisplayName("what a page looks like")
    class Paging {

        @Test
        @DisplayName("newest first by default, with a tie-breaker so paging is stable")
        void defaultsToNewestFirst() {
            service.list(scope(COMPANY), AuditFilter.none(), PageQuery.unpaged());

            Sort sort = capturedPageable().getSort();
            assertThat(sort).containsExactly(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"));
        }

        @Test
        @DisplayName("an explicit sort still ends with the tie-breaker")
        void keepsTheTieBreakerUnderAnExplicitSort() {
            service.list(scope(COMPANY), AuditFilter.none(), new PageQuery(0, 25, "action,asc"));

            assertThat(capturedPageable().getSort())
                    .containsExactly(Sort.Order.asc("action"), Sort.Order.desc("id"));
        }

        @Test
        @DisplayName("a sort over a column the trail is not indexed on is refused, not ignored")
        void refusesAnUnknownSort() {
            assertThatExceptionOfType(InvalidRequestException.class).isThrownBy(() ->
                    service.list(scope(COMPANY), AuditFilter.none(), new PageQuery(0, 25, "metadata,asc")));
        }

        @Test
        @DisplayName("the page size is the server's, however large the request was")
        void clampsThePageSize() {
            service.list(scope(COMPANY), AuditFilter.none(), new PageQuery(0, 10_000, null));

            assertThat(capturedPageable().getPageSize()).isEqualTo(PageQuery.MAX_SIZE);
        }

        @Test
        @DisplayName("the total is the repository's count, not the page's length")
        void reportsTheRealTotal() {
            Page<AuditEvent> page = new PageImpl<>(List.of(event(null)), PageRequest.of(0, 25), 4_231);
            when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            PageResponse<AuditEventView> response =
                    service.list(scope(COMPANY), AuditFilter.none(), PageQuery.unpaged());

            assertThat(response.content()).hasSize(1);
            assertThat(response.totalElements()).isEqualTo(4_231);
        }
    }

    // --- filters --------------------------------------------------------------------------------

    @Nested
    @DisplayName("filters")
    class Filters {

        @Test
        @DisplayName("a window that ends before it starts is refused, not answered with nothing")
        void refusesAnInvertedRange() {
            AuditFilter inverted = new AuditFilter(null, null, null, null, NOW, NOW.minusDays(1), null);

            assertThatExceptionOfType(InvalidRequestException.class)
                    .isThrownBy(() -> service.list(scope(COMPANY), inverted, PageQuery.unpaged()))
                    .withMessageContaining("'to' must not be earlier than 'from'");
        }

        @Test
        @DisplayName("a window with only one bound is accepted")
        void acceptsAnOpenEndedRange() {
            service.list(scope(COMPANY), new AuditFilter(null, null, null, null, NOW, null, null),
                    PageQuery.unpaged());
            service.list(scope(COMPANY), new AuditFilter(null, null, null, null, null, NOW, null),
                    PageQuery.unpaged());

            org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(2))
                    .findAll(any(Specification.class), any(Pageable.class));
        }

        @Test
        @DisplayName("the same instant at both ends is a valid window")
        void acceptsAZeroWidthRange() {
            service.list(scope(COMPANY), new AuditFilter(null, null, null, null, NOW, NOW, null),
                    PageQuery.unpaged());

            org.mockito.Mockito.verify(repository).findAll(any(Specification.class), any(Pageable.class));
        }
    }

    // --- what an entry looks like on the way out --------------------------------------------------

    @Nested
    @DisplayName("rendering an entry")
    class Rendering {

        @Test
        @DisplayName("metadata is handed over as data, not as a string for the client to parse")
        void parsesMetadata() {
            returns(event("{\"shipmentNumber\":\"SH-00000042\",\"reason\":\"customer refused\"}"));

            AuditEventView view = service.list(scope(COMPANY), AuditFilter.none(), PageQuery.unpaged())
                    .content()
                    .getFirst();

            assertThat(view.metadata())
                    .containsEntry("shipmentNumber", "SH-00000042")
                    .containsEntry("reason", "customer refused");
            assertThat(view.action()).isEqualTo(AuditAction.CREATE);
            assertThat(view.actorEmail()).isEqualTo("ana@ebim.test");
            assertThat(view.correlationId()).isEqualTo("corr-1");
        }

        @Test
        @DisplayName("an entry whose metadata was truncated is still served, without it")
        void degradesOnUnreadableMetadata() {
            // What AuditEventRecorder's 4000-character cap produces. The action still happened,
            // and dropping the row would lose the trail to protect its footnote.
            returns(event("{\"shipmentNumber\":\"SH-000000"));

            AuditEventView view = service.list(scope(COMPANY), AuditFilter.none(), PageQuery.unpaged())
                    .content()
                    .getFirst();

            assertThat(view.metadata()).isEmpty();
            assertThat(view.action()).isEqualTo(AuditAction.CREATE);
        }

        @Test
        @DisplayName("an entry with no metadata at all is an empty map, never a null")
        void hasNoNullMetadata() {
            returns(event(null));

            assertThat(service.list(scope(COMPANY), AuditFilter.none(), PageQuery.unpaged())
                    .content()
                    .getFirst()
                    .metadata())
                    .isEmpty();
        }
    }

    // --- what this service does not offer ----------------------------------------------------------

    @Test
    @DisplayName("there is no way to write, correct or delete an entry through this service")
    void isReadOnly() {
        // The trail is evidence. A method here that could change it would make it evidence of
        // nothing, and the database agrees: migration V22 revokes UPDATE and DELETE from tms_app.
        assertThat(AuditQueryService.class.getDeclaredMethods())
                .filteredOn(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .extracting(java.lang.reflect.Method::getName)
                .containsExactly("list");
    }
}
