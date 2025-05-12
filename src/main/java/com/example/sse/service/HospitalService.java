package com.example.sse.service;

import com.example.sse.dto.HospitalRequest;
import com.example.sse.dto.HospitalResponse;
import com.example.sse.model.Hospital;

import java.util.List;

/**
 * 医院服务接口
 */
public interface HospitalService {
    
    /**
     * 加载医院数据
     */
    void loadHospitalData();
    
    /**
     * 获取所有省份
     */
    List<String> getAllProvinces();
    
    /**
     * 获取指定省份的所有城市
     * @param province 省份
     */
    List<String> getCitiesByProvince(String province);
    
    /**
     * 根据条件查询医院列表
     * @param request 查询条件
     * @return 医院列表响应
     */
    HospitalResponse getHospitalsByCondition(HospitalRequest request) throws InterruptedException;
    
    /**
     * 获取医院位置信息
     * @param hospital 医院
     * @return 更新后的医院信息
     */
    Hospital getHospitalLocation(Hospital hospital);
} 