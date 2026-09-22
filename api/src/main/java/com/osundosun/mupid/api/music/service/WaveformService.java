package com.osundosun.mupid.api.music.service;

import com.osundosun.mupid.api.music.request.WaveformReplaceRequest;
import com.osundosun.mupid.api.music.response.WaveformReplaceResponse;
import com.osundosun.mupid.api.music.response.WaveformResponse;

public interface WaveformService {

    /**
     * 한 곡의 파형.
     *
     * <p>곡이 없으면 404, 삭제됐으면 410, 곡은 있는데 파형이 아직 없으면
     * `WAVEFORM_NOT_FOUND`(404) 다. 마지막 경우가 가장 흔하고 <b>정상</b>이다 —
     * 배치가 아직 닿지 않은 곡이다.
     */
    WaveformResponse getWaveform(int musicId);

    /**
     * 한 곡의 파형을 통째로 갈아 끼운다.
     *
     * <p>바이트는 오브젝트 스토리지에 JSON 한 파일로 올라가고
     * (`music/{musicId}/waveform.json`), `music.waveform_file_id` 가 그 파일을
     * 가리킨다. 같은 곡에 다시 올리면 같은 키를 덮어쓰므로 파일 번호가 유지된다.
     */
    WaveformReplaceResponse replaceWaveform(int musicId, WaveformReplaceRequest request);

}
