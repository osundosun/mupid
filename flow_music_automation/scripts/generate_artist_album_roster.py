# -*- coding: utf-8 -*-
"""ARTIST_ALBUM_ROSTER.md 재생성. artists.json/albums.json/downloads/metadata 기준.
아티스트는 artists.json의 순서(artist_key 알파벳순) 그대로, 앨범은 album_key 순."""
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def main():
    arts = json.load(open(os.path.join(ROOT, "artists.json"), encoding="utf-8"))
    albums = json.load(open(os.path.join(ROOT, "albums.json"), encoding="utf-8"))
    meta_cache = {}

    def title_of(tid):
        if tid not in meta_cache:
            with open(os.path.join(ROOT, "downloads", "metadata", f"{tid}.json"), encoding="utf-8") as f:
                meta_cache[tid] = json.load(f)
        return meta_cache[tid]["flow_title"]

    albums_by_artist = {}
    for al in albums:
        albums_by_artist.setdefault(al["artist_key"], []).append(al)
    for k in albums_by_artist:
        albums_by_artist[k].sort(key=lambda a: a["album_key"])

    total_artists = len(arts)
    total_albums = len(albums)
    total_tracks = sum(a["track_count"] for a in arts)

    lines = []
    lines.append("# 가상 아티스트 / 앨범 / 수록곡 전체 목록 (v5 - 보컬 재편/성별전환 반영)")
    lines.append("")
    lines.append(f"생성 기준: artists.json(아티스트 {total_artists}명) / albums.json(앨범 {total_albums}개) / downloads/metadata(트랙 {total_tracks}곡)")
    lines.append("")
    lines.append("---")
    lines.append("")

    LANG_LABEL = {"korean": "한국어", "english": "영어", "japanese": "일본어"}
    VOCAL_LABEL = {"female": "여성", "male": "남성", "mixed": "혼성"}

    for a in arts:
        lang_label = LANG_LABEL.get(a["language"], "인스트루멘탈")
        vocal_label = VOCAL_LABEL.get(a["vocal_type"], "인스트루멘탈")
        lines.append(f"## {a['artist_name']}")
        lines.append(f"- 분류: {lang_label} · {vocal_label} · 총 {a['track_count']}곡")
        lines.append(f"- 장르: {', '.join(a['genres'])}")
        lines.append("")
        for al in albums_by_artist.get(a["artist_key"], []):
            lines.append(f"### 앨범: {al['album_title']} ({al['track_count']}곡)")
            for tid in al["track_ids"]:
                lines.append(f"- `{tid}` {title_of(tid)}")
            lines.append("")
        lines.append("---")
        lines.append("")

    out_path = os.path.join(ROOT, "ARTIST_ALBUM_ROSTER.md")
    with open(out_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print("wrote", out_path)


if __name__ == "__main__":
    main()
