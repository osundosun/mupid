# 0019. 가사는 곡 단위로 통째로 갈아 끼운다

**상태** 채택 · 구현 완료

## 맥락

`music_preprocessing` 배치가 flow_music 카탈로그의 가사를 서버에 올린다. 곡
505개에 23,628줄이다.

쓰기 API 가 줄 하나짜리(`POST /api/v1/lyric`)뿐이라, 배치는 곡마다 이렇게 했다.

```
GET    /api/v1/music/{id}/lyric      기존 줄 조회
DELETE /api/v1/lyric/{lyricId}  × N  한 줄씩 지우기
POST   /api/v1/lyric            × N  한 줄씩 넣기
```

| | 요청 수 |
|---|---|
| 첫 등록 | 505 GET + 23,628 POST = **24,133** |
| 다시 올리기 | 505 GET + 23,628 DELETE + 23,628 POST = **47,761** |

느린 것보다 큰 문제는 **원자성이 없다는 것**이었다. 한 곡의 가사가 수십 개
트랜잭션에 흩어져 있어서, 중간에 끊기면 절반만 올라간 가사가 DB 에 남는다.
배치는 다음 실행에서 지우고 다시 쓰는 것으로 덮었지만, 그 사이 화면은 잘린
가사를 보여 준다.

## 결정

**`PUT /api/v1/music/{musicId}/lyric` 하나로 곡의 가사 전체를 교체한다.**

```json
{ "lines": [ { "text": "…", "time_ms": 0 }, … ] }
→ { "music_id": 1, "deleted": 47, "created": 58 }
```

서비스가 `deleteByMusic_MusicId` → `flush` → `saveAll` 을 한 트랜잭션에서
한다. `OnboardingServiceImpl` 의 재제출 처리와 같은 모양이다.

곁들여 정한 것 넷.

| | 결정 | 이유 |
|---|---|---|
| 경로 | `PUT .../lyric` (`/bulk` 없이) | `GET` 이 이미 그 경로에서 전체를 준다. `PUT` 이 그 대칭이고, 경로에 동작 이름을 넣지 않아도 된다 |
| 빈 목록 | **금지** (`@NotEmpty`) | 교체 의미로는 "전부 지워라" 가 맞지만, 배치의 실수 하나가 곡 가사를 통째로 날린다. 비우는 길은 필요해지면 그때 만든다 |
| 컨트롤러 | `LyricQueryController` → `LyricByMusicController` 로 개명해 합침 | 경로 하나에 컨트롤러 둘은 나중에 헷갈린다. `@SystemRole` 은 클래스가 아니라 `PUT` 메서드에 붙였다 — 조회는 로그인한 누구나 한다 |
| 배치 폴백 | **두지 않음** | 서버가 이 API 를 모르면 그냥 실패한다. 조용히 줄 단위로 내려가면 왜 8분이 걸리는지 아무도 모른다 |

**순서는 서버가 정렬하지 않는다.** 받은 순서 그대로 넣는다. `time_ms` 가 같은
줄이 지금 카탈로그에 164줄 있는데(후렴에 겹쳐 나오는 줄), 조회가
`time_ms`·`lyric_id` 순이라 넣은 순서가 곧 보이는 순서다. 서버가 정렬하면 그
동률이 뒤집힌다.

## 결과

| | 전 | 후 |
|---|---:|---:|
| HTTP 요청 (첫 등록) | 24,133 | **502** |
| HTTP 요청 (다시 올리기) | 47,761 | **502** |
| 트랜잭션 | 23,628 | **502** |
| INSERT 문 | 23,628 | 23,628 |
| 흉내 서버 전체 실행 | 163초 | **6초** |

받아들인 비용.

- **INSERT 문 수는 줄지 않는다.** `Lyric` 의 PK 가 `GenerationType.IDENTITY`
  라 Hibernate 가 JDBC 배치를 끈다. `hibernate.jdbc.batch_size` 를 켜도 이
  엔티티에는 듣지 않는다. 줄어드는 것은 왕복과 트랜잭션이고, 왕복 하나가 수
  ms 인 데 비해 INSERT 하나는 훨씬 싸므로 그쪽이 병목이었다. INSERT 까지
  줄이려면 PK 전략을 바꾸거나 네이티브 멀티로우 INSERT 를 써야 하는데,
  **하지 않기로 했다** — 왕복 47배면 충분하다.
- **덮어쓴다.** 누가 손으로 고쳐 둔 가사가 있으면 배치가 지운다. 줄 단위
  DELETE 로 덮던 때와 같으므로 새로 생긴 위험은 아니지만, 사라진 것은 아니다.
- 배치의 진행률이 줄 단위에서 곡 단위로 거칠어졌다. 502곡이면 ETA 로 충분하다.
- 쓰기 API 가 둘이 됐다. 줄 하나를 손보는 `POST`·`PATCH`·`DELETE` 는 그대로
  두었다 — 사람이 오타 한 줄을 고칠 때 곡 전체를 다시 보내게 할 이유가 없다.

ERD 변경은 없다. 테이블도 컬럼도 그대로다.

## 근거

`api/src/main/java/com/osundosun/mupid/api/music/controller/LyricByMusicController.java`
`api/src/main/java/com/osundosun/mupid/api/music/service/LyricServiceImpl.java`
`api/src/test/java/com/osundosun/mupid/api/music/LyricReplaceTest.java`
`api/docs/Music/가사 일괄 교체.yml`
`music_preprocessing/src/music_preprocessing/lyrics.py`
