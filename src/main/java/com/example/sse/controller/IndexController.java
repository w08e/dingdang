package com.example.sse.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 首页控制器
 */
@Controller
public class IndexController {

    /**
     * 处理根路径请求，返回index模板
     */
    @GetMapping("/")
    public String index() {
        return "index";
    }
} 