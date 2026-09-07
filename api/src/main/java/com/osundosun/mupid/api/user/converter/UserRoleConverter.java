package com.osundosun.mupid.api.user.converter;

import com.osundosun.mupid.api.user.enumerate.UserRole;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class UserRoleConverter implements AttributeConverter<UserRole, String> {

    @Override
    public String convertToDatabaseColumn(UserRole attribute) {
        return (attribute != null) ? attribute.getCode() : null;
    }

    @Override
    public UserRole convertToEntityAttribute(String dbData) {
        return UserRole.fromCode(dbData);
    }
    
}
