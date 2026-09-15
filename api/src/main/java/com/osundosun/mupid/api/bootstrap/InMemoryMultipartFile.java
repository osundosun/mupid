package com.osundosun.mupid.api.bootstrap;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.web.multipart.MultipartFile;

/**
 * 메모리에 들고 있는 바이트를 {@link MultipartFile} 인 척 넘기는 어댑터.
 *
 * <p>{@code StorageService.uploadFile} 은 업로드 입구가 컨트롤러뿐이라 {@link MultipartFile}
 * 만 받는다. 부트스트랩은 classpath 의 zip 에서 꺼낸 바이트를 올려야 하는데, 이것 때문에
 * {@code S3Client} 를 직접 부르면 키 중복 처리와 메타데이터 기록이 스토리지 계층 밖으로
 * 새어 나간다. 그래서 통로를 새로 뚫지 않고 바이트를 이 모양으로 감싼다.
 *
 * <p>업로드가 실제로 쓰는 것은 {@code getSize} · {@code getInputStream} ·
 * {@code getOriginalFilename} 셋뿐이다.
 */
class InMemoryMultipartFile implements MultipartFile {

    private final String name;
    private final String contentType;
    private final byte[] content;

    InMemoryMultipartFile(String name, String contentType, byte[] content) {
        this.name = name;
        this.contentType = contentType;
        this.content = content;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getOriginalFilename() {
        return name;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return content.length == 0;
    }

    @Override
    public long getSize() {
        return content.length;
    }

    @Override
    public byte[] getBytes() {
        return content;
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(content);
    }

    @Override
    public void transferTo(File dest) throws IOException {
        Files.write(dest.toPath(), content);
    }

    @Override
    public void transferTo(Path dest) throws IOException {
        Files.write(dest, content);
    }

}
