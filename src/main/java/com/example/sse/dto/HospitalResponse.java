package com.example.sse.dto;

import com.example.sse.model.Hospital;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 医院查询响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HospitalResponse {
    private List<Hospital> hospitals;  // 医院列表
    private Integer total;             // 总记录数
    private Integer page;              // 当前页码
    private Integer size;              // 每页记录数
    private String message;            // 提示信息，如无匹配医院时的提示
    
    public HospitalResponse(String message) {
        this.message = message;
    }
} 