package com.example.sse.service;

import com.example.sse.model.CsvToSqlResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CsvToSqlService {

    public CsvToSqlResponse processFile(MultipartFile file, String tableName, String databaseType, Map<String, String> columnTypes) {
        try {
            List<String> sqlStatements = new ArrayList<>();
            List<String> columnNames = new ArrayList<>();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
                String headerLine = reader.readLine();
                if (headerLine == null) {
                    return new CsvToSqlResponse(false, "CSV file is empty", Collections.emptyList(), Collections.emptyList());
                }
                
                // Parse header to get column names
                String[] headers = headerLine.split(",");
                columnNames = Arrays.asList(headers);
                
                // Process each data row
                String line;
                while ((line = reader.readLine()) != null && !line.trim().isEmpty()) {
                    List<String> values = parseCSVLine(line);
                    
                    // If we have fewer values than headers, skip this row
                    if (values.size() < headers.length) {
                        continue;
                    }
                    
                    StringBuilder sql = new StringBuilder("INSERT INTO ")
                        .append(tableName)
                        .append(" (");
                    
                    // Add column names
                    sql.append(String.join(", ", columnNames));
                    sql.append(") VALUES (");
                    
                    // Add values, handling different types
                    List<String> formattedValues = new ArrayList<>();
                    for (int i = 0; i < Math.min(values.size(), columnNames.size()); i++) {
                        String columnName = columnNames.get(i);
                        String value = values.get(i);
                        
                        // Handle based on column type if specified
                        if (columnTypes != null && columnTypes.containsKey(columnName)) {
                            String type = columnTypes.get(columnName);
                            if (type.toLowerCase().contains("date") || type.toLowerCase().contains("time")) {
                                // Format date/time according to the database
                                formattedValues.add(formatDateTimeValue(value, databaseType));
                            } else if (type.toLowerCase().contains("int") || type.toLowerCase().contains("numeric") || 
                                       type.toLowerCase().contains("double") || type.toLowerCase().contains("float")) {
                                // Numeric types
                                formattedValues.add(value.isEmpty() ? "NULL" : value);
                            } else {
                                // String types
                                formattedValues.add(value.isEmpty() ? "NULL" : "'" + escapeValue(value) + "'");
                            }
                        } else {
                            // Default: treat as string
                            formattedValues.add(value.isEmpty() ? "NULL" : "'" + escapeValue(value) + "'");
                        }
                    }
                    
                    sql.append(String.join(", ", formattedValues));
                    sql.append(");");
                    
                    sqlStatements.add(sql.toString());
                }
            }
            
            return new CsvToSqlResponse(true, "Successfully generated SQL", sqlStatements, columnNames);
        } catch (IOException e) {
            return new CsvToSqlResponse(false, "Error processing file: " + e.getMessage(), Collections.emptyList(), Collections.emptyList());
        }
    }
    
    private List<String> parseCSVLine(String line) {
        List<String> result = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder currentValue = new StringBuilder();
        
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            
            if (c == '"') {
                // Handle quotes
                if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    // Escaped quote
                    currentValue.append('"');
                    i++; // Skip the next quote
                } else {
                    // Toggle in-quotes state
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                // End of field
                result.add(currentValue.toString().trim());
                currentValue = new StringBuilder();
            } else {
                // Regular character
                currentValue.append(c);
            }
        }
        
        // Add the last value
        result.add(currentValue.toString().trim());
        
        return result;
    }
    
    private String escapeValue(String value) {
        // Remove any quote prefix/suffix like ="value"
        if (value.startsWith("=\"") && value.endsWith("\"")) {
            value = value.substring(2, value.length() - 1);
        } else if (value.startsWith("=") && !value.contains("\"")) {
            // Handle cases like =1234
            value = value.substring(1);
        }
        
        // Basic SQL string escaping
        return value.replace("'", "''");
    }
    
    private String formatDateTimeValue(String value, String databaseType) {
        // Remove any wrapping quotes and = 
        if (value.startsWith("=\"") && value.endsWith("\"")) {
            value = value.substring(2, value.length() - 1);
        } else if (value.startsWith("=")) {
            value = value.substring(1);
        }
        
        if (value.isEmpty()) {
            return "NULL";
        }
        
        // Different formats for different databases
        switch (databaseType.toLowerCase()) {
            case "mysql":
                return "STR_TO_DATE('" + value + "', '%Y%m%d')";
            case "postgresql":
                return "TO_DATE('" + value + "', 'YYYYMMDD')";
            case "oracle":
                return "TO_DATE('" + value + "', 'YYYYMMDD')";
            case "sqlserver":
                return "CONVERT(DATE, '" + value + "', 112)";
            default:
                return "'" + value + "'";
        }
    }
} 