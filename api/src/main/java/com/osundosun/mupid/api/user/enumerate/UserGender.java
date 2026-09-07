package com.osundosun.mupid.api.user.enumerate;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum UserGender {
    
    Male("남성", "MALE"),
    Female("여성", "FEMALE"),
    Extra("기타", "ETC");

    private final String displayName;
    private final String code;

    private static final Map<String, UserGender> CODE_MAP = Collections.unmodifiableMap(
        Arrays.stream(values())
            .collect(Collectors.toMap(it -> it.getCode(), Function.identity()))
    );

    public static UserGender fromCode(String code) {
        if (code == null) return null;
        UserGender value = CODE_MAP.get(code);
        if (value == null)
            throw new IllegalArgumentException("Unknown code (%s)".formatted(code));
        return value;
    }

}
