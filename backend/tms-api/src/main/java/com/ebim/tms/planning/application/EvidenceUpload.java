package com.ebim.tms.planning.application;

import java.io.InputStream;

/**
 * One uploaded file, reduced to what the use case actually needs.
 *
 * <p>Deliberately not a {@code MultipartFile}: an application service must stay callable from a job
 * or an integration and not only from a controller ({@code LayeringTest}), and the same rule is why
 * {@code OrderImportService} takes bytes rather than a part. The controller is the only place that
 * knows about multipart.
 *
 * <p>A stream and not a {@code byte[]}, unlike the import: an import file is parsed in memory
 * anyway, while evidence is megabytes that go straight through to a store. The service closes it.
 *
 * @param contentType the media type the client declared, validated before a byte is read
 * @param fileName the operator's own name for the file, already reduced to a bare name; may be null
 */
public record EvidenceUpload(String contentType, String fileName, InputStream content) {
}
