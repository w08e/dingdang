package com.example.sse.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CsvToSqlResponse {
    private boolean success;
    private String message;
    private List<String> sqlStatements;
    private List<String> columnNames;
} 