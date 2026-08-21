package com.ebim.tms.planning.infrastructure;

import com.ebim.tms.planning.domain.TransportEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The operational timeline (migration V27).
 *
 * <p>Every read is company-scoped in its own signature rather than relying on the trip id having
 * been scoped by whoever resolved it: the same belt-and-braces rule every planning repository
 * follows, and what makes a leak require two independent mistakes instead of one.
 *
 * <p>Deliberately exposes no update or delete. The table withholds both verbs from {@code tms_app}
 * (V27), so a method that offered them would fail at the database anyway - and JPA's inherited
 * {@code delete} is unreachable here because nothing outside {@code TransportEventRecorder} holds
 * a managed instance to delete.
 */
public interface TransportEventRepository extends JpaRepository<TransportEvent, UUID> {

    /**
     * One shipment's day in order.
     *
     * <p>Ordered by {@code eventTime} and then by {@code recordedAt}: the timeline a dispatcher
     * reads is the one the fleet lived, and the tie-break matters because two facts backdated to
     * the same minute must still appear in the order they were reported rather than in whatever
     * order the database returns them.
     */
    List<TransportEvent> findByCompanyIdAndTripIdOrderByEventTimeAscRecordedAtAsc(UUID companyId, UUID tripId);
}
