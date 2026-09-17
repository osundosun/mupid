import json, glob, collections, re, io, sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

mapping = {
    # --- funk: instrumental_funk category, 11/20 songs said "Funk" (55%) ---
    'T000731': 'Electric Boogie Impulse',
    'T000734': 'Sunlit Soul Strut',
    'T000735': 'Backbeat Circuit',
    'T000737': 'Radiant Bounce Strut',
    'T000739': 'Boogie Café',
    'T000742': 'Corner Soul Cadence',
    'T000743': 'Luminous Backbeat Sashay',
    'T000744': 'Power Bounce 122',
    'T000745': 'Kitchen Soul Beat',
    'T000746': 'Gleam Backbeat Pocket',
    'T000748': 'Boogie Line 119',

    # --- chamber: instrumental_chamber_classical category, 5/10 songs said "Chamber" (50%) ---
    'T000991': 'Quartet Study in Hope',
    'T000992': 'Salon Reflections',
    'T000993': 'Woodwind Recital',
    'T000997': 'Compact Ensemble',
    'T001000': 'Melancholic Sonata Reflection',

    # --- acoustic: instrumental_acoustic_chill category, 4/10 songs said "Acoustic" (40%) ---
    # T000703 'Acoustic Ease' kept as category anchor (1/10 = 10%, within threshold)
    'T000704': 'Amber Organic Drift',
    'T000707': 'Ember Stripped Unwind',
    'T000710': 'Golden Bare Repose',

    # --- bgm: suffix/label removal (same treatment as Take/Option tags) ---
    'T000811': 'Eclipse Action Warfront',
    'T000817': 'Epic Reckoning',
    'T000819': 'Battle Cry',
    'T000884': 'Golden Meadows',
    'T000890': 'Snug Quest',
    'T000891': 'Ember Glow',
    'T000893': 'Radiant Breeze',
    'T000894': 'Homely Route',
    'T000895': 'Beam & Calm',

    # --- neo_soul: instrumental_neo_soul category, 2/10 songs said "Soul" (20%) ---
    'T000433': 'Cafe Interlude',
    # --- neo_soul: same category, 2/10 songs said "Velvet" (20%), pre-existing/unrelated to funk-chamber-acoustic-bgm scope ---
    'T000439': 'Plush Espresso',
}

print('총 매핑:', len(mapping))

rows = []
for fp in glob.glob('downloads/metadata/*.json'):
    d = json.load(open(fp, encoding='utf-8'))
    rows.append({'track_id': d['track_id'], 'title': d.get('flow_title'), 'category': d.get('requested', {}).get('category')})

existing_ids = {r['track_id'] for r in rows}
missing = [tid for tid in mapping if tid not in existing_ids]
print('존재하지 않는 track_id:', missing)

# exact duplicate check across whole dataset after applying
existing = collections.Counter(r['title'] for r in rows if r['track_id'] not in mapping)
final = collections.Counter(existing)
for tid, new_t in mapping.items():
    final[new_t] += 1
dups = {t: c for t, c in final.items() if c > 1}
print('예상 완전중복:', len(dups), dups)

internal_dup = {t: c for t, c in collections.Counter(mapping.values()).items() if c > 1}
print('새 제목끼리 내부중복:', internal_dup)

# category-internal concentration check for the 4 target words + new replacement words
by_cat = collections.defaultdict(list)
for r in rows:
    title = mapping.get(r['track_id'], r['title'])
    by_cat[r['category']].append(title)

watch_words = ['funk', 'chamber', 'acoustic', 'bgm', 'boogie', 'soul', 'backbeat', 'bounce',
               'quartet', 'salon', 'recital', 'sonata', 'ensemble', 'organic', 'stripped', 'bare',
               'velvet', 'interlude', 'plush']
print()
print('카테고리 내부 단어 비중 (변경 후 시뮬레이션):')
for cat, titles in by_cat.items():
    n = len(titles)
    wc = collections.Counter()
    for t in titles:
        for w in re.findall(r"[A-Za-z']+", t):
            wc[w.lower()] += 1
    for w in watch_words:
        if wc[w] > 0:
            pct = wc[w] / n * 100
            flag = ' <-- OVER 15%' if pct > 15 and n >= 5 else ''
            print(f'  {cat} ({n}곡): {w}={wc[w]} ({pct:.0f}%){flag}')

# global word frequency recheck
words = collections.Counter()
for r in rows:
    title = mapping.get(r['track_id'], r['title'])
    for w in re.findall(r"[A-Za-z']+", title):
        words[w.lower()] += 1

print()
print('전역 단어 빈도 (변경 후 시뮬레이션):')
for w in ['funk', 'chamber', 'acoustic', 'bgm', 'boogie', 'soul', 'backbeat', 'bounce',
          'quartet', 'salon', 'recital', 'sonata', 'organic', 'stripped', 'bare', 'cry',
          'pocket', 'strut', 'ensemble', 'velvet', 'interlude', 'plush']:
    print(f'  {w}: {words[w]}')

json.dump(mapping, open('title_rework_plan_wave6.json', 'w', encoding='utf-8'), indent=2, ensure_ascii=False)
print()
print('title_rework_plan_wave6.json 저장 완료')
