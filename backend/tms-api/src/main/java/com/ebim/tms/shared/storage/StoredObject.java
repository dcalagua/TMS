package com.ebim.tms.shared.storage;

/**
 * What {@link EvidenceStoragePort#store} wrote: where to find it again, how big it turned out, and
 * what it hashes to.
 *
 * <p>All three are the <em>store's</em> account of the bytes rather than the uploader's claim about
 * them, which is the point: a checksum supplied by whoever uploaded the file proves nothing about
 * the file that was stored.
 *
 * @param storageKey opaque, server-generated, and the only handle a caller ever holds
 * @param sizeBytes bytes actually written
 * @param checksumSha256 lower-case hex SHA-256 of those bytes
 */
public record StoredObject(String storageKey, long sizeBytes, String checksumSha256) {
}
