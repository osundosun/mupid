import json, io, sys, re, shutil
from datetime import datetime, timezone

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

PROMPTS_PATH = 'prompts.jsonl'

with open(PROMPTS_PATH, encoding='utf-8') as f:
    lines = f.readlines()
recs = [json.loads(l) for l in lines]
assert len(recs) == 1000, f'expected 1000 existing prompts, got {len(recs)}'
by_id = {r['prompt_id']: r for r in recs}

# category -> (korean_source_ids, english_source_ids), in the order decided with the user
SELECTION = {
    'contemporary_pop':               (['P0020', 'P0030', 'P0040'], ['P0040', 'P0050', 'P0060']),
    'dance_pop':                       (['P0070', 'P0080'],          ['P0090', 'P0100']),
    'synthpop':                        (['P0110', 'P0120'],          ['P0120', 'P0130']),
    'indie_pop':                       (['P0150', 'P0160'],          ['P0160', 'P0170']),
    'city_pop':                        (['P0180', 'P0190'],          ['P0190', 'P0200']),
    'pop_ballad':                      (['P0210', 'P0220'],          ['P0220', 'P0230']),
    'poprock':                         (['P0240', 'P0250'],          ['P0250', 'P0260']),
    'alternative_rock':                (['P0270', 'P0280'],          ['P0280', 'P0290']),
    'modern_trap_hiphop':              (['P0470'],                   ['P0470']),
    'singing_rap_melodic_hiphop':      (['P0490', 'P0500'],          ['P0490', 'P0500']),
}

TRAILING_LYRICS_SENTENCE_RE = re.compile(r'\s*Use English lyrics and natural English vocal phrasing\.')


def transform(base_prompt_text, target_lang_word, drop_trailing_lyrics_sentence):
    text = re.sub(r'\bJapanese\b', target_lang_word, base_prompt_text)
    if drop_trailing_lyrics_sentence:
        text = TRAILING_LYRICS_SENTENCE_RE.sub('', text)
    return text


new_records = []
seq = 1001

# Korean set: P1001-P1020
for genre, (ko_ids, _) in SELECTION.items():
    for src_id in ko_ids:
        src = by_id[src_id]
        new_records.append({
            'prompt_id': f'P{seq}',
            'batch_id': f'B{100 + ((seq - 1001) // 10) + 1}',
            'batch_position': ((seq - 1001) % 10) + 1,
            'category': f'mixed_korean_{genre}',
            'vocal_type': 'mixed',
            'language': 'korean',
            'requested_bpm_range': src['requested_bpm_range'],
            'prompt': transform(src['prompt'], 'Korean', drop_trailing_lyrics_sentence=False),
            '_source_prompt_id': src_id,
        })
        seq += 1

# English set: P1021-P1040
for genre, (_, en_ids) in SELECTION.items():
    for src_id in en_ids:
        src = by_id[src_id]
        new_records.append({
            'prompt_id': f'P{seq}',
            'batch_id': f'B{100 + ((seq - 1001) // 10) + 1}',
            'batch_position': ((seq - 1001) % 10) + 1,
            'category': f'mixed_english_{genre}',
            'vocal_type': 'mixed',
            'language': 'english',
            'requested_bpm_range': src['requested_bpm_range'],
            'prompt': transform(src['prompt'], 'English', drop_trailing_lyrics_sentence=True),
            '_source_prompt_id': src_id,
        })
        seq += 1

print('생성된 신규 레코드 수:', len(new_records))
assert len(new_records) == 40
assert seq == 1041

# --- validation ---
new_ids = [r['prompt_id'] for r in new_records]
assert len(new_ids) == len(set(new_ids)), 'duplicate new prompt_id'
assert new_ids[0] == 'P1001' and new_ids[-1] == 'P1040'

existing_ids = set(by_id.keys())
assert not (set(new_ids) & existing_ids), 'new id collides with existing prompt_id'

# no leftover "Japanese" word in any new prompt
leftover = [r['prompt_id'] for r in new_records if re.search(r'\bJapanese\b', r['prompt'])]
print('Japanese 단어 잔존:', leftover)

# Korean set must contain the reinforcement sentence; English set must not
ko_missing = [r['prompt_id'] for r in new_records if r['language'] == 'korean' and 'Use Korean lyrics and natural Korean vocal phrasing.' not in r['prompt']]
en_has_sentence = [r['prompt_id'] for r in new_records if r['language'] == 'english' and 'Use English lyrics' in r['prompt']]
print('한국어 세트 중 보강 문장 누락:', ko_missing)
print('영어 세트 중 보강 문장 잔존(삭제 실패):', en_has_sentence)

# exact duplicate prompt text among new + existing?
all_prompt_texts = [r['prompt'] for r in recs] + [r['prompt'] for r in new_records]
dup_count = len(all_prompt_texts) - len(set(all_prompt_texts))
print('전체(기존+신규) 프롬프트 텍스트 완전중복 개수:', dup_count)

# category naming sanity
cats = sorted(set(r['category'] for r in new_records))
print()
print('신규 카테고리 목록:')
for c in cats:
    print(' ', c)

print()
print('=== 예시: 한국어 세트 (P1001, source', new_records[0]['_source_prompt_id'], ') ===')
print(new_records[0]['prompt'])
print()
en_example = next(r for r in new_records if r['language'] == 'english')
print('=== 예시: 영어 세트 (', en_example['prompt_id'], ', source', en_example['_source_prompt_id'], ') ===')
print(en_example['prompt'])

for r in new_records:
    del r['_source_prompt_id']

json.dump(new_records, open('mixed_lang_expansion_plan.json', 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
print()
print('mixed_lang_expansion_plan.json 저장 완료 (40건)')
