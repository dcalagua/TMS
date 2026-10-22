package com.ebim.tms.settlement.application;

import java.io.InputStream;

/**
 * A file on its way into, or out of, a freight claim's evidence (V71). Multipart stops at the
 * controller, so the use case stays callable from anything ({@code LayeringTest}).
 */
public record ClaimEvidenceFile(String contentType, String fileName, long sizeBytes, InputStream content) {
}
