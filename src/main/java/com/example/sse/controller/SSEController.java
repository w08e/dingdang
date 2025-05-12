package com.example.sse.controller;


import com.example.sse.context.SseEmitterHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class SSEController {

    @GetMapping("/reg")
    public SseEmitter reg(Long uid, String page) {
        SseEmitter emitter = new SseEmitter();
        SseEmitterHolder.add(uid, page, emitter);
        emitter.onCompletion(() -> SseEmitterHolder.remove(uid, page));
        return emitter;
    }

    @PostMapping("/send")
    public String send(Long uid, String page, String data) {
        SseEmitterHolder.send(data, uid, page);
        return "success";
    }
}
