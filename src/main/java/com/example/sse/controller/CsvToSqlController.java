package com.example.sse.controller;

import com.example.sse.model.CsvToSqlResponse;
import com.example.sse.service.CsvToSqlService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/csv-to-sql")
public class CsvToSqlController {

    private final CsvToSqlService csvToSqlService;

    @Autowired
    public CsvToSqlController(CsvToSqlService csvToSqlService) {
        this.csvToSqlService = csvToSqlService;
    }

    @PostMapping("/upload")
    public ResponseEntity<CsvToSqlResponse> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("tableName") String tableName,
            @RequestParam("databaseType") String databaseType,
            @RequestParam(value = "columnTypes", required = false) Map<String, String> columnTypes) {
        
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(
                new CsvToSqlResponse(false, "Please select a file to upload", null, null)
            );
        }

        CsvToSqlResponse response = csvToSqlService.processFile(file, tableName, databaseType, columnTypes);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/database-types")
    public ResponseEntity<List<String>> getDatabaseTypes() {
        // Return a list of supported database types
        return ResponseEntity.ok(Arrays.asList("mysql", "postgresql", "oracle", "sqlserver"));
    }

    @PostMapping("/preview")
    public ResponseEntity<List<String>> previewColumns(@RequestParam("file") MultipartFile file) {
        try {
            CsvToSqlResponse response = csvToSqlService.processFile(file, "preview", "mysql", null);
            return ResponseEntity.ok(response.getColumnNames());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(null);
        }
    }
} 