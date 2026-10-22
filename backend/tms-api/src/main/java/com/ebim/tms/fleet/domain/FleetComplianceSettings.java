package com.ebim.tms.fleet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.UpdateTimestamp;

/** How many days ahead a fleet document counts as about to expire, per company (migration V72). */
@Entity
@Table(name = "fleet_compliance_settings")
public class FleetComplianceSettings {

    @Id
    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "expiry_alert_days", nullable = false)
    private int expiryAlertDays;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected FleetComplianceSettings() {
        // JPA
    }

    public FleetComplianceSettings(UUID companyId, int expiryAlertDays, UUID actorId) {
        this.companyId = companyId;
        this.expiryAlertDays = expiryAlertDays;
        this.updatedBy = actorId;
    }

    /** @return whether anything changed */
    public boolean change(int expiryAlertDays, UUID actorId) {
        if (this.expiryAlertDays == expiryAlertDays) {
            return false;
        }
        this.expiryAlertDays = expiryAlertDays;
        this.updatedBy = actorId;
        return true;
    }

    public UUID companyId() {
        return companyId;
    }

    public int expiryAlertDays() {
        return expiryAlertDays;
    }
}
