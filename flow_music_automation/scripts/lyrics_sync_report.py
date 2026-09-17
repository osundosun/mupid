"""downloads/lyrics_sync/*.json 결과를 집계해서 트랙별/언어별 정렬 품질을 요약.

메타데이터 병합(apply_lyrics_sync_to_metadata.py) 전에 반드시 먼저 돌려서
전체 커버리지와 이상치를 확인할 것.
"""
import json
import statistics
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent
SYNC_DIR = PROJECT / "downloads" / "lyrics_sync"
META_DIR = PROJECT / "downloads" / "metadata"


def main():
    files = sorted(SYNC_DIR.glob("T*.json"))
    print(f"총 결과 파일: {len(files)}개\n")

    errors = []
    per_track = []
    lang_stats = {}

    for f in files:
        d = json.loads(f.read_text(encoding="utf-8"))
        track_id = d["track_id"]
        if "error" in d:
            errors.append((track_id, d["error"]))
            continue

        meta = json.loads((META_DIR / f"{track_id}.json").read_text(encoding="utf-8"))
        lang = meta.get("requested", {}).get("language") or "unknown"

        real_lines = [ln for ln in d["lines"] if not ln["is_empty_section"]]
        aligned = [ln for ln in real_lines if ln["start_sec"] is not None]
        ratios = [ln["match_ratio"] for ln in aligned if ln["match_ratio"] is not None]

        coverage = len(aligned) / len(real_lines) if real_lines else None
        avg_ratio = statistics.mean(ratios) if ratios else None

        per_track.append({"track_id": track_id, "lang": lang, "coverage": coverage, "avg_ratio": avg_ratio, "n_lines": len(real_lines)})
        lang_stats.setdefault(lang, {"coverage": [], "avg_ratio": []})
        if coverage is not None:
            lang_stats[lang]["coverage"].append(coverage)
        if avg_ratio is not None:
            lang_stats[lang]["avg_ratio"].append(avg_ratio)

    print("=== 언어별 요약 ===")
    for lang, s in sorted(lang_stats.items()):
        n = len(s["coverage"])
        cov = statistics.mean(s["coverage"]) if s["coverage"] else 0
        ratio = statistics.mean(s["avg_ratio"]) if s["avg_ratio"] else 0
        print(f"{lang:10s} n={n:4d}  평균 커버리지={cov*100:5.1f}%  평균 match_ratio={ratio*100:5.1f}%")

    if errors:
        print(f"\n=== 오류 트랙 {len(errors)}건 ===")
        for tid, err in errors:
            print(f"  {tid}: {err}")

    per_track.sort(key=lambda x: (x["coverage"] if x["coverage"] is not None else -1))
    print("\n=== 커버리지 낮은 순 20곡 (재검토 후보) ===")
    for row in per_track[:20]:
        cov = f"{row['coverage']*100:.0f}%" if row["coverage"] is not None else "N/A"
        ratio = f"{row['avg_ratio']*100:.0f}%" if row["avg_ratio"] is not None else "N/A"
        print(f"  {row['track_id']} ({row['lang']:8s}) coverage={cov:>5} avg_ratio={ratio:>5} lines={row['n_lines']}")

    (PROJECT / "logs" / "lyrics_sync_report.json").write_text(
        json.dumps({"per_track": per_track, "errors": errors}, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"\n전체 데이터: logs/lyrics_sync_report.json")


if __name__ == "__main__":
    main()
