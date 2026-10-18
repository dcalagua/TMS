package com.ebim.tms.settlement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * A freight obligation between the service and the invoice (migration V65, ADR-023).
 *
 * <p>The running totals are a convenience the list and the aging report read without summing; the
 * movements in {@link FreightAccrualEntry} are the truth. Every method here that moves a total
 * returns the signed amount of the one movement the caller must book in the same transaction, so a
 * total cannot move without its entry: {@code SUM(entry.amount) == balance()}.
 */
@Entity
@Table(name = "freight_accrual")
public class FreightAccrual {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "trip_id", updatable = false, nullable = false)
    private UUID tripId;

    @Column(name = "carrier_id", updatable = false)
    private UUID carrierId;

    @Column(name = "currency", updatable = false, nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AccrualStatus status = AccrualStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(name = "basis", nullable = false)
    private AccrualBasis basis;

    @Column(name = "service_date", updatable = false, nullable = false)
    private LocalDate serviceDate;

    @Column(name = "accrued_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal accruedAmount = BigDecimal.ZERO;

    @Column(name = "consumed_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal consumedAmount = BigDecimal.ZERO;

    @Column(name = "reversed_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal reversedAmount = BigDecimal.ZERO;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected FreightAccrual() {
        // JPA
    }

    /**
     * Born with its first figure. Refuses zero or less: there is no obligation to record, and an
     * accrual of 0.00 standing for "we could not price it" is exactly the invented figure ADR-023
     * forbids.
     */
    public FreightAccrual(UUID companyId, UUID tripId, UUID carrierId, String currency, AccrualBasis basis,
            LocalDate serviceDate, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("an accrual is born with a positive amount");
        }
        this.companyId = companyId;
        this.tripId = tripId;
        this.carrierId = carrierId;
        this.currency = currency;
        this.basis = basis;
        this.serviceDate = serviceDate;
        this.accruedAmount = money(amount);
    }

    /** What is still owed: accrued - consumed - reversed. Negative when invoiced above the accrual. */
    public BigDecimal balance() {
        return accruedAmount.subtract(consumedAmount).subtract(reversedAmount);
    }

    /**
     * Moves the accrued figure to the closed actual cost. Only while {@link AccrualStatus#OPEN}:
     * once an invoice drew on it, the invoice is the better figure and the variance is visible.
     *
     * @return the signed ADJUST amount to book, or zero when nothing changes (then book nothing)
     */
    public BigDecimal adjustTo(BigDecimal actual) {
        if (status != AccrualStatus.OPEN) {
            throw new IllegalStateException("only an open accrual is adjusted to the actual cost");
        }
        BigDecimal delta = money(actual).subtract(balance());
        basis = AccrualBasis.ACTUAL;
        if (delta.signum() != 0) {
            accruedAmount = accruedAmount.add(delta);
        }
        return delta;
    }

    /**
     * An approved invoice draws {@code invoiced} (positive) on this accrual. Consuming beyond the
     * balance is allowed: the invoice is what is owed, and the difference is the variance a person
     * reads. Never on a reversed accrual - nothing was left to draw on.
     *
     * @return the signed CONSUME amount to book (negative)
     */
    public BigDecimal consume(BigDecimal invoiced) {
        if (invoiced == null || invoiced.signum() <= 0) {
            throw new IllegalArgumentException("an invoice consumes a positive amount");
        }
        if (status == AccrualStatus.REVERSED) {
            throw new IllegalStateException("a reversed accrual is not consumed");
        }
        consumedAmount = consumedAmount.add(money(invoiced));
        status = balance().signum() <= 0 ? AccrualStatus.SETTLED : AccrualStatus.PARTIALLY_SETTLED;
        return money(invoiced).negate();
    }

    /**
     * A person releases the balance: an {@code OPEN} accrual is reversed (service not billable), a
     * {@code PARTIALLY_SETTLED} one is closed (the last invoice came in below the accrual).
     *
     * @return the signed REVERSE amount to book (negative)
     */
    public BigDecimal reverseRemainder() {
        if (status != AccrualStatus.OPEN && status != AccrualStatus.PARTIALLY_SETTLED) {
            throw new IllegalStateException("only an open or partially settled accrual has a remainder to release");
        }
        BigDecimal remainder = balance();
        if (remainder.signum() <= 0) {
            throw new IllegalStateException("there is no remainder to release");
        }
        reversedAmount = reversedAmount.add(remainder);
        status = status == AccrualStatus.OPEN ? AccrualStatus.REVERSED : AccrualStatus.SETTLED;
        return remainder.negate();
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public UUID tripId() {
        return tripId;
    }

    public UUID carrierId() {
        return carrierId;
    }

    public String currency() {
        return currency;
    }

    public AccrualStatus status() {
        return status;
    }

    public AccrualBasis basis() {
        return basis;
    }

    public LocalDate serviceDate() {
        return serviceDate;
    }

    public BigDecimal accruedAmount() {
        return accruedAmount;
    }

    public BigDecimal consumedAmount() {
        return consumedAmount;
    }

    public BigDecimal reversedAmount() {
        return reversedAmount;
    }

    public long version() {
        return version;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}
