package com.osundosun.mupid.api.email.converter;


import com.osundosun.mupid.api.email.enumerate.EmailVerificationPurpose;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class EmailVerificationPurposeConverter implements AttributeConverter<EmailVerificationPurpose, String> {

    @Override
    public String convertToDatabaseColumn(EmailVerificationPurpose attribute) {
        return (attribute != null) ? attribute.getCode() : null;
    }

    @Override
    public EmailVerificationPurpose convertToEntityAttribute(String dbData) {
        return EmailVerificationPurpose.fromCode(dbData);
    }

}
