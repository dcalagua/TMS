package com.ebim.tms.planning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** The per-order summary of a dispatch document (V53). Written once with its document, never updated. */
@Entity
@Table(name = "external_dispatch_order")
public class ExternalDispatchOrder {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "external_dispatch_id", nullable = false, updatable = false)
    private UUID externalDispatchId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "order_id", updatable = false)
    private UUID orderId;

    @Column(name = "external_source", nullable = false, updatable = false)
    private String externalSource;

    @Column(name = "external_reference", nullable = false, updatable = false)
    private String externalReference;

    @Column(name = "warehouse_order_number", updatable = false)
    private String warehouseOrderNumber;

    @Column(name = "handling_units", updatable = false)
    private Integer handlingUnits;

    @Column(name = "weight_kg", updatable = false)
    private BigDecimal weightKg;

    @Column(name = "volume_m3", updatable = false)
    private BigDecimal volumeM3;

    @Column(name = "status", updatable = false)
    private String status;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_result", nullable = false, updatable = false)
    private ExternalDispatchOrderMatch matchResult;

    protected ExternalDispatchOrder() {
    }

    public ExternalDispatchOrder(UUID externalDispatchId, UUID companyId, UUID orderId, String externalSource,
            String externalReference, String warehouseOrderNumber, Integer handlingUnits, BigDecimal weightKg,
            BigDecimal volumeM3, String status, ExternalDispatchOrderMatch matchResult) {
        this.externalDispatchId = externalDispatchId;
        this.companyId = companyId;
        this.orderId = orderId;
        this.externalSource = externalSource;
        this.externalReference = externalReference;
        this.warehouseOrderNumber = warehouseOrderNumber;
        this.handlingUnits = handlingUnits;
        this.weightKg = weightKg;
        this.volumeM3 = volumeM3;
        this.status = status;
        this.matchResult = matchResult;
    }

    public UUID id() {
        return id;
    }

    public UUID externalDispatchId() {
        return externalDispatchId;
    }

    public UUID companyId() {
        return companyId;
    }

    public UUID orderId() {
        return orderId;
    }

    public String externalSource() {
        return externalSource;
    }

    public String externalReference() {
        return externalReference;
    }

    public String warehouseOrderNumber() {
        return warehouseOrderNumber;
    }

    public Integer handlingUnits() {
        return handlingUnits;
    }

    public BigDecimal weightKg() {
        return weightKg;
    }

    public BigDecimal volumeM3() {
        return volumeM3;
    }

    public String status() {
        return status;
    }

    public ExternalDispatchOrderMatch matchResult() {
        return matchResult;
    }
}
