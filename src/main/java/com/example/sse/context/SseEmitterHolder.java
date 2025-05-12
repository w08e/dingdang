package com.example.sse.context;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SseEmitterHolder {
    private static final Map<Long, Map<String, SseEmitter>> userPageEmitterMap = new ConcurrentHashMap<>();
    private static final Map<String, Map<Long, SseEmitter>> pageUserEmitterMap = new ConcurrentHashMap<>();

    public static void add(Long uid, String page, SseEmitter emitter) {
        userPageEmitterMap.compute(uid, (k, pageSseMap) -> {
            if (pageSseMap == null) {
                pageSseMap = new ConcurrentHashMap<>();
            }
            pageSseMap.put(page, emitter);
            return pageSseMap;
        });
        pageUserEmitterMap.compute(page, (k, userSseMap) -> {
            if (userSseMap == null) {
                userSseMap = new ConcurrentHashMap<>();
            }
            userSseMap.put(uid, emitter);
            return userSseMap;
        });
    }

    public static void remove(Long uid, String page) {
        userPageEmitterMap.computeIfPresent(uid, (k, v) -> {
            v.remove(page);
            return v;
        });
        pageUserEmitterMap.computeIfPresent(page, (k, v) -> {
            v.remove(uid);
            return v;
        });
    }

    public static void sendToAll(String data, String page) {
        pageUserEmitterMap.getOrDefault(page, Collections.emptyMap()).forEach((u, emitter) -> {
            try {
                emitter.send(data, MediaType.TEXT_PLAIN);
            } catch (IOException e) {
                emitter.complete();
                e.printStackTrace();
            }
        });
    }

    public static void send(String data, Long uid, String page) {
        userPageEmitterMap.computeIfPresent(uid, (k, v) -> {
            v.computeIfPresent(page, (p, emitter) -> {
                try {
                    emitter.send(data, MediaType.TEXT_PLAIN);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return emitter;
            });
            return v;
        });
    }
}
