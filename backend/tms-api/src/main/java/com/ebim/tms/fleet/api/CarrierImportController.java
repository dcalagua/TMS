package com.ebim.tms.fleet.api;

import com.ebim.tms.fleet.application.imports.CarrierImportPreview;
import com.ebim.tms.fleet.application.imports.CarrierImportService;
import com.ebim.tms.fleet.application.imports.CarrierImportTemplate;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.imports.ImportFormat;
import com.ebim.tms.shared.imports.ImportLimits;
import com.ebim.tms.shared.imports.ImportReport;
import com.ebim.tms.shared.imports.ImportSupport;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
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
 * The bulk Carrier import: download a template, preview a file, apply it. Same deliberate
 * asymmetry as {@code LocationImportController}: {@code preview} needs only
 * {@code fleet.carrier:read}, {@code apply} needs {@code fleet.carrier:manage}.
 */
@RestController
@RequestMapping("${tms.api.base-path}/fleet/carriers/import")
@Tag(name = "Carrier import", description = "Bulk carrier import: template, preview and apply")
public class CarrierImportController {

    private final CarrierImportService carrierImportService;
    private final CarrierImportTemplate carrierImportTemplate;

    public CarrierImportController(
            CarrierImportService carrierImportService, CarrierImportTemplate carrierImportTemplate) {
        this.carrierImportService = carrierImportService;
        this.carrierImportTemplate = carrierImportTemplate;
    }

    @GetMapping("/template")
    @PreAuthorize("hasAuthority('fleet.carrier:read')")
    @Operation(summary = "Download the carrier import template as .xlsx or .csv")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ResponseEntity<Resource> template(
            CompanyScope scope, @RequestParam(name = "format", defaultValue = "XLSX") ImportFormat format) {
        byte[] content = carrierImportTemplate.build(format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(carrierImportTemplate.fileName(format))
                        .build()
                        .toString())
                .contentType(MediaType.parseMediaType(format.contentType()))
                .contentLength(content.length)
                .cacheControl(CacheControl.noStore())
                .body(new ByteArrayResource(content));
    }

    @PostMapping(path = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('fleet.carrier:read')")
    @Operation(summary = "Validate an import file and report what applying it would do, writing nothing")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ImportReport<CarrierImportPreview> preview(CompanyScope scope, @RequestParam("file") MultipartFile file) {
        byte[] content = bytesOf(file);
        ImportFormat format = ImportSupport.requireSupportedFile(content, fileNameOf(file), ImportLimits.standard());
        return carrierImportService.dryRun(scope, content, fileNameOf(file), format);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('fleet.carrier:manage')")
    @Operation(summary = "Apply an import file in one transaction, or write nothing if any row has an issue")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ImportReport<CarrierImportPreview> apply(CompanyScope scope, @RequestParam("file") MultipartFile file) {
        byte[] content = bytesOf(file);
        ImportFormat format = ImportSupport.requireSupportedFile(content, fileNameOf(file), ImportLimits.standard());
        return carrierImportService.apply(scope, content, fileNameOf(file), format);
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
