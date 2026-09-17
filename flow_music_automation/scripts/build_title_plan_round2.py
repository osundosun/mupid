import json, glob, sys, io, collections, re, os
from datetime import datetime, timezone
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

flagged = json.load(open('title_rework_candidates.json', encoding='utf-8'))
high_risk_ids = set(json.load(open('title_rework_excluded_vocal.json', encoding='utf-8')))
targets = [r for r in flagged if r['track_id'] in high_risk_ids]

def num(tid): return int(tid.replace('T', ''))
targets.sort(key=lambda r: num(r['track_id']))

# current live titles (post round-1 changes)
all_rows = []
for fp in sorted(glob.glob('downloads/metadata/*.json')):
    d = json.load(open(fp, encoding='utf-8'))
    all_rows.append({'track_id': d['track_id'], 'title': d.get('flow_title')})
existing_titles = collections.Counter(r['title'] for r in all_rows)

en_priority = ['echoes', 'cafe', 'shadows', 'warm', 'midnight', 'horizon', 'drift', 'bright', 'dreamy', 'dark', 'neon']
kr_priority = ['바람', '국도', '노을']
jp_priority = ['茜色', '風']

pools = {
    'midnight': ['Nocturne', 'After Hours', 'Small Hours', 'Witching Hour', '3AM', 'Late Night', 'Moonlit Hour'],
    'echoes':   ['Reverb', 'Resonance', 'Afterglow', 'Remnants', 'Traces', 'Reverberation'],
    'cafe':     ['Bistro', 'Brew', 'Corner', 'Kitchen', 'Diner', 'Nook'],
    'warm':     ['Cozy', 'Amber', 'Ember', 'Golden', 'Tender', 'Hearth'],
    'horizon':  ['Skyline', 'Distance', 'Vista', 'Edge', 'Frontier', 'Farline'],
    'drift':    ['Glide', 'Wander', 'Sway', 'Current', 'Coast', 'Float'],
    'shadows':  ['Silhouettes', 'Dusk', 'Veil', 'Obscura', 'Umbra'],
    'bright':   ['Radiant', 'Vivid', 'Luminous', 'Gleam', 'Sunlit'],
    'dreamy':   ['Hazy', 'Wistful', 'Gauzy', 'Reverie', 'Misty'],
    'dark':     ['Shadowed', 'Noir', 'Obsidian', 'Eclipse', 'Onyx'],
    'neon':     ['Chrome', 'Electric', 'Circuit', 'Voltage', 'Glow'],
    '바람': ['미풍', '실바람', '산들바람'],
    '국도': ['가도', '신작로'],
    '노을': ['황혼', '저녁놀'],
    '茜色': ['夕焼け', '黄昏色'],
    '風':   ['そよ風', '海風', '夜風'],
}


def primary_anchor(reasons):
    anchors = [x.split(':')[1] for x in reasons if x.startswith('ANCHOR:')]
    for p in en_priority + kr_priority + jp_priority:
        if p in anchors:
            return p
    return anchors[0] if anchors else None


def make_unique(orig_title, anchor_word, pool_word, isEn):
    if anchor_word == '風' and '潮風' in orig_title:
        return orig_title.replace('潮風', pool_word, 1)
    if isEn:
        pat = re.compile(r'\b' + re.escape(anchor_word) + r'\b', re.I)
        return pat.sub(pool_word, orig_title, count=1)
    return orig_title.replace(anchor_word, pool_word, 1)


plan = []
for r in targets:
    a = primary_anchor(r['reasons'])
    pool = pools[a]
    isEn = a in en_priority
    new_title = None
    for pw in pool:
        cand = make_unique(r['title'], a, pw, isEn)
        if existing_titles[cand] == 0 and cand != r['title']:
            new_title = cand
            break
    if new_title is None:
        new_title = make_unique(r['title'], a, pool[0], isEn)
    existing_titles[new_title] += 1
    plan.append({
        'track_id': r['track_id'], 'prompt_id': r['prompt_id'],
        'old_title': r['title'], 'new_title': new_title,
        'anchor': a, 'method': 'anchor_pool_rotation_round2',
    })

json.dump(plan, open('title_rework_plan_round2.json', 'w', encoding='utf-8'), indent=2, ensure_ascii=False)
print('2차 계획 생성:', len(plan), '건')
for p in plan:
    print(f"  {p['track_id']}  {p['old_title']!r} -> {p['new_title']!r}  (anchor={p['anchor']})")

# collision check across the whole resulting set
final_counts = collections.Counter()
changed_ids = {p['track_id'] for p in plan}
for r in all_rows:
    if r['track_id'] in changed_ids:
        continue
    final_counts[r['title']] += 1
for p in plan:
    final_counts[p['new_title']] += 1
dups = {t: c for t, c in final_counts.items() if c > 1}
print()
print('적용 후 예상 중복:', len(dups), '건')
