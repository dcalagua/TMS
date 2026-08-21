package com.ebim.tms.masterdata.api;

import com.ebim.tms.masterdata.application.imports.LocationImportPreview;
import com.ebim.tms.masterdata.application.imports.LocationImportService;
import com.ebim.tms.masterdata.application.imports.LocationImportTemplate;
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
 * The bulk Location import: download a template, preview a file, apply it. Same deliberate
 * asymmetry as {@code OrderImportController}: {@code preview} needs only
 * {@code masterdata.location:read}, {@code apply} needs {@code masterdata.location:manage} - the
 * same permission creating one location by hand needs, because creating three hundred at once
 * must not be an easier authorisation than creating one.
 */
@RestController
@RequestMapping("${tms.api.base-path}/masterdata/locations/import")
@Tag(name = "Location import", description = "Bulk location import: template, preview and apply")
public class LocationImportController {

    private final LocationImportService locationImportService;
    private final LocationImportTemplate locationImportTemplate;

    public LocationImportController(
            LocationImportService locationImportService, LocationImportTemplate locationImportTemplate) {
        this.locationImportService = locationImportService;
        this.locationImportTemplate = locationImportTemplate;
    }

    @GetMapping("/template")
    @PreAuthorize("hasAuthority('masterdata.location:read')")
    @Operation(summary = "Download the location import template as .xlsx or .csv")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ResponseEntity<Resource> template(
            CompanyScope scope, @RequestParam(name = "format", defaultValue = "XLSX") ImportFormat format) {
        byte[] content = locationImportTemplate.build(format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(locationImportTemplate.fileName(format))
                        .build()
                        .toString())
                .contentType(MediaType.parseMediaType(format.contentType()))
                .contentLength(content.length)
                .cacheControl(CacheControl.noStore())
                .body(new ByteArrayResource(content));
    }

    @PostMapping(path = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('masterdata.location:read')")
    @Operation(summary = "Validate an import file and report what applying it would do, writing nothing")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ImportReport<LocationImportPreview> preview(CompanyScope scope, @RequestParam("file") MultipartFile file) {
        byte[] content = bytesOf(file);
        ImportFormat format = ImportSupport.requireSupportedFile(content, fileNameOf(file), formatLimits());
        return locationImportService.dryRun(scope, content, fileNameOf(file), format);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('masterdata.location:manage')")
    @Operation(summary = "Apply an import file in one transaction, or write nothing if any row has an issue")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ImportReport<LocationImportPreview> apply(CompanyScope scope, @RequestParam("file") MultipartFile file) {
        byte[] content = bytesOf(file);
        ImportFormat format = ImportSupport.requireSupportedFile(content, fileNameOf(file), formatLimits());
        return locationImportService.apply(scope, content, fileNameOf(file), format);
    }

    private static ImportLimits formatLimits() {
        return ImportLimits.standard();
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

    /** The original file name, reduced to its last path segment - see {@code OrderImportController} for why. */
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
