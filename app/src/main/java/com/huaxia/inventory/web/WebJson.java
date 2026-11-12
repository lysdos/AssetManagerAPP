package com.huaxia.inventory.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** 共享 ObjectMapper —— 复用已有 Jackson 依赖，不新增库。 */
public final class WebJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private WebJson() {}

    public static byte[] write(Object payload) {
        try {
            return MAPPER.writeValueAsBytes(payload);
        } catch (Exception e) {
            return "{\"error\":{\"code\":\"INTERNAL\",\"message\":\"序列化失败\"}}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    public static <T> T read(String json, Class<T> type) throws Exception {
        return MAPPER.readValue(json, type);
    }
}
