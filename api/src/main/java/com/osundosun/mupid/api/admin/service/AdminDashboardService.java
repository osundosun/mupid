package com.osundosun.mupid.api.admin.service;

import com.osundosun.mupid.api.admin.response.AdminDashboardResponse;

public interface AdminDashboardService {

    /**
     * 첫 화면에 필요한 것 전부. 자원별 행 수, JVM 상태, 붙어 있는 저장소 상태.
     */
    AdminDashboardResponse dashboard();

}
