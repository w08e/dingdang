package com.example.sse.dto;

import lombok.Data;

/**
 * 医院查询请求参数
 */
@Data
public class HospitalRequest {
    // 用户位置
    private Double longitude;    // 经度
    private Double latitude;     // 纬度
    
    // 筛选条件
    private String province;     // 省份
    private String city;         // 城市
    private String keyword;      // 搜索关键词
    
    // 分页参数
    private Integer page = 0;         // 页码
    private Integer size = 50;        // 每页记录数
} 