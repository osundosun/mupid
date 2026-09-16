package com.osundosun.mupid.api.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
// Boot 4 에서 패키지가 옮겨졌다. 3.x 의 `boot.test.autoconfigure.web.servlet` 이 아니다.
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osundosun.mupid.api.common.enumerate.Selector;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.repository.MusicRepository;
import com.osundosun.mupid.api.playlist.entity.Playlist;
import com.osundosun.mupid.api.playlist.entity.PlaylistTrack;
import com.osundosun.mupid.api.playlist.entity.PlaylistTrack.PlaylistTrackId;
import com.osundosun.mupid.api.queue.dto.PlayQueueDocument;
import com.osundosun.mupid.api.queue.repository.PlayQueueRepository;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserGender;
import com.osundosun.mupid.api.user.enumerate.UserRole;
import com.osundosun.mupid.api.user.enumerate.UserStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 재생 큐 API 를 HTTP 로 끝까지 지나가며 확인한다 — 순서 조정 · 담기 · 빼기 ·
 * 플레이리스트 전체 재생 · 중복 담기.
 *
 * 규칙 계산 자체는 `PlayQueueRuleTest` 가 Spring 없이 본다. 여기서 보는 것은
 * **그 규칙이 컨트롤러·직렬화·Redis 를 거쳐도 그대로인가** 다.
 *
 * MySQL 은 테스트마다 롤백되지만 **Redis 는 롤백되지 않는다.** 큐 키를 앞뒤로
 * 직접 지운다 — 안 그러면 앞 테스트가 남긴 큐 위에서 다음 테스트가 돈다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PlayQueueApiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MusicRepository musicRepository;

    @Autowired
    private PlayQueueRepository playQueueRepository;

    @PersistenceContext
    private EntityManager em;

    private User me;
    private List<Integer> music;

    @BeforeEach
    void setUp() {
        me = persistUser();
        em.flush();
        playQueueRepository.delete(me.getUserId());

        // 카탈로그에 실제로 있는 곡을 쓴다. 큐는 없는 곡을 받지 않는다.
        music = musicRepository.findAll().stream()
            .filter(it -> !Boolean.TRUE.equals(it.getIsDeleted()))
            .map(Music::getMusicId)
            .sorted()
            .limit(4)
            .toList();
        assertEquals(4, music.size(), "이 테스트는 카탈로그에 곡이 4개 이상 있어야 돈다");
    }

    @AfterEach
    void tearDown() {
        playQueueRepository.delete(me.getUserId());
    }

    @Test
    @DisplayName("순서 조정: 보낸 순서 그대로 저장되고 판본이 오른다")
    void reordersQueue() throws Exception {
        JsonNode first = replace(List.of(music.get(0), music.get(1), music.get(2)), music.get(0), 0);
        assertEquals(1, first.get("revision").asInt());
        assertEquals(music.subList(0, 3), trackIds(first));

        // 뒤집어 보낸다. 화면에서 손잡이를 끌어 옮긴 것과 같은 요청이다.
        List<Integer> reversed = List.of(music.get(2), music.get(1), music.get(0));
        JsonNode second = replace(reversed, music.get(0), 1);
        assertEquals(2, second.get("revision").asInt());
        assertEquals(reversed, trackIds(second));

        // 다시 읽어도 그 순서다. 화면 상태가 아니라 계정에 남았다는 뜻이다.
        assertEquals(reversed, trackIds(queue()));
    }

    @Test
    @DisplayName("순서 조정: 낡은 판본으로 보내면 409 이고 아무것도 바뀌지 않는다")
    void rejectsStaleReorder() throws Exception {
        replace(List.of(music.get(0), music.get(1)), music.get(0), 0);

        mockMvc.perform(auth(put("/api/v1/user/queue"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(java.util.Map.of(
                    "music_ids", List.of(music.get(1), music.get(0)),
                    "current_music_id", music.get(0),
                    "revision", 0))))
            .andExpect(status().isConflict());

        assertEquals(List.of(music.get(0), music.get(1)), trackIds(queue()));
    }

    @Test
    @DisplayName("담기: 재생 중인 곡 바로 아래로 들어간다")
    void addsBelowCurrent() throws Exception {
        replace(List.of(music.get(0), music.get(1), music.get(2)), music.get(1), 0);

        JsonNode body = addTrack(music.get(3));
        assertFalse(body.get("duplicated").asBoolean());
        assertTrue(body.get("evicted_music_id").isNull());

        // 재생 중인 곡이 1번 자리(music[1])이므로 새 곡은 그 다음이다.
        assertEquals(
            List.of(music.get(0), music.get(1), music.get(3), music.get(2)),
            trackIds(body.get("queue")));
    }

    @Test
    @DisplayName("중복 담기: 200 으로 duplicated 를 주고 순서도 판본도 그대로다")
    void addingTwiceIsNotAnError() throws Exception {
        replace(List.of(music.get(0), music.get(1)), music.get(0), 0);
        int revisionBefore = queue().get("revision").asInt();

        JsonNode body = addTrack(music.get(1));

        assertTrue(body.get("duplicated").asBoolean());
        assertEquals(List.of(music.get(0), music.get(1)), trackIds(body.get("queue")));
        // 아무것도 하지 않았으면 판본도 그대로여야 한다. 오르면 다른 기기가
        // 서버가 바뀐 줄 알고 자기 상태를 버린다.
        assertEquals(revisionBefore, body.get("queue").get("revision").asInt());
    }

    @Test
    @DisplayName("빼기: 그 곡만 빠지고, 담겨 있지 않으면 404 다")
    void removesTrack() throws Exception {
        replace(List.of(music.get(0), music.get(1), music.get(2)), music.get(0), 0);

        JsonNode body = json(mockMvc.perform(
                auth(delete("/api/v1/user/queue/track/" + music.get(1))))
            .andExpect(status().isOk()));
        assertEquals(List.of(music.get(0), music.get(2)), trackIds(body));

        // 방금 뺀 곡을 또 빼면 404 다. 화면과 서버가 어긋났다는 뜻이라 조용히 넘기지 않는다.
        mockMvc.perform(auth(delete("/api/v1/user/queue/track/" + music.get(1))))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("플레이리스트 전체 재생: 큐가 수록곡으로 바뀌고 첫 곡이 재생 대상이 된다")
    void replacesQueueFromPlaylist() throws Exception {
        // 큐에 엉뚱한 곡을 채워 둔다. 전체 재생은 '덧붙이기'가 아니라 '갈아끼우기'다.
        replace(List.of(music.get(3)), music.get(3), 0);

        Playlist playlist = persistPlaylist(List.of(music.get(0), music.get(1)));
        em.flush();

        JsonNode body = json(mockMvc.perform(
                auth(post("/api/v1/user/queue/playlist/" + playlist.getPlaylistId())))
            .andExpect(status().isOk()));

        assertEquals(List.of(music.get(0), music.get(1)), trackIds(body));
        assertEquals(music.get(0).intValue(), body.get("current_music_id").asInt());
        // 원래 큐에 있던 곡은 남아 있지 않다.
        assertFalse(trackIds(body).contains(music.get(3)));
    }

    @Test
    @DisplayName("남의 플레이리스트로는 큐를 바꿀 수 없다")
    void refusesAnotherUsersPlaylist() throws Exception {
        User stranger = persistUser();
        em.persist(stranger);
        Playlist theirs = Playlist.builder().user(stranger).title("남의 목록").build();
        em.persist(theirs);
        em.flush();

        mockMvc.perform(auth(post("/api/v1/user/queue/playlist/" + theirs.getPlaylistId())))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("담은 적이 없으면 빈 큐(판본 0)가 온다 — 404 가 아니다")
    void emptyQueueIsNotMissing() throws Exception {
        JsonNode body = queue();
        assertEquals(0, body.get("revision").asInt());
        assertTrue(trackIds(body).isEmpty());
        assertEquals(PlayQueueDocument.LIMIT, body.get("limit").asInt());
    }

    @Test
    @DisplayName("큐는 사용자별로 갈린다")
    void queueIsPerUser() throws Exception {
        User stranger = persistUser();
        em.flush();
        playQueueRepository.delete(stranger.getUserId());

        replace(List.of(music.get(0), music.get(1)), music.get(0), 0);

        JsonNode theirs = json(mockMvc.perform(get("/api/v1/user/queue")
                .with(csrf())
                .with(authentication(
                    new UsernamePasswordAuthenticationToken(stranger, null, List.of())))));
        assertTrue(trackIds(theirs).isEmpty());
        assertNotEquals(trackIds(queue()), trackIds(theirs));

        playQueueRepository.delete(stranger.getUserId());
    }

    // ─────────────────────────────────────────────────────────────

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder builder) {
        return builder
            .with(csrf())
            .with(authentication(new UsernamePasswordAuthenticationToken(me, null, List.of())));
    }

    private JsonNode queue() throws Exception {
        return json(mockMvc.perform(auth(get("/api/v1/user/queue"))).andExpect(status().isOk()));
    }

    private JsonNode replace(List<Integer> ids, Integer currentMusicId, int revision) throws Exception {
        return json(mockMvc.perform(auth(put("/api/v1/user/queue"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(java.util.Map.of(
                    "music_ids", ids,
                    "current_music_id", currentMusicId,
                    "revision", revision))))
            .andExpect(status().isOk()));
    }

    private JsonNode addTrack(int musicId) throws Exception {
        return json(mockMvc.perform(auth(post("/api/v1/user/queue/track"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"music_id\":%d}".formatted(musicId)))
            .andExpect(status().isOk()));
    }

    /** `BaseResponse` 의 `data` 만 꺼낸다. */
    private JsonNode json(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return MAPPER
            .readTree(actions.andReturn().getResponse().getContentAsString())
            .get("data");
    }

    private List<Integer> trackIds(JsonNode queue) {
        return queue.get("tracks").findValuesAsText("music_id").stream()
            .map(Integer::valueOf)
            .toList();
    }

    private Playlist persistPlaylist(List<Integer> musicIds) {
        Playlist playlist = Playlist.builder().user(me).title("검증용 목록").build();
        em.persist(playlist);
        em.flush();

        int order = 1;
        for (int musicId : musicIds) {
            Music track = musicRepository.findById(musicId).orElseThrow();
            em.persist(PlaylistTrack.builder()
                .playlistTrackId(new PlaylistTrackId(playlist.getPlaylistId(), order++))
                .playlist(playlist)
                .music(track)
                .selector(Selector.USER)
                .user(me)
                .build());
        }
        playlist.setMusicCount(musicIds.size());
        return playlist;
    }

    private User persistUser() {
        User user = User.builder()
            .email("queue-%s@mupid.test".formatted(UUID.randomUUID()))
            .password("$2a$10$abcdefghijklmnopqrstuv")
            .nickname("테스터")
            .birthYear(1999)
            .gender(UserGender.ETC)
            .role(UserRole.ROLE_USER)
            .status(UserStatus.ACTIVE)
            .build();
        em.persist(user);
        return user;
    }

}
