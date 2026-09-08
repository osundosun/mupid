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

test('time labels safely format elapsed seconds', () => {
  assert.equal(formatTime(0), '00:00');
  assert.equal(formatTime(148.9), '02:28');
  assert.equal(formatTime(-1), '00:00');
  assert.equal(formatTime(NaN), '00:00');
});

test('every demo track has a continuous bounded song form and valid moment', () => {
  for (const track of tracks) {
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
    tracks[0],
    tracks,
  );
  assert.equal(answer.tracks[0].mood, '잔잔한');
  assert.equal(answer.tracks.length, 3);
  assert.ok(!answer.tracks.some((t) => t.id === tracks[0].id));
});

test('energy requests change the recommendation direction', () => {
  const calm = deriveChatRecommendations('잔잔하게', tracks[0], tracks);
  const energetic = deriveChatRecommendations(
    '더 신나는 음악',
    tracks[0],
    tracks,
  );
  assert.notEqual(calm.tracks[0].id, energetic.tracks[0].id);
  assert.match(energetic.text, /에너지/);
});

test('recommendations never reintroduce excluded tracks or invent fallback results', () => {
  assert.deepEqual(
    deriveChatRecommendations('추천해줘', tracks[0], []).tracks,
    [],
  );
  assert.deepEqual(
    deriveChatRecommendations('추천해줘', tracks[0], [tracks[0]]).tracks,
    [],
  );
  assert.deepEqual(
    deriveChatRecommendations('잔잔하게', tracks[0], [tracks[5]]).tracks.map(
      (t) => t.id,
    ),
    [tracks[5].id],
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
