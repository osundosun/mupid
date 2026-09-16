package com.osundosun.mupid.api.music.service;

import com.osundosun.mupid.api.music.request.MusicClassificationRequest;
import com.osundosun.mupid.api.music.response.MusicClassificationResponse;

public interface MusicClassificationService {

    /**
     * 한 곡의 장르·분위기를 통째로 갈아 끼운다.
     */
    MusicClassificationResponse replaceClassification(int musicId, MusicClassificationRequest request);

}
