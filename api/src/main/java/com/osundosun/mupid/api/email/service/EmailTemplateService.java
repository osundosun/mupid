package com.osundosun.mupid.api.email.service;

import java.util.Map;

public interface EmailTemplateService {
    
    String buildHtml(String templateName, Map<String, Object> variables);

}
