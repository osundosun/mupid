import json, glob, collections, io, sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

mapping = {
    # Take/Option/Alt 태그 제거
    'T000841': 'Rising Warmth',       # 'Echoes of Hope (Option 2)' -> 태그 제거하면 T000786과 충돌해서 재작명
    'T001006': '나란한 시선',          # Take 1 태그만 제거
    'T001008': '같은 보폭',            # Alt Take 태그 제거 + 나란한/발걸음 중복 해소 겸 재작명
    'T001012': '다락방의 음반',         # Take 2 태그만 제거
    'T001040': 'Quiet Space',         # Take 2 태그만 제거

    # 신규 mixed_korean 20곡 내부 반복 해소
    'T001001': '박자를 맞춰',          # '발걸음 맞춰' -> 발걸음 클러스터(1001/1008/1014) 해소
    'T001018': '전력 질주',           # '한계 돌파' 완전중복(1004와) 해소
}

print('총 매핑:', len(mapping))

rows = []
for fp in glob.glob('downloads/metadata/*.json'):
    d = json.load(open(fp, encoding='utf-8'))
    rows.append({'track_id': d['track_id'], 'title': d.get('flow_title')})

existing_ids = {r['track_id'] for r in rows}
missing = [tid for tid in mapping if tid not in existing_ids]
print('존재하지 않는 track_id:', missing)

existing = collections.Counter(r['title'] for r in rows if r['track_id'] not in mapping)
final = collections.Counter(existing)
for tid, new_t in mapping.items():
    final[new_t] += 1
dups = {t: c for t, c in final.items() if c > 1}
print('예상 완전중복:', len(dups), dups)

internal_dup = {t: c for t, c in collections.Counter(mapping.values()).items() if c > 1}
print('새 제목끼리 내부중복:', internal_dup)

json.dump(mapping, open('title_rework_plan_wave8.json', 'w', encoding='utf-8'), indent=2, ensure_ascii=False)
print('title_rework_plan_wave8.json 저장 완료')
