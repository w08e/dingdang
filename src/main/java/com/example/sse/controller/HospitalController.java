package com.example.sse.controller;

import com.example.sse.dto.HospitalRequest;
import com.example.sse.dto.HospitalResponse;
import com.example.sse.service.HospitalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 医院查询控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/hospitals")
public class HospitalController {

    private final HospitalService hospitalService;

    public HospitalController(HospitalService hospitalService) {
        this.hospitalService = hospitalService;
    }

    /**
     * 获取所有省份
     */
    @GetMapping("/provinces")
    public ResponseEntity<List<String>> getAllProvinces() {
        return ResponseEntity.ok(hospitalService.getAllProvinces());
    }

    /**
     * 获取指定省份的所有城市
     */
    @GetMapping("/cities")
    public ResponseEntity<List<String>> getCitiesByProvince(@RequestParam String province) {
        return ResponseEntity.ok(hospitalService.getCitiesByProvince(province));
    }

    /**
     * 根据条件查询医院列表
     */
    @PostMapping("/search")
    public ResponseEntity<HospitalResponse> searchHospitals(@RequestBody HospitalRequest request) throws InterruptedException {
        log.info("查询医院，请求参数: {}", request);
        HospitalResponse response = hospitalService.getHospitalsByCondition(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 刷新医院数据（管理接口）
     */
    @PostMapping("/refresh")
    public ResponseEntity<String> refreshHospitalData() {
        log.info("手动刷新医院数据");
        hospitalService.loadHospitalData();
        return ResponseEntity.ok("医院数据刷新成功");
    }
} 