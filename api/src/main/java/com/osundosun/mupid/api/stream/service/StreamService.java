package com.osundosun.mupid.api.stream.service;

import java.util.Collection;
import java.util.List;

import com.osundosun.mupid.api.stream.entity.Stream;
import com.osundosun.mupid.api.stream.enumerate.StreamStatus;
import com.osundosun.mupid.api.stream.request.StreamCreateRequest;

public interface StreamService {

    /**
     * 음악에 새 스트림을 만든다. 상태는 PENDING 으로 시작한다.
     */
    int createStream(int musicId, StreamCreateRequest request);

    Stream getStream(int streamId);

    List<Stream> getStreamByMusicId(int musicId);

    /**
     * 여러 곡의 스트림을 한 번에 가져온다. 목록 화면이 곡마다 조회하지 않도록.
     */
    List<Stream> getStreamByMusicIds(Collection<Integer> musicIds);

    /**
     * 인코딩 상태를 바꾼다. DONE 은 청크가 모두 올라온 뒤에만 허용한다.
     */
    void updateStatus(int streamId, StreamStatus status);

    /**
     * 스트림과 딸린 청크·스토리지 파일을 모두 지운다. 재등록할 때 쓴다.
     */
    void deleteStream(int streamId);
    
}
