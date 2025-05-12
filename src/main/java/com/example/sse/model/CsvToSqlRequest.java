package com.example.sse.model;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Data
public class CsvToSqlRequest {
    private MultipartFile csvFile;
    private String tableName;
    private String databaseType;
    private Map<String, String> columnTypes; // Map column names to their SQL types
} 