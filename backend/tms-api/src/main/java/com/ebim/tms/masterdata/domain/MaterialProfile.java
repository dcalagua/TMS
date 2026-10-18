package com.ebim.tms.masterdata.domain;

import com.ebim.tms.shared.geometry.PhysicalBlock;
import com.ebim.tms.shared.geometry.StackMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * The warehouse's physical profile of one material, as TMS last received it (migration V59,
 * ADR-022 section 1).
 *
 * <p>A dated copy, never an editable master: the warehouse owns it (D-02), the only writer is the
 * integration API, and TMS never corrects it. Its identity is {@code (company, materialCode)},
 * matched byte for byte against {@code transport_order_line.material_code}. There is no delete -
 * a profile the warehouse stops sending stays as the last known copy.
 */
@Entity
@Table(name = "material_profile")
public class MaterialProfile {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "material_code", updatable = false, nullable = false)
    private String materialCode;

    @Column(name = "description")
    private String description;

    @Column(name = "source_system", nullable = false)
    private String sourceSystem;

    @Column(name = "source_updated_at", nullable = false)
    private OffsetDateTime sourceUpdatedAt;

    @Column(name = "length_m", precision = 8, scale = 4)
    private BigDecimal lengthM;

    @Column(name = "width_m", precision = 8, scale = 4)
    private BigDecimal widthM;

    @Column(name = "height_m", precision = 8, scale = 4)
    private BigDecimal heightM;

    @Column(name = "unit_weight_kg", precision = 10, scale = 3)
    private BigDecimal unitWeightKg;

    @Column(name = "unit_volume_m3", precision = 10, scale = 4)
    private BigDecimal unitVolumeM3;

    @Enumerated(EnumType.STRING)
    @Column(name = "stack_mode")
    private StackMode stackMode;

    @Column(name = "stack_max_quantity")
    private Integer stackMaxQuantity;

    @Column(name = "nest_increment_m", precision = 8, scale = 4)
    private BigDecimal nestIncrementM;

    @Column(name = "max_top_load_kg", precision = 12, scale = 3)
    private BigDecimal maxTopLoadKg;

    @Column(name = "orientation_locked", nullable = false)
    private boolean orientationLocked;

    @Column(name = "updated_by_client")
    private UUID updatedByClient;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected MaterialProfile() {
        // JPA
    }

    public MaterialProfile(UUID companyId, String materialCode, Content content, OffsetDateTime sourceUpdatedAt,
            UUID integrationClientId) {
        this.companyId = companyId;
        this.materialCode = materialCode;
        apply(content, sourceUpdatedAt, integrationClientId);
    }

    /**
     * Everything the warehouse states about the material, compared as a whole to tell a real change
     * from a redelivery. Numbers compare by value, so {@code 0.105} and {@code 0.1050} are the same.
     */
    public record Content(
            String description,
            String sourceSystem,
            BigDecimal unitWeightKg,
            BigDecimal unitVolumeM3,
            PhysicalBlock block) {

        public boolean sameAs(Content other) {
            return Objects.equals(description, other.description)
                    && Objects.equals(sourceSystem, other.sourceSystem)
                    && sameNumber(unitWeightKg, other.unitWeightKg)
                    && sameNumber(unitVolumeM3, other.unitVolumeM3)
                    && sameNumber(block.lengthM(), other.block.lengthM())
                    && sameNumber(block.widthM(), other.block.widthM())
                    && sameNumber(block.heightM(), other.block.heightM())
                    && block.stackMode() == other.block.stackMode()
                    && Objects.equals(block.stackMaxQuantity(), other.block.stackMaxQuantity())
                    && sameNumber(block.nestIncrementM(), other.block.nestIncrementM())
                    && sameNumber(block.maxTopLoadKg(), other.block.maxTopLoadKg())
                    && block.orientationLocked() == other.block.orientationLocked();
        }

        private static boolean sameNumber(BigDecimal left, BigDecimal right) {
            return left == null ? right == null : right != null && left.compareTo(right) == 0;
        }
    }

    /** Replaces the stored copy. The caller has already decided the write is not stale. */
    public void apply(Content content, OffsetDateTime sourceUpdatedAt, UUID integrationClientId) {
        PhysicalBlock block = content.block();
        this.description = content.description();
        this.sourceSystem = content.sourceSystem();
        this.unitWeightKg = content.unitWeightKg();
        this.unitVolumeM3 = content.unitVolumeM3();
        this.lengthM = block.lengthM();
        this.widthM = block.widthM();
        this.heightM = block.heightM();
        this.stackMode = block.stackMode();
        this.stackMaxQuantity = block.stackMaxQuantity();
        this.nestIncrementM = block.nestIncrementM();
        this.maxTopLoadKg = block.maxTopLoadKg();
        this.orientationLocked = block.orientationLocked();
        this.sourceUpdatedAt = sourceUpdatedAt;
        this.updatedByClient = integrationClientId;
    }

    /** Whether a write stamped {@code candidate} is older than the copy already held. */
    public boolean isNewerThan(OffsetDateTime candidate) {
        return sourceUpdatedAt.isAfter(candidate);
    }

    public Content content() {
        return new Content(description, sourceSystem, unitWeightKg, unitVolumeM3, block());
    }

    public PhysicalBlock block() {
        return new PhysicalBlock(lengthM, widthM, heightM, stackMode, stackMaxQuantity, nestIncrementM, maxTopLoadKg,
                orientationLocked);
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public String materialCode() {
        return materialCode;
    }

    public String description() {
        return description;
    }

    public String sourceSystem() {
        return sourceSystem;
    }

    public OffsetDateTime sourceUpdatedAt() {
        return sourceUpdatedAt;
    }

    public BigDecimal unitWeightKg() {
        return unitWeightKg;
    }

    public BigDecimal unitVolumeM3() {
        return unitVolumeM3;
    }

    public UUID updatedByClient() {
        return updatedByClient;
    }

    public long version() {
        return version;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }
}
