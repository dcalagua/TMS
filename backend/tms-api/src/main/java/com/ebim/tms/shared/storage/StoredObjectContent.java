package com.ebim.tms.shared.storage;

import java.io.InputStream;

/**
 * An open handle on stored bytes: how many there are, and a stream over them.
 *
 * <p>A stream and not a {@code byte[]} deliberately. Evidence is measured in megabytes, several
 * requests can be reading at once, and buffering each one whole would make a page of thumbnails a
 * memory event. The caller closes the stream - the download endpoint hands it to the servlet
 * container inside a try-with-resources.
 *
 * @param sizeBytes what the store says the object is, for the {@code Content-Length} header
 * @param stream the bytes; the caller owns it and must close it
 */
public record StoredObjectContent(long sizeBytes, InputStream stream) {
}
