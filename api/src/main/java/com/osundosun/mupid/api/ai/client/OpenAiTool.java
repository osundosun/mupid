package com.osundosun.mupid.api.ai.client;

import java.util.Map;

/**
 * 모델에게 알려 주는 툴 선언.
 */
public record OpenAiTool(

    String type,

    Function function

) {

    public record Function(

        String name,

        String description,

        /**
         * JSON Schema. `Map` 의 **키는 snake_case 로 변환되지 않으므로**
         * (`type`·`properties`·`required`) 스키마 예약어를 그대로 적으면 된다.
         */
        Map<String, Object> parameters

    ) {
    }

    public static OpenAiTool function(String name, String description, Map<String, Object> parameters) {
        return new OpenAiTool("function", new Function(name, description, parameters));
    }

}
