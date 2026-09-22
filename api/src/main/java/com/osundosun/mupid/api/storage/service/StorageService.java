package com.osundosun.mupid.api.storage.service;

import java.util.Optional;

import org.springframework.web.multipart.MultipartFile;

import com.osundosun.mupid.api.storage.dto.StorageFileData;
import com.osundosun.mupid.api.storage.dto.StorageObjectStat;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;

public interface StorageService {
    
    ObjectStorageFile uploadFile(FileType fileType, FileCategory category, String key, MultipartFile file);

    /**
     * mime 을 호출부가 지정하는 업로드.
     * 브라우저가 아닌 클라이언트는 Content-Type 을 신뢰할 수 없고,
     * object_storage_files.mime 이 varchar(15) 라 긴 값이 들어오면 INSERT 가 깨진다.
     */
    ObjectStorageFile uploadFile(FileType fileType, FileCategory category, String key, MultipartFile file, String mime);

    /**
     * 서버가 만든 바이트를 그대로 올린다. {@link MultipartFile} 이 없는 경로용이다.
     *
     * <p>{@code uploadFile} 은 사람이 올린 파일을 받는 자리라 {@code MultipartFile} 을
     * 받는다. 파형 JSON 처럼 <b>서버가 직렬화한 바이트</b>에는 그 그릇이 없고, 흉내
     * 내려면 {@code MultipartFile} 구현을 하나 더 들고 다녀야 한다.
     *
     * <p>{@code uploadFile} 과 같은 규칙을 따른다 — 같은 키면 기존 행을 갱신하고,
     * 없으면 새 행을 만든다. 그래서 다시 올려도 {@code fileId} 가 유지된다.
     */
    ObjectStorageFile uploadBytes(
        FileType fileType, FileCategory category, String key,
        String originalFileName, String mime, byte[] data);

    /**
     * 오브젝트가 실제로 있는지 HEAD 로만 확인한다. 본문은 받지 않는다.
     * 스프링을 거치지 않고 올라온 파일(nginx → 스토리지 직행)을 검증할 때 쓴다.
     */
    Optional<StorageObjectStat> statObject(String key);

    /**
     * 업로드 없이 메타데이터만 기록한다.
     * 파일 자체는 이미 스토리지에 올라와 있어야 한다.
     */
    ObjectStorageFile registerFile(
        FileType fileType, FileCategory category, String key,
        String originalFileName, String mime, int fileSizeBytes);

    StorageFileData getFileData(int fileId);

    void deleteFile(int fileId);

    Optional<ObjectStorageFile> getFile(int fileId);

    Optional<ObjectStorageFile> getFileByKey(String key);

    /**
     * 저장 키로 바이트를 바로 읽는다. 메타데이터 행이 없어도 오브젝트만 있으면 읽힌다.
     *
     * <p>{@code getFileData(int fileId)} 와 다르다. 그쪽은 행을 거쳐야 하므로 행이 없는
     * 키는 못 읽는다 — 부트스트랩이 잘못된 키로 올려 둔 객체처럼 <b>행이 가리키지 않는
     * 오브젝트</b>를 옮길 때가 바로 그 경우다.
     *
     * <p>없으면 빈 값을 돌려준다. 없는 것과 읽지 못한 것을 부르는 쪽이 구분할 필요가 없어서다.
     */
    Optional<byte[]> getFileDataByKey(String key);

    /**
     * 메타데이터 행이 가리키는 저장 키를 옛 키에서 새 키로 바꾼다. 바뀐 행 수를 돌려준다.
     *
     * <p>오브젝트를 옮긴 뒤 <b>행도 같이</b> 옮겨야 한다. {@code imageUrl(ObjectStorageFile)} 이
     * 행의 키를 되짚어 조회 URL 을 만들기 때문이다 — 오브젝트만 새 자리로 가고 행이 옛 키를
     * 그대로 들고 있으면 URL 은 여전히 null 이다.
     *
     * <p>0 을 돌려주는 경우는 그 키의 행이 없다는 뜻이다(예: 옛 키로 올려 둔 객체만 있고 행은
     * 다른 키를 이미 가리킴). 오류가 아니므로 부르는 쪽이 판단한다.
     */
    int renameStorageObjectKey(String stale, String wanted);

    boolean isFileExists(String key);

    /**
     * 오브젝트 하나를 있는지 묻지 않고 지운다. 메타데이터 행은 건드리지 않는다.
     *
     * <p>`deleteFile` 과 다르다. 그쪽은 `object_storage_files` 행을 찾아 소프트 삭제하고
     * 오브젝트도 함께 지우는데, 여기서 지우려는 것은 <b>잘못된 키로 올라가 있어 행이
     * 가리키지도 않는 객체</b>다 — 그 키로 행을 찾으면 못 찾아 예외가 난다.
     *
     * <p>대상 스토리지(개발 SeaweedFS)를 확인했다: 삭제는 <b>키 하나에만</b> 걸린다. 같은
     * 바이트를 다른 키로 올린 뒤 옛 키를 지워도 새 키의 객체와 그 DB 행은 그대로 남는다.
     * (메타데이터와 오브젝트가 한 경로인 스토리지라면 다를 수 있으니, 옮기는 쪽은
     * <b>새 자리에 먼저 올리고 나서</b> 부를 것.)
     *
     * <p>지우지 못해도 예외를 던지지 않는다. 남은 옛 객체는 보이지 않는 쓰레기일 뿐이고,
     * 그 때문에 기동이 실패하면 잃는 것이 더 크다.
     */
    void deleteObjectQuietly(String key);

}
