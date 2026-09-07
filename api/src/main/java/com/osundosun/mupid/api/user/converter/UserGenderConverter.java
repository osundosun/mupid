package com.osundosun.mupid.api.user.converter;

import com.osundosun.mupid.api.user.enumerate.UserGender;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class UserGenderConverter implements AttributeConverter<UserGender, String> {

    @Override
    public String convertToDatabaseColumn(UserGender attribute) {
        return (attribute != null) ? attribute.getCode() : null;
    }

    @Override
    public UserGender convertToEntityAttribute(String dbData) {
        return UserGender.fromCode(dbData);
    }
    
}
