package com.osundosun.mupid.api.admin.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.osundosun.mupid.api.admin.dto.AdminAttribute;
import com.osundosun.mupid.api.admin.dto.AdminField;
import com.osundosun.mupid.api.admin.dto.AdminResourceMeta;
import com.osundosun.mupid.api.admin.dto.AdminThumbnail;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;
import com.osundosun.mupid.api.storage.service.StorageImageService;

import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;

/**
 * FK 자리에 함께 그릴 값 — **대표 컬럼 값**(`_labels`)과 **작은 이미지**(`_thumbnails`) — 을
 * 목록 질의 **한 번에** 가져오는 투영 계획.
 *
 * <h2>왜 별도 클래스인가</h2>
 *
 * 행마다 "이 FK 가 가리키는 것의 이름/사진" 을 따로 물어보면 그대로 N+1 이 된다. 목록이
 * 수백 행일 때 행 수만큼 질의가 붙어 화면이 죽는다. 그래서 값은 <b>행을 가져오는 그 질의</b>
 * 안에서 같이 가져온다 — 이미 있는 LEFT JOIN 을 그대로 타고 올라가기만 하므로 **질의 수가
 * 늘지 않는다** (목록 1 + `COUNT` 1 은 그대로다).
 *
 * <p>여기서 만드는 것은 투영 목록뿐이고, 결과를 읽어 행에 붙이는 일도 여기서 한다. 목록과
 * 단건 조회가 같은 규칙을 쓰게 하려는 것이다 — 한쪽만 대표값이 나오면 화면이 갈라진다.
 *
 * <h2>무엇을 투영하는가 (자원 이름에 박지 않는다)</h2>
 *
 * 연관 필드 하나마다:
 *
 * <ol>
 *   <li><b>FK 번호</b> — 언제나. 요구는 "대표값과 번호를 함께" 이므로 번호는 남는다.</li>
 *   <li><b>대표 컬럼 값</b> — 대상 자원에 대표 컬럼이 정해져 있을 때만
 *       (`AdminField.representativeField`).</li>
 *   <li><b>썸네일 재료</b> — 그 대상의 이미지가 이미지 파일인지 판단할 수 있는 값
 *       (종류·삭제 여부·카테고리)과 파일 번호. 대상이 곧 파일이면 파일 번호는 1번을
 *       그대로 쓴다. 대상이 파일을 <b>들고 있는</b> 자원이면(앨범의 아티스트 사진)
 *       그 이미지까지 한 단계 더 들어간다.</li>
 * </ol>
 *
 * <p><b>깊이는 2단계로 끊는다.</b> "회원의 아티스트의 앨범의…" 처럼 끝없이 들어가면
 * 질의가 자원 구조를 통째로 훑는다. 요구가 있는 곳(앨범 행의 아티스트 사진)이 여기까지다.
 *
 * <p>대상이 파일인지는 클래스({@link ObjectStorageFile})로 본다. 카테고리별 공개 여부는
 * 파일 행의 `category` 에서 읽는다 — {@code ImageAccessPolicy} 가 그 판단의 주인이고,
 * 화면이 카테고리 이름을 몰라도 되도록 결과에 값으로 담아 보낸다.
 */
final class AdminRowProjection {

    /**
     * `multiselect` 의 0번은 루트 엔티티다. 우리가 붙이는 투영은 1번부터라, 자리 계산을
     * 여기서 한 칸 밀어 둔다. 이 상수와 {@link Slice} 의 자리들이 서로 맞물려 돌아간다.
     */
    private static final int ROOT_OFFSET = 1;

    /**
     * 연관 필드 하나를 읽는 법.
     *
     * @param fieldName          행 `Map` 의 키
     * @param labelOffset        대표값이 있는 자리. 없으면 -1
     * @param thumbTypeOffset    이미지 종류가 있는 자리. 없으면 -1
     * @param thumbDeletedOffset 삭제 여부가 있는 자리. 없으면 -1
     * @param thumbCategoryOffset 카테고리가 있는 자리. 없으면 -1
     * @param thumbFileIdOffset  썸네일 주소를 만들 파일 번호의 자리. 없으면 -1
     * @param foreignKeyOffset   FK 번호(행의 원래 값) 자리. 언제나 있다.
     */
    private record Slice(
        String fieldName,
        int foreignKeyOffset,
        int labelOffset,
        int thumbTypeOffset,
        int thumbDeletedOffset,
        int thumbCategoryOffset,
        int thumbFileIdOffset
    ) {
    }

    private final List<Selection<?>> selections;
    private final List<Slice> slices;

    private AdminRowProjection(List<Selection<?>> selections, List<Slice> slices) {
        this.selections = selections;
        this.slices = slices;
    }

    /**
     * 자원 하나의 투영 계획을 세운다.
     *
     * <p>연관 필드가 없으면 빈 계획이 되고, 그때 질의는 예전과 똑같이 돈다.
     */
    static AdminRowProjection of(
        Root<?> root,
        AdminResourceMeta meta,
        AdminResourceRegistry registry
    ) {
        List<Selection<?>> selections = new ArrayList<>();
        List<Slice> slices = new ArrayList<>();

        for (AdminAttribute attribute : meta.getAttributes().values()) {
            AdminField field = attribute.getField();
            if (field.sensitive() || attribute.getRelationType() == null) {
                continue;
            }

            AdminResourceMeta target = registry.findByType(attribute.getRelationType());
            if (target == null) {
                // 관리 대상이 아닌 엔티티를 가리키는 연관. 번호만 나가고 끝난다.
                continue;
            }

            // 자리(offset)는 지금까지 쌓인 투영 수에서 나온다. `slice` 가 자기 선택을
            // 여기 `selections` 에 직접 붙이므로 순서가 곧 자리다.
            Slice slice = slice(root, attribute, field, target,
                selections.size() + ROOT_OFFSET, selections);
            slices.add(slice);
        }

        return new AdminRowProjection(List.copyOf(selections), List.copyOf(slices));
    }

    /**
     * 질의에 넘길 투영 목록. **루트 엔티티를 포함하지 않는다** — 부르는 쪽이 맨 앞에 붙인다.
     */
    List<Selection<?>> selections() {
        return selections;
    }

    /**
     * 결과 한 줄에서 FK 부가값을 꺼내 행에 붙인다. 붙일 것이 없으면 키를 만들지 않는다 —
     * 화면이 "없음" 과 "빈 표" 를 구분하지 않아도 되게.
     *
     * @param tuple `multiselect` 결과. 0번은 루트 엔티티라 이 클래스의 자리는 1부터다.
     * @param row   여기에 `_labels`·`_thumbnails` 를 붙인다.
     */
    void attach(Tuple tuple, Map<String, Object> row) {
        Map<String, Object> labels = new LinkedHashMap<>();
        Map<String, Object> thumbnails = new LinkedHashMap<>();

        for (Slice slice : slices) {
            // 대표값 자리가 없으면(-1) 읽지 않는다. `field.representativeField()` 가 null 인
            // 연관이 그렇다 — 대표 컬럼이 정해지지 않은 자원(`REPRESENTATIVE_FIELDS` 에 없음)
            // 이거나 대상이 관리 대상이 아닐 때다. **이것은 오류가 아니라 정상 상태다**:
            // 그때 화면은 번호만 그린다(387 때 합의된 동작).
            //
            // 예전에는 이 검사 없이 그대로 `tuple.get(-1)` 을 불렀고, Hibernate 의
            // `TupleImpl.get` 이 배열을 그대로 인덱싱해
            // `ArrayIndexOutOfBoundsException: Index -1 out of bounds` 로 **목록 조회 자체가
            // 500** 이 됐다. 대표 필드가 없는 자원(구간-분위기 등 복합키 자원)이 전부 탔다.
            String label = text(at(tuple, slice.labelOffset()));
            if (label != null) {
                labels.put(slice.fieldName(), label);
            }

            AdminThumbnail thumbnail = thumbnail(tuple, slice);
            if (thumbnail != null) {
                thumbnails.put(slice.fieldName(), thumbnail);
            }
        }

        if (!labels.isEmpty()) {
            row.put("_labels", labels);
        }
        if (!thumbnails.isEmpty()) {
            row.put("_thumbnails", thumbnails);
        }
    }

    // ------------------------------------------------------------------ 만들기

    /**
     * 연관 필드 하나의 투영을 만든다.
     *
     * <p>고른 선택을 `selections` 에 **직접 붙인다.** 따로 모아 돌려주면 부르는 쪽이
     * 붙이는 것을 잊었을 때 조용히 아무것도 안 오고, 화면은 대표값 없이 번호만 그린다 —
     * 실제로 그렇게 만들었다가 이 테스트에서 걸렸다.
     *
     * @param start      이 연관의 첫 선택이 들어갈 자리
     * @param selections 여기에 자기 선택을 순서대로 붙인다
     */
    private static Slice slice(
        Root<?> root,
        AdminAttribute attribute,
        AdminField field,
        AdminResourceMeta target,
        int start,
        List<Selection<?>> selections
    ) {
        String name = attribute.getField().name();

        // 이미 있는 LEFT JOIN 을 그대로 쓴다. INNER 로 붙이면 FK 가 null 인 행이 조용히
        // 사라진다 — 목록 조회가 원래 쓰던 규칙과 같아야 한다.
        Join<?, ?> join = root.join(name, JoinType.LEFT);

        int offset = start;
        int foreignKeyOffset = offset++;
        selections.add(join.get(target.getIdAttributeName()));

        int labelOffset = -1;
        if (field.representativeField() != null) {
            labelOffset = offset++;
            selections.add(join.get(field.representativeField()));
        }

        int typeOffset = -1;
        int deletedOffset = -1;
        int categoryOffset = -1;
        int fileIdOffset = -1;

        if (ObjectStorageFile.class.equals(target.getJavaType())) {
            // 대상이 곧 파일이다 (회원·아티스트·앨범의 imageFile). 조인한 행이 파일 행이므로
            // 종류·삭제 여부·카테고리를 바로 읽는다. 파일 번호는 FK 값을 그대로 쓴다.
            typeOffset = offset++;
            deletedOffset = offset++;
            categoryOffset = offset++;
            selections.add(join.get("fileType"));
            selections.add(join.get("isDeleted"));
            selections.add(join.get("category"));
            fileIdOffset = foreignKeyOffset;
        } else {
            // 대상이 파일을 들고 있는 자원이다 (앨범의 아티스트 사진). 한 단계만 더 들어간다.
            AdminAttribute imageAttribute = imageAttributeOf(target);
            if (imageAttribute != null) {
                // Join 은 From 이고 Path 다 — 여기서 한 단계 더 들어갈 수 있다.
                Join<?, ?> imageFile = join.join(imageAttribute.name(), JoinType.LEFT);
                typeOffset = offset++;
                deletedOffset = offset++;
                categoryOffset = offset++;
                fileIdOffset = offset++;
                selections.add(imageFile.get("fileType"));
                selections.add(imageFile.get("isDeleted"));
                selections.add(imageFile.get("category"));
                selections.add(imageFile.get("fileId"));
            }
        }

        return new Slice(name, foreignKeyOffset, labelOffset,
            typeOffset, deletedOffset, categoryOffset, fileIdOffset);
    }

    /**
     * 자원이 들고 있는 **이미지** 연관 필드. 보통 `imageFile` 하나다.
     *
     * <p>여러 개면 이름이 `imageFile` 인 것을 고른다 — 나머지(`Chunk.file` 처럼)는 사람이
     * 보는 그림이 아니라 청크 바이트라 화면에 작게 그릴 것이 아니다.
     */
    private static AdminAttribute imageAttributeOf(AdminResourceMeta meta) {
        AdminAttribute fallback = null;
        for (AdminAttribute candidate : meta.getAttributes().values()) {
            if (!ObjectStorageFile.class.equals(candidate.getRelationType())) {
                continue;
            }
            if ("imageFile".equals(candidate.name())) {
                return candidate;
            }
            if (fallback == null) {
                fallback = candidate;
            }
        }
        return fallback;
    }

    // -------------------------------------------------------------------- 읽기

    /**
     * 썸네일 하나. **이미지가 아니거나 지워진 파일이면 null** 이다.
     *
     * <p>{@code FileType.IMG} 를 보는 것은 {@code StorageImageService.getImageData} 와 같은
     * 검사다. 여기서 안 거르면 화면이 음원 청크 주소를 {@code <img src>} 에 걸고, 그 요청은
     * 400 을 받아 조용히 깨진 그림 자리가 된다.
     */
    private static AdminThumbnail thumbnail(Tuple tuple, Slice slice) {
        if (slice.thumbFileIdOffset() < 0) {
            return null;
        }

        Object fileId = at(tuple, slice.thumbFileIdOffset());
        if (fileId == null) {
            return null;
        }
        if (!(at(tuple, slice.thumbTypeOffset()) instanceof FileType type) || type != FileType.IMG) {
            return null;
        }
        if (Boolean.TRUE.equals(at(tuple, slice.thumbDeletedOffset()))) {
            return null;
        }

        FileCategory category = at(tuple, slice.thumbCategoryOffset()) instanceof FileCategory value
            ? value
            : null;

        return new AdminThumbnail(
            StorageImageService.imageUrl(((Number) fileId).intValue()),
            category == null || !category.getAccessPolicy().isPubliclyReadable()
        );
    }

    /**
     * 자리 하나를 읽는다. **자리가 없으면(-1) 읽지 않고 null 을 준다.**
     *
     * <p>`-1` 은 {@link Slice} 에서 "이 값은 투영하지 않았다" 는 뜻이고, 화면이 그 필드의
     * 부가값을 안 그리면 그만인 <b>정상 상태</b>다. 그런데 Hibernate 의 `TupleImpl.get` 은
     * 자리를 검사하지 않고 배열을 그대로 인덱싱해서, `-1` 을 넘기면
     * `ArrayIndexOutOfBoundsException: Index -1 out of bounds` 로 **조회 자체가 죽는다**.
     *
     * <p>그래서 `tuple.get(...)` 을 직접 부르지 않고 전부 이 문을 지나게 한다. 자리 계산이
     * 늘어나도 같은 부류가 다시 나지 않는다 — 실제로 `attach()` 가 `labelOffset` 을 이 검사
     * 없이 읽어 대표 필드가 없는 자원(구간-분위기 등)의 목록 조회가 500 이 났다(380-A 회귀).
     */
    private static Object at(Tuple tuple, int offset) {
        return offset < 0 ? null : tuple.get(offset);
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Enum<?> constant) {
            return constant.name();
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

}
