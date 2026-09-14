package com.osundosun.mupid.api.common.converter;

import org.jspecify.annotations.Nullable;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;

import com.osundosun.mupid.api.common.error.GlobalErrorCode;
import com.osundosun.mupid.api.common.exception.BusinessException;

public class StringToEnumIgnoringCaseConverterFactory implements ConverterFactory<String, Enum<?>> {

    @Override
    public <T extends Enum<?>> Converter<String, ? extends @Nullable T> getConverter(Class<T> targetType) {
        return new StringToEnumConverter<>(targetType);
    }

    private static class StringToEnumConverter<T extends Enum<?>> implements Converter<String, T> {
        private final Class<T> enumType;

        public StringToEnumConverter(Class<T> enumType) {
            this.enumType = enumType;
        }

        @Override
        public T convert(String source) {
            if (source == null || source.isBlank()) {
                return null;
            }

            String trimmed = source.trim();
            T[] constants = enumType.getEnumConstants();
            if (constants != null) {
                for (T constant : constants) {
                    if (constant.name().equalsIgnoreCase(trimmed)) {
                        return constant;
                    }
                }
            }

            throw new BusinessException(GlobalErrorCode.NOT_READABLE_MESSAGE);
        }
        
    }

}
