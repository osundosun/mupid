package com.osundosun.mupid.api.admin.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import com.osundosun.mupid.api.admin.error.AdminErrorCode;
import com.osundosun.mupid.api.common.exception.BusinessException;

/**
 * JSON 으로 들어온 값을 엔티티 필드의 자바 타입으로 바꾼다.
 *
 * 화면은 자원마다 다른 타입을 미리 알 수 없어 대부분을 문자열로 보낸다. Jackson 이
 * `Map&lt;String, Object&gt;` 로 풀어 주는 값은 String·Integer·Double·Boolean·null 뿐이라
 * 나머지는 여기서 직접 맞춘다.
 *
 * 못 바꾸면 {@link AdminErrorCode#INVALID_FIELD_VALUE} 로 400 을 낸다. 조용히 null 을
 * 넣으면 `NOT NULL` 컬럼에서 500 이 되어 원인이 사라진다.
 */
final class AdminValueCodec {

    private AdminValueCodec() {
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    static Object coerce(Object value, Class<?> target) {
        if (value == null) {
            return null;
        }
        if (target.isInstance(value)) {
            return value;
        }

        if (target == String.class) {
            return String.valueOf(value);
        }

        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }

        try {
            if (target == Integer.class || target == int.class) {
                return Integer.valueOf(text);
            }
            if (target == Long.class || target == long.class) {
                return Long.valueOf(text);
            }
            if (target == Short.class || target == short.class) {
                return Short.valueOf(text);
            }
            if (target == Byte.class || target == byte.class) {
                return Byte.valueOf(text);
            }
            if (target == Double.class || target == double.class) {
                return Double.valueOf(text);
            }
            if (target == Float.class || target == float.class) {
                return Float.valueOf(text);
            }
            if (target == BigDecimal.class) {
                return new BigDecimal(text);
            }
            if (target == BigInteger.class) {
                return new BigInteger(text);
            }
            if (target == Boolean.class || target == boolean.class) {
                return parseBoolean(text);
            }
            if (target.isEnum()) {
                return Enum.valueOf((Class<Enum>) target, text);
            }
            if (target == UUID.class) {
                return UUID.fromString(text);
            }
            if (target == LocalDateTime.class) {
                return LocalDateTime.parse(normalizeDateTime(text));
            }
            if (target == LocalDate.class) {
                return LocalDate.parse(text);
            }
            if (target == LocalTime.class) {
                return LocalTime.parse(text);
            }
            if (target == Instant.class) {
                return Instant.parse(text);
            }
            if (target == OffsetDateTime.class) {
                return OffsetDateTime.parse(text);
            }
            if (target == ZonedDateTime.class) {
                return ZonedDateTime.parse(text);
            }
            if (target == java.util.Date.class) {
                return java.util.Date.from(
                    LocalDateTime.parse(normalizeDateTime(text)).atZone(ZoneId.systemDefault()).toInstant());
            }
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new BusinessException(AdminErrorCode.INVALID_FIELD_VALUE);
        }

        throw new BusinessException(AdminErrorCode.INVALID_FIELD_VALUE);
    }

    private static boolean parseBoolean(String text) {
        if ("true".equalsIgnoreCase(text) || "1".equals(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text) || "0".equals(text)) {
            return false;
        }
        throw new BusinessException(AdminErrorCode.INVALID_FIELD_VALUE);
    }

    /**
     * 브라우저의 `datetime-local` 입력은 초를 빼고 `2026-09-14T21:30` 으로 보낸다.
     * 반대로 사람이 손으로 적을 때는 가운데를 공백으로 두기 쉽다. 둘 다 받아 준다.
     */
    private static String normalizeDateTime(String text) {
        String normalized = text.contains("T") ? text : text.replaceFirst(" ", "T");
        int timeStart = normalized.indexOf('T');
        if (timeStart >= 0 && normalized.length() - timeStart == 6) {
            // "…T21:30" → "…T21:30:00"
            return normalized + ":00";
        }
        return normalized;
    }

}
