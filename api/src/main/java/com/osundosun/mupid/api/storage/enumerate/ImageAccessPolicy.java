package com.osundosun.mupid.api.storage.enumerate;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.storage.error.StorageImageErrorCode;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserRole;

/**
 * 카테고리별 이미지 접근 정책.
 *
 * <h2>왜 카테고리가 정책을 들고 있는가</h2>
 *
 * 업로드와 삭제는 검사할 것이 같다 — "이 사람이 이 자리에 써도 되는가". 그런데 이
 * 검사가 컨트롤러마다, 서비스마다 흩어지면 한쪽만 고쳐지는 순간 <b>대칭이 깨진다.</b>
 * 실제로 그런 상태였다: {@code PROFILE} 에만 소유자 검사가 있어서 남의 번호로는 못
 * 올리지만, {@code ALBUM}·{@code ARTIST}·{@code TRACK}·{@code MUBTI} 는 로그인만 하면
 * 누구나 덮어쓰고 지울 수 있었다.
 *
 * <p>그래서 검사를 정책 하나로 모으고, 그 정책을 {@link FileCategory} 가 들고 있게 했다.
 * 부르는 쪽은 카테고리만 알면 되고, 검사할 자리가 늘어나도 규칙은 여기 한 곳만 본다.
 *
 * <h2>조회 공개 여부는 왜 여기 없는가</h2>
 *
 * 읽기는 {@code <img src>} 로 오는 경우가 있어서 <b>스프링 시큐리티가 먼저 판단한다</b>
 * ({@code SecurityConfig}). 경로에 카테고리가 없으면({@code /{fileId}}) 거기서 열 수
 * 없으므로 컨트롤러가 같은 판단을 한 번 더 한다 — 그때 쓰는 것이
 * {@link #isPubliclyReadable()} 다. 판단 기준은 여기 하나이고, 시큐리티의 경로 규칙은
 * 그 결과를 경로로 적어 둔 것뿐이다.
 */
public enum ImageAccessPolicy {

    /**
     * 본인 것만 만질 수 있다. 읽기는 비공개.
     *
     * <p>저장 키의 마지막 마디가 <b>본인 유저 번호</b>여야 한다. 키가 경로 변수라 그냥
     * 두면 남의 사진을 덮어쓸 수 있었고, 남의 프로필 사진은 개인 정보라 열지 않는다.
     */
    OWNER {
        @Override
        public void check(User actor, String storageObjectKey) {
            if (actor == null || !actor.getUserId().equals(ownerKeyOf(storageObjectKey))) {
                throw new BusinessException(StorageImageErrorCode.STORAGE_KEY_MISMATCH);
            }
        }
    },

    /**
     * 카탈로그 이미지 — 앨범·아티스트·곡 커버·MuBTI 캐릭터.
     *
     * <p>쓰기·삭제는 배치({@code ROLE_SYSTEM}) 이상만 한다. 읽기는 공개다. 목록의
     * 표지를 {@code <img src>} 로 그리는데 거기에는 {@code Authorization} 헤더를 붙일
     * 수 없고, 키가 앨범·아티스트 이름이나 유형 코드라 개인 정보도 아니다.
     */
    CATALOG {
        @Override
        public void check(User actor, String storageObjectKey) {
            requireBatchOrAbove(actor);
        }
    },

    /**
     * 사람이 보는 그림이 아닌 것 — 스트리밍용 오디오 청크.
     *
     * <p>쓰기·삭제·읽기 셋 다 배치 이상만 한다. 바이트는 원래 nginx 가 스프링을 거치지
     * 않고 스토리지로 흘려보내고({@code X-Accel-Redirect}), 여기로는 메타데이터만 지나간다.
     */
    MACHINE {
        @Override
        public void check(User actor, String storageObjectKey) {
            requireBatchOrAbove(actor);
        }
    };

    /** 배치 프로그램({@code ROLE_SYSTEM}) 과 관리자가 쓸 수 있는 최소 권한. */
    private static final int BATCH_ROLE_LEVEL = UserRole.ROLE_SYSTEM.getLevel();

    /**
     * 업로드·삭제 전에 부른다. 쓸 수 없으면 {@link BusinessException} 을 던진다.
     *
     * <p>던지는 결과는 정책마다 다르다 — 남의 자리면 403({@code MUPID_STORAGE_KEY_MISMATCH}),
     * 권한이 모자라면 403({@code MUPID_FORBIDDEN}) 이다. 상태 코드를 호출부가 정하지
     * 않는 것은 이 저장소의 규약이다 ({@code error-code-enum-owns-http-status}).
     *
     * @param actor            지금 요청을 보낸 사람. 없으면(비로그인) 통과하지 못한다.
     * @param storageObjectKey 저장 키 전체({@code image/{카테고리}/{키}}).
     */
    public abstract void check(User actor, String storageObjectKey);

    /** 읽기가 공개인가. {@code <img src>} 로 열려도 되는 종류인가 하는 뜻이다. */
    public boolean isPubliclyReadable() {
        return this == CATALOG;
    }

    /** 이 카테고리가 쓰기·삭제에 요구하는 최소 권한. {@code OWNER} 는 그 사람 자신이라 없다. */
    public String describeWriteRequirement() {
        return this == OWNER ? "본인" : "ROLE_SYSTEM 이상";
    }

    /**
     * 저장 키의 마지막 마디를 유저 번호로 본다.
     *
     * <p>{@code image/profile/15} 이면 {@code 15} 다. 숫자가 아니면 {@code null} 이고,
     * 그때 {@code actor.getUserId().equals(null)} 이 false 라 어차피 거부된다.
     */
    private static Integer ownerKeyOf(String storageObjectKey) {
        if (storageObjectKey == null) {
            return null;
        }
        String[] parts = storageObjectKey.split("/");
        try {
            return Integer.valueOf(parts[parts.length - 1]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 배치 이상인가.
     *
     * <p>{@code hasRole('SYSTEM')} 대신 {@link UserRole#getLevel()} 을 보는 것은,
     * 액세스 토큰이 아니라 <b>DB 에서 읽은 유저</b>를 보기 때문이다. 토큰의 role 은
     * 발급 시점에 굳어 있으므로 승격된 사람이 재발급 전까지 옛 권한으로 판정된다.
     */
    private static void requireBatchOrAbove(User actor) {
        if (actor == null || actor.getRole() == null
            || actor.getRole().getLevel() < BATCH_ROLE_LEVEL) {
            throw new BusinessException(StorageImageErrorCode.STORAGE_WRITE_FORBIDDEN);
        }
    }

}
