package com.ebim.tms.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * One applied bulk order import (migration V17): what file was uploaded, by whom, under which
 * external source, and how many orders it produced.
 *
 * <p>Written in the same transaction as the orders it created, so an import that rolls back
 * leaves no batch row claiming it happened. A dry run never creates one.
 *
 * <p>Immutable after insert - there is no setter and no {@code updated_at}. An audit record that
 * can be edited answers a different question from the one it was written for.
 */
@Entity
@Table(name = "order_import_batch")
public class OrderImportBatch {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "company_id", updatable = false, nullable = false)
    private UUID companyId;

    @Column(name = "external_source", updatable = false, nullable = false)
    private String externalSource;

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

    protected OrderImportBatch() {
        // JPA
    }

    public OrderImportBatch(UUID companyId, String externalSource, String fileName, String fileFormat,
            String fileSha256, int rowCount, int createdCount, int skippedCount, UUID actorId) {
        this.companyId = companyId;
        this.externalSource = externalSource;
        this.fileName = fileName;
        this.fileFormat = fileFormat;
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

    public String externalSource() {
        return externalSource;
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
