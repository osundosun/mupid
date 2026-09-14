import test from 'node:test';
import assert from 'node:assert/strict';
import {
  tracks,
  initialMoments,
  initialPlaylists,
  formatTime,
  getSongSectionAt,
  deriveChatRecommendations,
} from '../lib/mupid-data.ts';
import { demoTracks } from './fixtures/tracks.mjs';

test('time labels safely format elapsed seconds', () => {
  assert.equal(formatTime(0), '00:00');
  assert.equal(formatTime(148.9), '02:28');
  assert.equal(formatTime(-1), '00:00');
  assert.equal(formatTime(NaN), '00:00');
});

// 화면에 보이는 곡은 서버에서 온다. 제품 코드에는 예시 곡이 없어야 한다.
test('the app ships no demo songs', () => {
  assert.deepEqual(tracks, []);
  assert.deepEqual(initialPlaylists, []);
  assert.deepEqual(initialMoments, []);
});

test('every track has a continuous bounded song form and valid moment', () => {
  for (const track of demoTracks) {
    assert.equal(track.sections[0].start, 0);
    assert.equal(track.sections.at(-1).end, track.duration);
    assert.ok(track.moment >= 0 && track.moment < track.duration);
    track.sections.forEach((section, index) => {
      assert.ok(section.start < section.end);
      if (index) assert.equal(track.sections[index - 1].end, section.start);
      assert.equal(getSongSectionAt(track, section.start), section);
    });
    assert.equal(
      getSongSectionAt(track, track.duration),
      track.sections.at(-1),
    );
    assert.equal(getSongSectionAt(track, -1), track.sections[0]);
    assert.equal(getSongSectionAt(track, NaN), track.sections[0]);
  }
});

test('calmer requests prefer a calm track relative to the current track', () => {
  const answer = deriveChatRecommendations(
    '이 곡보다 더 잔잔하게',
    demoTracks[0],
    demoTracks,
  );
  assert.equal(answer.tracks[0].mood, '잔잔한');
  assert.equal(answer.tracks.length, 3);
  assert.ok(!answer.tracks.some((t) => t.id === demoTracks[0].id));
});

test('energy requests change the recommendation direction', () => {
  const calm = deriveChatRecommendations('잔잔하게', demoTracks[0], demoTracks);
  const energetic = deriveChatRecommendations(
    '더 신나는 음악',
    demoTracks[0],
    demoTracks,
  );
  assert.notEqual(calm.tracks[0].id, energetic.tracks[0].id);
  assert.match(energetic.text, /에너지/);
});

test('recommendations never reintroduce excluded tracks or invent fallback results', () => {
  assert.deepEqual(
    deriveChatRecommendations('추천해줘', demoTracks[0], []).tracks,
    [],
  );
  assert.deepEqual(
    deriveChatRecommendations('추천해줘', demoTracks[0], [demoTracks[0]]).tracks,
    [],
  );
  assert.deepEqual(
    deriveChatRecommendations('잔잔하게', demoTracks[0], [demoTracks[5]]).tracks.map(
      (t) => t.id,
    ),
    [demoTracks[5].id],
  );
});

test('saved example moments and playlists reference playable catalog tracks', () => {
  for (const moment of initialMoments) {
    assert.ok(tracks.includes(moment.track));
    assert.ok(
      moment.start >= 0 &&
        moment.end <= moment.track.duration &&
        moment.end > moment.start,
    );
  }
  for (const playlist of initialPlaylists) {
    assert.ok(playlist.tracks.every((t) => tracks.includes(t)));
    assert.equal(
      new Set(playlist.tracks.map((t) => t.id)).size,
      playlist.tracks.length,
    );
  }
});
