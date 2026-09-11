package com.osundosun.mupid.api.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.osundosun.mupid.api.common.converter.StringToEnumIgnoringCaseConverterFactory;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        // Controller로 입력되는 모든 Enum String을 자동으로 Enum으로 변환
        registry.addConverterFactory(
            new StringToEnumIgnoringCaseConverterFactory()
        );
    }

}
