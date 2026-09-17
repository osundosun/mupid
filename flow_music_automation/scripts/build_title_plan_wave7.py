import json, glob, collections, re, io, sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

mapping = {
    # --- expanse: instrumental_atmospheric_post_rock category, 4/20 songs said "Expanse" (20%) ---
    'T000352': 'Distant Lines',
    'T000368': 'Ripples of the Void',
}

print('총 매핑:', len(mapping))

rows = []
for fp in glob.glob('downloads/metadata/*.json'):
    d = json.load(open(fp, encoding='utf-8'))
    rows.append({'track_id': d['track_id'], 'title': d.get('flow_title'), 'category': d.get('requested', {}).get('category')})

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

by_cat = collections.defaultdict(list)
for r in rows:
    by_cat[r['category']].append(mapping.get(r['track_id'], r['title']))

watch_words = ['expanse', 'ripples', 'distant', 'void', 'lines']
print()
print('카테고리 내부 단어 비중 (변경 후 시뮬레이션, instrumental_atmospheric_post_rock):')
titles = by_cat['instrumental_atmospheric_post_rock']
n = len(titles)
wc = collections.Counter()
for t in titles:
    for w in re.findall(r"[A-Za-z']+", t):
        wc[w.lower()] += 1
for w in watch_words:
    if wc[w] > 0:
        print(f'  {w}={wc[w]} ({wc[w]/n*100:.0f}%)')

words = collections.Counter()
for r in rows:
    title = mapping.get(r['track_id'], r['title'])
    for w in re.findall(r"[A-Za-z']+", title):
        words[w.lower()] += 1

print()
print('전역 단어 빈도 (변경 후 시뮬레이션):')
for w in ['expanse', 'ripples', 'distant', 'void', 'lines']:
    print(f'  {w}: {words[w]}')

json.dump(mapping, open('title_rework_plan_wave7.json', 'w', encoding='utf-8'), indent=2, ensure_ascii=False)
print()
print('title_rework_plan_wave7.json 저장 완료')
