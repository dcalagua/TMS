package com.ebim.tms.shared.imports;

import com.ebim.tms.shared.api.InvalidRequestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Small helpers every entity import's service needs, factored out so none of them repeats them. */
public final class ImportSupport {

    private ImportSupport() {}

    /** Validates size and detects format, or throws {@link InvalidRequestException}. */
    public static ImportFormat requireSupportedFile(byte[] content, String fileName, ImportLimits limits) {
        if (content == null || content.length == 0) {
            throw new InvalidRequestException("The uploaded file is empty.");
        }
        if (content.length > limits.maxFileBytes()) {
            throw new InvalidRequestException(
                    "The file is larger than " + limits.maxFileBytes() / (1024 * 1024) + " MB.");
        }
        ImportFormat format = ImportFormat.detect(content, fileName);
        if (format == null) {
            throw new InvalidRequestException(
                    "Only .xlsx and .csv files can be imported. Download the template to see the expected shape.");
        }
        return format;
    }

    /** SHA-256 of the uploaded bytes, for the audit batch row's traceability column. */
    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }

    /** Caps a full issue list to what a report will carry, flagging whether anything was cut. */
    public static List<ImportIssue> truncate(List<ImportIssue> issues, int maxReportedIssues) {
        return issues.size() > maxReportedIssues ? issues.subList(0, maxReportedIssues) : issues;
    }
}
