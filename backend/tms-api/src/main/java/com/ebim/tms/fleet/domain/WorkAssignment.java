package com.ebim.tms.fleet.domain;

import com.ebim.tms.shared.api.ConflictException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One driver-and-vehicle pairing's work for one day (migration V47, closing debt D5).
 *
 * <p><b>It organises shipments that already exist.</b> Not a second trip: it carries no load, visits
 * no stops, and grants no authority. A shipment in somebody's day is still refused at the gate by
 * every guard that refuses it now - which is the one property this aggregate must never erode.
 *
 * <p>One operational <em>date</em> rather than a range, deliberately: V42 stores driver shifts as a
 * weekly rule with no overnight support, and an assignment spanning two dates could not be validated
 * against it without inventing semantics V42 refused.
 */
@Entity
@Table(name = "work_assignment")
public class WorkAssignment {

    public enum Status { PLANNED, CONFIRMED, CANCELLED }

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "operational_date", updatable = false, nullable = false)
    private LocalDate operationalDate;

    @Column(name = "vehicle_id", nullable = false)
    private UUID vehicleId;

    /** Nullable: a truck is committed before the person is confirmed, which is how a yard plans. */
    @Column(name = "driver_id")
    private UUID driverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.PLANNED;

    @Column(name = "notes")
    private String notes;

    @OneToMany(mappedBy = "assignment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sequence ASC")
    private List<WorkAssignmentTrip> trips = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    /** Two dispatchers rearranging one day at the same second: the loser gets a stale write. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected WorkAssignment() {
    }

    public WorkAssignment(UUID companyId, LocalDate operationalDate, UUID vehicleId, UUID driverId,
            String notes, UUID actorId) {
        this.companyId = companyId;
        this.operationalDate = operationalDate;
        this.vehicleId = vehicleId;
        this.driverId = driverId;
        this.notes = notes;
        this.createdBy = actorId;
        this.updatedBy = actorId;
    }

    /**
     * Replaces the whole sequence in order.
     *
     * <p>Whole, always - never one element. Moving a shipment breaks the leg into it and the leg out
     * of it, so every operation rebuilds the list and the caller revalidates all of it. A partial
     * update is how a day ends up feasible everywhere except the join nobody re-checked.
     */
    public void replaceTrips(List<WorkAssignmentTrip> replacements, UUID actorId) {
        requireOpen();
        trips.clear();
        int sequence = 1;
        for (WorkAssignmentTrip trip : replacements) {
            trip.attachTo(this, sequence++);
            trips.add(trip);
        }
        this.updatedBy = actorId;
    }

    public void assignVehicle(UUID vehicleId, UUID actorId) {
        requireOpen();
        this.vehicleId = vehicleId;
        this.updatedBy = actorId;
    }

    public void assignDriver(UUID driverId, UUID actorId) {
        requireOpen();
        this.driverId = driverId;
        this.updatedBy = actorId;
    }

    /**
     * Confirms the day.
     *
     * <p>Confirmation is a statement that the sequence was checked, not a permission: the shipments
     * in it are dispatched one at a time by {@code TripExecutionService}, which asks its own
     * questions and is unaffected by anything here.
     */
    public void confirm(UUID actorId) {
        requireOpen();
        this.status = Status.CONFIRMED;
        this.updatedBy = actorId;
    }

    public void cancel(UUID actorId) {
        if (status == Status.CANCELLED) {
            return;
        }
        this.status = Status.CANCELLED;
        this.updatedBy = actorId;
    }

    private void requireOpen() {
        if (status == Status.CANCELLED) {
            throw new ConflictException("This work assignment has been cancelled and can no longer be changed.");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public LocalDate operationalDate() {
        return operationalDate;
    }

    public UUID vehicleId() {
        return vehicleId;
    }

    public UUID driverId() {
        return driverId;
    }

    public Status status() {
        return status;
    }

    public String notes() {
        return notes;
    }

    public List<WorkAssignmentTrip> trips() {
        return List.copyOf(trips);
    }

    public long version() {
        return version;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }
}
