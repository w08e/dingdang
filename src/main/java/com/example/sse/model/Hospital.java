package com.example.sse.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 医院实体类
 */
@Slf4j
@Data
public class Hospital {
    // 医院基本信息
    private String name;              // 医院名称
    private String province;          // 省份
    private String city;              // 城市
    private String district;          // 县区
    private String address;           // 医院地址
    private String clinicName;        // 门诊名称
    private String clinicTime;        // 门诊时间
    
    // 位置信息
    private Double longitude;         // 经度
    private Double latitude;          // 纬度
    private Double distance;          // 距离
    private Boolean hasAccurateLocation = false; // 是否有精确位置
    
    // 用于从JSON数据映射，使用JsonProperty注解确保映射正确
    private String 医院;
    private String 省;
    
    @JsonProperty("城市（地级市）")  // 使用原始JSON字段名
    private String 城市地级市;        // 修改字段名，移除括号
    
    private String 县级市;
    private String 医院地址;
    private String 门诊名称;
    private String 门诊时间;
    private String 经度;
    private String 纬度;
    
    /**
     * 标准化数据，将原始JSON字段映射到标准字段
     */
    public void standardize() {
        this.name = this.医院;
        this.province = this.省;
        
        // 添加额外的日志输出以便调试
        System.out.println("城市字段值: " + this.城市地级市);
        
        this.city = this.城市地级市;    // 同步修改字段引用
        this.district = this.县级市;
        this.address = this.医院地址;
        this.clinicName = this.门诊名称;
        this.clinicTime = this.门诊时间;
        
        // 处理经纬度
        try {
            if (this.经度 != null && !this.经度.isEmpty()) {
                this.longitude = Double.parseDouble(this.经度);
            }
            if (this.纬度 != null && !this.纬度.isEmpty()) {
                this.latitude = Double.parseDouble(this.纬度);
            }
            // 如果有经纬度，标记为精确位置
            if (this.longitude != null && this.latitude != null) {
                this.hasAccurateLocation = true;
            }
        } catch (NumberFormatException e) {
            // 经纬度解析失败，保持null值
        }
    }
    
    /**
     * 获取完整地址
     */
    public String getFullAddress() {
        StringBuilder sb = new StringBuilder();
        if (province != null && !province.isEmpty()) {
            sb.append(province);
        }
        if (city != null && !city.isEmpty() && !city.equals(province)) {
            sb.append(city);
        }
        if (district != null && !district.isEmpty() && !" ".equals(district)) {
            sb.append(district);
        }
        if (address != null && !address.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append(" ");
            }
            sb.append(address);
        }
        log.info("查询经纬度使用位置为：{}", sb);
        return sb.toString();
    }
}