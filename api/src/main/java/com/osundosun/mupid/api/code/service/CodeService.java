package com.osundosun.mupid.api.code.service;

import com.osundosun.mupid.api.code.request.CodeCreateRequest;
import com.osundosun.mupid.api.code.response.CodeResponse;

public interface CodeService {

    /**
     * 코드를 추가한다. 이미 있으면 그대로 돌려준다.
     */
    CodeResponse addCode(CodeCreateRequest request);

}
