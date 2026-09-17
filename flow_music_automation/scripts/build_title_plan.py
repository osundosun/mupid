import json, glob, sys, io, collections, re
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

pmap = {}
with open('prompts.jsonl', encoding='utf-8') as f:
    for line in f:
        d = json.loads(line)
        pmap[d['prompt_id']] = d

flagged = json.load(open('title_rework_candidates.json', encoding='utf-8'))
high_risk = set(json.load(open('title_rework_excluded_vocal.json', encoding='utf-8')))
candidates = [r for r in flagged if r['track_id'] not in high_risk]

def num(tid): return int(tid.replace('T', ''))
candidates.sort(key=lambda r: num(r['track_id']))

all_rows = []
for fp in sorted(glob.glob('downloads/metadata/*.json')):
    d = json.load(open(fp, encoding='utf-8'))
    all_rows.append({'track_id': d['track_id'], 'title': d.get('flow_title')})
existing_titles = collections.Counter(r['title'] for r in all_rows)

en_priority = ['echoes', 'cafe', 'shadows', 'warm', 'midnight', 'horizon', 'drift', 'bright', 'dreamy', 'dark', 'neon']
kr_priority = ['바람', '국도', '노을']
jp_priority = ['茜色', '風']

pools = {
    'midnight': (6, ['Nocturne', 'After Hours', 'Small Hours', 'Witching Hour', '3AM', 'Late Night', 'Moonlit Hour']),
    'echoes':   (8, ['Reverb', 'Resonance', 'Afterglow', 'Remnants', 'Traces', 'Reverberation']),
    'cafe':     (6, ['Bistro', 'Brew', 'Corner', 'Kitchen', 'Diner', 'Nook']),
    'warm':     (8, ['Cozy', 'Amber', 'Ember', 'Golden', 'Tender', 'Hearth']),
    'horizon':  (6, ['Skyline', 'Distance', 'Vista', 'Edge', 'Frontier', 'Farline']),
    'drift':    (8, ['Glide', 'Wander', 'Sway', 'Current', 'Coast', 'Float']),
    'shadows':  (5, ['Silhouettes', 'Dusk', 'Veil', 'Obscura', 'Umbra']),
    'bright':   (5, ['Radiant', 'Vivid', 'Luminous', 'Gleam', 'Sunlit']),
    'dreamy':   (5, ['Hazy', 'Wistful', 'Gauzy', 'Reverie', 'Misty']),
    'dark':     (5, ['Shadowed', 'Noir', 'Obsidian', 'Eclipse', 'Onyx']),
    'neon':     (5, ['Chrome', 'Electric', 'Circuit', 'Voltage', 'Glow']),
    '바람': (3, ['미풍', '실바람', '산들바람']),
    '국도': (1, ['가도', '신작로']),
    '노을': (2, ['황혼', '저녁놀']),
    '茜色': (1, ['夕焼け', '黄昏色']),
    '風':   (4, ['そよ風', '海風', '夜風']),
}


def primary_anchor(reasons):
    anchors = [x.split(':')[1] for x in reasons if x.startswith('ANCHOR:')]
    if not anchors:
        return None
    for p in en_priority + kr_priority + jp_priority:
        if p in anchors:
            return p
    return anchors[0]


groups = collections.defaultdict(list)
no_anchor = []
for r in candidates:
    a = primary_anchor(r['reasons'])
    if a:
        groups[a].append(r)
    else:
        no_anchor.append(r)

plan = []


def register_title(t):
    existing_titles[t] += 1


def make_unique(orig_title, anchor_word, pool_word, isEn):
    if isEn:
        pat = re.compile(r'\b' + re.escape(anchor_word) + r'\b', re.I)
        new_t = pat.sub(pool_word, orig_title, count=1)
    else:
        new_t = orig_title.replace(anchor_word, pool_word, 1)
    return new_t


for anchor, items in groups.items():
    keep, pool = pools[anchor]
    isEn = anchor in en_priority
    items_sorted = sorted(items, key=lambda r: num(r['track_id']))
    kept_raw = items_sorted[:keep]
    rest = items_sorted[keep:]

    seen_titles = set()
    kept = []
    for r in kept_raw:
        if r['title'] in seen_titles:
            rest.insert(0, r)
        else:
            seen_titles.add(r['title'])
            kept.append(r)
    rest.sort(key=lambda r: num(r['track_id']))

    pool_idx = 0
    for r in kept:
        register_title(r['title'])
    for r in rest:
        tries = 0
        new_title = None
        while tries < len(pool) * 3:
            pw = pool[pool_idx % len(pool)]
            pool_idx += 1
            candidate_title = make_unique(r['title'], anchor, pw, isEn)
            if existing_titles[candidate_title] == 0:
                new_title = candidate_title
                break
            tries += 1
        if new_title is None:
            new_title = candidate_title
        register_title(new_title)
        plan.append({
            'track_id': r['track_id'], 'prompt_id': r['prompt_id'],
            'old_title': r['title'], 'new_title': new_title,
            'anchor': anchor, 'method': 'anchor_pool_rotation',
        })

paraphrase = {
    'Where We Left Off': 'Back Where We Started',
    'Break The Line': 'Cross The Line',
    'Take The Crown': 'Claim The Crown',
    '골목길 산책': '뒷골목 걸음',
    'Fading Horizons': 'Vanishing Horizons',
    'Night Drive': ['Nighttime Cruise', 'After Dark Drive', 'Evening Drive'],
    'Night Drive (Take 2)': 'Late Drive (Take 2)',
    'Reese Pulse': 'Reese Current',
    'Drifting Frequencies': 'Wandering Frequencies',
    'Chillhop Stroll': 'Lo-Fi Wander',
    'Melancholic Waves': 'Wistful Waves',
    'Beacon of Hope': 'Light of Hope',
    'Final Strike': ['Decisive Blow', 'Last Stand'],
    "Hero's Journey": ["Hero's Path", "Hero's Trial", "Champion's Road", "Valiant Quest"],
    'Realm of Valor': 'Realm of Courage',
    'Acoustic Reflection': 'Acoustic Musing',
    'Whispers of Hope': 'Murmurs of Hope',
    'Hopeful Horizons': ['Promising Horizons', 'Optimistic Skyline'],
    'Cozy Steps': 'Gentle Steps',
    'Quiet Reflections': ['Silent Musings', 'Still Reflections'],
}

na_groups = collections.defaultdict(list)
for r in no_anchor:
    na_groups[r['title']].append(r)

for title, items in na_groups.items():
    items_sorted = sorted(items, key=lambda r: num(r['track_id']))
    keep_item = items_sorted[0]
    register_title(keep_item['title'])
    rest = items_sorted[1:]
    alt = paraphrase[title]
    alts = alt if isinstance(alt, list) else [alt]
    for i, r in enumerate(rest):
        new_title = alts[i % len(alts)]
        if len(rest) > len(alts) and i >= len(alts):
            new_title = f'{new_title} ({i + 1})'
        if existing_titles[new_title] > 0:
            new_title = f'{new_title} II'
        register_title(new_title)
        plan.append({
            'track_id': r['track_id'], 'prompt_id': r['prompt_id'],
            'old_title': r['title'], 'new_title': new_title,
            'anchor': None, 'method': 'paraphrase',
        })

json.dump(plan, open('title_rework_plan.json', 'w', encoding='utf-8'), indent=2, ensure_ascii=False)
print('계획 생성 완료:', len(plan), '건')

final_counts = collections.Counter()
changed_ids = {p['track_id'] for p in plan}
for r in all_rows:
    t = r['title']
    if r['track_id'] in changed_ids:
        continue
    final_counts[t] += 1
for p in plan:
    final_counts[p['new_title']] += 1

dups_after = {t: c for t, c in final_counts.items() if c > 1}
print('적용 후 예상 완전중복 제목 수:', len(dups_after), '건 (', sum(dups_after.values()), '곡)')
for t, c in sorted(dups_after.items(), key=lambda x: -x[1])[:20]:
    print(' ', c, repr(t))
