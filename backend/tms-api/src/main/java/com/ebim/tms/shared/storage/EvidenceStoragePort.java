package com.ebim.tms.shared.storage;

import java.io.InputStream;
import java.util.UUID;

/**
 * Where the bytes of a proof-of-delivery artefact live (migration V28).
 *
 * <p><b>Why a port and not a Supabase Storage client.</b> TMS has no mature file infrastructure
 * today: Supabase Storage is deferred by decision ({@code CLAUDE.md}), and the alternative -
 * base64 in a column, or a public bucket - is the failure this interface exists to prevent. So the
 * <em>shape</em> of the answer is fixed here now, an implementation that stores to a private
 * filesystem volume is provided for deployments that want the feature today, and the
 * {@link DisabledEvidenceStorage} default refuses clearly rather than inventing somewhere to put a
 * customer's signed delivery note. Moving to Supabase Storage later is a second implementation of
 * this interface and no change to any caller, because {@code storageKey} is opaque.
 *
 * <p><b>The three rules every implementation keeps.</b>
 *
 * <ol>
 *   <li><b>The key is ours.</b> {@link #store} generates it; no caller supplies one, and nothing
 *     derived from a file name a client sent ever reaches it. That is what makes path traversal
 *     not a class of bug here rather than a filter that has to be right.</li>
 *   <li><b>The store is private.</b> There is no method that returns a URL, signed or otherwise,
 *     because there is no address a customer's delivery note should be reachable at. Bytes come
 *     back through {@link #open}, into a request the API has already authenticated, scoped to a
 *     company and checked a permission for.</li>
 *   <li><b>Company first.</b> Every operation takes the company the object belongs to and an
 *     implementation is expected to keep tenants apart in its own layout, so that a key leaked from
 *     one tenant cannot be read by another. The database's own scoping is the first line; this is
 *     the second.</li>
 * </ol>
 *
 * <p>There is deliberately no {@code delete}. V1 never removes evidence - see migration V28's
 * "Deliberately NOT here" - and an interface with a method nobody may call is an invitation.
 */
public interface EvidenceStoragePort {

    /**
     * Whether this deployment can store evidence at all. A screen asks before offering an upload
     * button, so a company running without a configured store is told up front rather than after
     * choosing a file.
     */
    boolean isEnabled();

    /**
     * Writes {@code content} and returns the key it can be read back with, plus the size and
     * checksum of what was actually stored.
     *
     * <p>The size and checksum are computed <em>by the store, from the bytes it wrote</em>, and
     * never taken from the caller: they are what a dispute is settled with, and a number the
     * uploader supplied would prove nothing.
     *
     * @param companyId the tenant the object belongs to
     * @param contentType the media type, already validated by the caller against what it accepts
     * @param originalFilename what the operator's device called it, used only to pick a file
     *     extension; may be null, and is never trusted as a path
     * @param content the bytes; consumed and closed by the implementation
     * @param maxBytes the ceiling the caller enforces. An implementation stops reading past it and
     *     throws {@link EvidenceRejectedException}, so an oversized upload cannot fill a volume
     *     before anybody checks its length
     * @throws EvidenceStorageUnavailableException if no store is configured
     * @throws EvidenceRejectedException if {@code content} exceeds {@code maxBytes} or is empty
     * @throws EvidenceStorageException if the write fails
     */
    StoredObject store(UUID companyId, String contentType, String originalFilename, InputStream content,
            long maxBytes);

    /**
     * Opens a stored object for reading. The caller must have established that this company owns
     * this key - the row in {@code tms.delivery_evidence} is that proof - before asking.
     *
     * @throws EvidenceStorageUnavailableException if no store is configured
     * @throws EvidenceStorageException if the object is missing or cannot be read
     */
    StoredObjectContent open(UUID companyId, String storageKey);
}
