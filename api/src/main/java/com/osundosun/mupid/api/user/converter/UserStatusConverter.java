package com.osundosun.mupid.api.user.converter;

import com.osundosun.mupid.api.user.enumerate.UserStatus;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class UserStatusConverter implements AttributeConverter<UserStatus, String> {

    @Override
    public String convertToDatabaseColumn(UserStatus attribute) {
        return (attribute != null) ? attribute.getCode() : null;
    }

    @Override
    public UserStatus convertToEntityAttribute(String dbData) {
        return UserStatus.fromCode(dbData);
    }
    
}
