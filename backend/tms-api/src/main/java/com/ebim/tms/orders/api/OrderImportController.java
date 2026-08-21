package com.ebim.tms.orders.api;

import com.ebim.tms.orders.application.imports.OrderImportFormat;
import com.ebim.tms.orders.application.imports.OrderImportReport;
import com.ebim.tms.orders.application.imports.OrderImportService;
import com.ebim.tms.orders.application.imports.OrderImportTemplate;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The bulk order import: download a template, preview a file, apply it.
 *
 * <p>Three endpoints and a deliberate asymmetry between the last two. {@code preview} needs only
 * {@code orders.order:read} - it writes nothing and exists precisely so an operator can inspect a
 * file before committing to it - while {@code apply} needs {@code orders.order:manage}, the same
 * permission creating one order by hand needs. Creating three hundred at once must not be an
 * easier authorisation than creating one.
 *
 * <p>Both take the file as {@code multipart/form-data} and an {@code externalSource} form field
 * naming the system it came from; see {@link OrderImportService} for why that field is what makes
 * the import idempotent.
 */
@RestController
@RequestMapping("${tms.api.base-path}/orders/import")
@Tag(name = "Order import", description = "Bulk transport order import: template, preview and apply")
public class OrderImportController {

    private final OrderImportService orderImportService;
    private final OrderImportTemplate orderImportTemplate;
    private final Clock clock;

    public OrderImportController(
            OrderImportService orderImportService, OrderImportTemplate orderImportTemplate, Clock clock) {
        this.orderImportService = orderImportService;
        this.orderImportTemplate = orderImportTemplate;
        this.clock = clock;
    }

    /**
     * The template, generated from the column definition rather than served from disk.
     *
     * <p>Company-scoped and permission-checked like every other endpoint, even though the bytes
     * contain no tenant data: an unauthenticated download would be a second, quieter API surface,
     * and "it only returns a blank form" is exactly the reasoning that leaves one unprotected.
     */
    @GetMapping("/template")
    @PreAuthorize("hasAuthority('orders.order:read')")
    @Operation(summary = "Download the order import template as .xlsx or .csv")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ResponseEntity<Resource> template(
            CompanyScope scope, @RequestParam(name = "format", defaultValue = "XLSX") OrderImportFormat format) {
        // Tomorrow, so the example rows carry a date that is still plausible when the operator
        // fills them in - and, more usefully, one that is never accidentally in the past.
        LocalDate exampleDate = LocalDate.now(clock).plusDays(1);
        byte[] content = orderImportTemplate.build(format, exampleDate);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(orderImportTemplate.fileName(format))
                        .build()
                        .toString())
                .contentType(MediaType.parseMediaType(format.contentType()))
                .contentLength(content.length)
                // A generated document with example data in it must never be cached by a proxy.
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new ByteArrayResource(content));
    }

    @PostMapping(path = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('orders.order:read')")
    @Operation(summary = "Validate an import file and report what applying it would do, writing nothing")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public OrderImportReport preview(CompanyScope scope,
            @RequestParam("externalSource") String externalSource, @RequestParam("file") MultipartFile file) {
        return orderImportService.dryRun(scope, externalSource, bytesOf(file), fileNameOf(file));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('orders.order:manage')")
    @Operation(summary = "Apply an import file in one transaction, or write nothing if any row has an issue")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public OrderImportReport apply(CompanyScope scope,
            @RequestParam("externalSource") String externalSource, @RequestParam("file") MultipartFile file) {
        return orderImportService.apply(scope, externalSource, bytesOf(file), fileNameOf(file));
    }

    private static byte[] bytesOf(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("A file is required.");
        }
        try {
            return file.getBytes();
        } catch (IOException unreadable) {
            throw new InvalidRequestException("The uploaded file could not be read. Try uploading it again.");
        }
    }

    /**
     * The original file name, reduced to its last path segment. Browsers send a bare name, but a
     * hand-made request may send a path, and this value is persisted on the audit row and echoed
     * in the report - neither of which should ever carry a directory from the caller's machine.
     */
    private static String fileNameOf(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return "upload";
        }
        String trimmed = name.trim();
        int lastSeparator = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        String bare = lastSeparator < 0 ? trimmed : trimmed.substring(lastSeparator + 1);
        return bare.isBlank() ? "upload" : bare.substring(0, Math.min(bare.length(), 255));
    }
}
