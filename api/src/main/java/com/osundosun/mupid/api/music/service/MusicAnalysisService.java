package com.osundosun.mupid.api.music.service;

import com.osundosun.mupid.api.music.request.MusicAnalysisRequest;
import com.osundosun.mupid.api.music.response.MusicAnalysisBulkResponse;

public interface MusicAnalysisService {

    /**
     * 한 곡의 보컬 판정을 통째로 갈아 끼운다.
     *
     * <p>이미 있으면 덮어쓴다 — {@code music_analysis} 는 곡당 한 행이다.
     */
    MusicAnalysisBulkResponse replaceAnalysis(int musicId, MusicAnalysisRequest request);

}
