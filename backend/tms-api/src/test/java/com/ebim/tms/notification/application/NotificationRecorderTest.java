package com.ebim.tms.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ebim.tms.notification.domain.Notification;
import com.ebim.tms.notification.infrastructure.NotificationRepository;
import com.ebim.tms.shared.notification.NotificationRequest;
import com.ebim.tms.shared.notification.NotificationType;
import com.ebim.tms.shared.security.CompanyScope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * The two promises {@code NotificationPublisher} makes to every module that raises an alert: the
 * placeholders are stored as data, and nothing here can fail the transaction it was called from.
 */
class NotificationRecorderTest {

    private static final UUID COMPANY = UUID.randomUUID();
    private static final UUID TRIP_ID = UUID.randomUUID();
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-20T09:35:00Z");

    private static final CompanyScope SCOPE = new CompanyScope(COMPANY, "CO-A", "Company A", "America/Lima",
            UUID.randomUUID(), "ORG", "Organization", Set.of());

    private NotificationRepository repository;
    private NotificationRecorder recorder;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationRepository.class);
        recorder = new NotificationRecorder(repository, JsonMapper.builder().build(), new SimpleMeterRegistry());
    }

    private static NotificationRequest request(Map<String, Object> messageArgs) {
        return new NotificationRequest(NotificationType.TRIP_DELAYED, TRIP_ID, "SH-00000042",
                NotificationType.TRIP_DELAYED.dedupeKey(TRIP_ID), NOW, messageArgs);
    }

    @Test
    @DisplayName("stamps the tenant from the scope it was handed, never from the request")
    void tenantComesFromTheScope() {
        recorder.raise(SCOPE, request(Map.of("shipmentNumber", "SH-00000042")));

        verify(repository).insertIfAbsent(eq(COMPANY), eq("TRIP_DELAYED"), eq("WARNING"), eq("TRIP"),
                eq(TRIP_ID), eq("SH-00000042"), anyString(), eq("TRIP_DELAYED:" + TRIP_ID), eq(NOW));
    }

    @Test
    @DisplayName("writes the placeholders as JSON, so history stays translatable")
    void argumentsAreStoredAsData() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("shipmentNumber", "SH-00000042");
        args.put("minutes", 95L);

        recorder.raise(SCOPE, request(args));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(repository).insertIfAbsent(any(), anyString(), anyString(), anyString(), any(), anyString(),
                json.capture(), anyString(), any());
        // Asserted as data rather than as a string, which is the claim this test is making. The
        // mapper orders map entries by key, so the bytes do not follow the caller's insertion
        // order - and they should not have to: what the read side needs back is the placeholders,
        // and pinning a byte sequence here would make an unrelated mapper setting a test failure.
        @SuppressWarnings("unchecked")
        Map<String, Object> stored = JsonMapper.builder().build().readValue(json.getValue(), Map.class);
        assertThat(stored)
                .containsExactlyInAnyOrderEntriesOf(Map.of("shipmentNumber", "SH-00000042", "minutes", 95));
    }

    @Test
    @DisplayName("stores nothing rather than an empty object when there are no placeholders")
    void noArgumentsMeansNull() {
        recorder.raise(SCOPE, request(Map.of()));

        verify(repository).insertIfAbsent(any(), anyString(), anyString(), anyString(), any(), anyString(),
                isNull(), anyString(), any());
    }

    /**
     * The alert is a by-product. An argument map somebody put a value in that Jackson has no
     * serializer for must cost the sentence its detail, never the dispatch that raised it.
     *
     * <p>Asserts the guarantee and not the mechanism: what must hold is that the raise still
     * reaches the table and that nothing propagates to the caller. Whether the arguments end up
     * dropped or written depends on the mapper's configuration, and pinning that here would make
     * this test a statement about Jackson rather than about the recorder.
     */
    @Test
    @DisplayName("still raises the alert when its placeholders cannot be serialised")
    void unserialisableArgumentsDoNotFailTheCaller() {
        recorder.raise(SCOPE, request(Map.of("broken", new Object())));

        verify(repository).insertIfAbsent(eq(COMPANY), eq("TRIP_DELAYED"), anyString(), anyString(), eq(TRIP_ID),
                anyString(), any(), eq("TRIP_DELAYED:" + TRIP_ID), eq(NOW));
    }

    @Test
    @DisplayName("resolves the alert with the given key, and leaves an already-resolved one alone")
    void resolveGoesThroughTheEntity() {
        Notification notification = mock(Notification.class);
        when(repository.findByCompanyIdAndDedupeKey(COMPANY, "EXCEPTION_OPENED:x"))
                .thenReturn(Optional.of(notification));

        recorder.resolve(SCOPE, "EXCEPTION_OPENED:x", NOW);

        verify(notification).resolve(NOW);
        verify(repository).save(notification);
    }

    /**
     * A key that matches nothing is the ordinary case, not an error: the condition may have closed
     * on an alert raised by an older build, or by a type that never raises one at all.
     */
    @Test
    @DisplayName("does nothing when the key matches no alert")
    void resolvingAnUnknownKeyIsQuiet() {
        when(repository.findByCompanyIdAndDedupeKey(COMPANY, "EXCEPTION_OPENED:x")).thenReturn(Optional.empty());

        recorder.resolve(SCOPE, "EXCEPTION_OPENED:x", NOW);

        verify(repository, never()).save(any(Notification.class));
    }
}
