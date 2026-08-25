package com.ebim.tms.planning.application;

import java.io.InputStream;

/**
 * An evidence file on its way to a caller: what it is, what to call it, how big it is, and the
 * bytes.
 *
 * <p>Deliberately not a {@code byte[]} - see {@code StoredObjectContent} for why - and deliberately
 * not a URL: the only way to these bytes is a request the API has authenticated, scoped to a
 * company and checked a permission for. The controller closes {@link #stream()}.
 *
 * @param fileName what the browser should save it as - the uploader's own name where there is one,
 *     a generated one otherwise. Already reduced to a bare file name when it was stored
 */
public record DeliveryEvidenceDownload(String contentType, String fileName, long sizeBytes, InputStream stream) {
}
