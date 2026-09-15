package com.osundosun.mupid.api.ai.service;

import com.osundosun.mupid.api.ai.request.AiChatRequest;
import com.osundosun.mupid.api.ai.response.AiChatResponse;
import com.osundosun.mupid.api.user.entity.User;

public interface AiChatService {

    AiChatResponse chat(User user, AiChatRequest request);

}
