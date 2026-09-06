package com.digithink.zsretail.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.dto.ImportFieldMappingDTO;
import com.digithink.zsretail.dto.ImportPreviewDTO;
import com.digithink.zsretail.dto.ImportResultDTO;
import com.digithink.zsretail.service.DataImportService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("admin/import")
@RequiredArgsConstructor
@Log4j2
public class DataImportAPI {

    private final ApplicationModeService applicationModeService;
    private final DataImportService dataImportService;
    private final ObjectMapper objectMapper;

    /**
     * Upload an Excel file and return the detected column names plus a few preview rows.
     * Does NOT persist anything.
     */
    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> preview(@RequestPart("file") MultipartFile file) {
        if (!applicationModeService.isStandalone()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Data import is only available in standalone mode.");
        }
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("No file provided.");
        }
        try {
            log.info("DataImportAPI::preview - file: {}", file.getOriginalFilename());
            ImportPreviewDTO preview = dataImportService.previewFile(file);
            return ResponseEntity.ok(preview);
        } catch (Exception e) {
            log.error("DataImportAPI::preview error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    /**
     * Execute the import: upload the Excel file together with the field-mapping JSON and entity type.
     * Upserts records using existing repositories. Returns success/error counts.
     */
    @PostMapping(value = "/execute", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> execute(
            @RequestPart("file") MultipartFile file,
            @RequestParam("entityType") String entityType,
            @RequestParam("mapping") String mappingJson) {

        if (!applicationModeService.isStandalone()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Data import is only available in standalone mode.");
        }
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("No file provided.");
        }
        try {
            List<ImportFieldMappingDTO> mapping = objectMapper.readValue(
                    mappingJson, new TypeReference<List<ImportFieldMappingDTO>>() {});
            log.info("DataImportAPI::execute - entityType: {}, file: {}", entityType, file.getOriginalFilename());
            ImportResultDTO result = dataImportService.executeImport(file, entityType, mapping);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("DataImportAPI::execute error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }
}
