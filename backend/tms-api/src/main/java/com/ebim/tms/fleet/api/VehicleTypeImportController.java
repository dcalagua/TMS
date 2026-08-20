package com.ebim.tms.fleet.api;

import com.ebim.tms.fleet.application.imports.VehicleTypeImportPreview;
import com.ebim.tms.fleet.application.imports.VehicleTypeImportService;
import com.ebim.tms.fleet.application.imports.VehicleTypeImportTemplate;
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
 * The bulk Vehicle Type import: download a template, preview a file, apply it. Same deliberate
 * asymmetry as {@code LocationImportController}: {@code preview} needs only
 * {@code fleet.vehicle_type:read}, {@code apply} needs {@code fleet.vehicle_type:manage}.
 */
@RestController
@RequestMapping("${tms.api.base-path}/fleet/vehicle-types/import")
@Tag(name = "Vehicle type import", description = "Bulk vehicle type import: template, preview and apply")
public class VehicleTypeImportController {

    private final VehicleTypeImportService vehicleTypeImportService;
    private final VehicleTypeImportTemplate vehicleTypeImportTemplate;

    public VehicleTypeImportController(
            VehicleTypeImportService vehicleTypeImportService, VehicleTypeImportTemplate vehicleTypeImportTemplate) {
        this.vehicleTypeImportService = vehicleTypeImportService;
        this.vehicleTypeImportTemplate = vehicleTypeImportTemplate;
    }

    @GetMapping("/template")
    @PreAuthorize("hasAuthority('fleet.vehicle_type:read')")
    @Operation(summary = "Download the vehicle type import template as .xlsx or .csv")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ResponseEntity<Resource> template(
            CompanyScope scope, @RequestParam(name = "format", defaultValue = "XLSX") ImportFormat format) {
        byte[] content = vehicleTypeImportTemplate.build(format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(vehicleTypeImportTemplate.fileName(format))
                        .build()
                        .toString())
                .contentType(MediaType.parseMediaType(format.contentType()))
                .contentLength(content.length)
                .cacheControl(CacheControl.noStore())
                .body(new ByteArrayResource(content));
    }

    @PostMapping(path = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('fleet.vehicle_type:read')")
    @Operation(summary = "Validate an import file and report what applying it would do, writing nothing")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ImportReport<VehicleTypeImportPreview> preview(CompanyScope scope, @RequestParam("file") MultipartFile file) {
        byte[] content = bytesOf(file);
        ImportFormat format = ImportSupport.requireSupportedFile(content, fileNameOf(file), ImportLimits.standard());
        return vehicleTypeImportService.dryRun(scope, content, fileNameOf(file), format);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('fleet.vehicle_type:manage')")
    @Operation(summary = "Apply an import file in one transaction, or write nothing if any row has an issue")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ImportReport<VehicleTypeImportPreview> apply(CompanyScope scope, @RequestParam("file") MultipartFile file) {
        byte[] content = bytesOf(file);
        ImportFormat format = ImportSupport.requireSupportedFile(content, fileNameOf(file), ImportLimits.standard());
        return vehicleTypeImportService.apply(scope, content, fileNameOf(file), format);
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
