package com.ebim.tms.shared.imports;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One applied bulk import of a master-data entity (migration V21): what file was uploaded, by
 * whom, and how many rows it produced. One shared table for Locations, Carriers, Vehicle Types
 * and Vehicles, discriminated by {@link #entityType}, rather than four near-identical tables -
 * unlike {@code tms.order_import_batch}, which predates this package and stays as it is.
 *
 * <p>Written in the same transaction as the rows it created, so an import that rolls back leaves
 * no batch row claiming it happened. A dry run never creates one. Immutable after insert - there
 * is no setter and no {@code updated_at}: an audit record that can be edited answers a different
 * question from the one it was written for.
 */
@Entity
@Table(name = "import_batch")
public class ImportBatch {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", updatable = false, nullable = false)
    private ImportEntityType entityType;

    @Column(name = "file_name", updatable = false, nullable = false)
    private String fileName;

    @Column(name = "file_format", updatable = false, nullable = false)
    private String fileFormat;

    @Column(name = "file_sha256", updatable = false, nullable = false)
    private String fileSha256;

    @Column(name = "row_count", updatable = false, nullable = false)
    private int rowCount;

    @Column(name = "created_count", updatable = false, nullable = false)
    private int createdCount;

    @Column(name = "skipped_count", updatable = false, nullable = false)
    private int skippedCount;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    protected ImportBatch() {
        // JPA
    }

    public ImportBatch(UUID companyId, ImportEntityType entityType, String fileName, ImportFormat format,
            String fileSha256, int rowCount, int createdCount, int skippedCount, UUID actorId) {
        this.companyId = companyId;
        this.entityType = entityType;
        this.fileName = fileName;
        this.fileFormat = format.name();
        this.fileSha256 = fileSha256;
        this.rowCount = rowCount;
        this.createdCount = createdCount;
        this.skippedCount = skippedCount;
        this.createdBy = actorId;
    }

    public UUID id() {
        return id;
    }

    public UUID companyId() {
        return companyId;
    }

    public ImportEntityType entityType() {
        return entityType;
    }

    public String fileName() {
        return fileName;
    }

    public String fileFormat() {
        return fileFormat;
    }

    public String fileSha256() {
        return fileSha256;
    }

    public int rowCount() {
        return rowCount;
    }

    public int createdCount() {
        return createdCount;
    }

    public int skippedCount() {
        return skippedCount;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public UUID createdBy() {
        return createdBy;
    }
}
