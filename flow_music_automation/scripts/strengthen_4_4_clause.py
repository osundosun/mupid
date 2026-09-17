import json
import os
import shutil
from datetime import datetime, timezone

PROMPTS_PATH = 'prompts.jsonl'
TARGET_IDS = {'P0794', 'P0947'}

OLD = 'Strict 4/4 throughout;'
NEW = 'Strict 4/4 throughout only -- absolutely never 3/4, 6/8, or any waltz/triple-meter feel;'


def main():
    with open(PROMPTS_PATH, encoding='utf-8') as f:
        lines = f.readlines()
    recs = [json.loads(l) for l in lines]

    ts = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    backup_path = f'{PROMPTS_PATH}.bak_before_strengthen_4_4_{ts}'
    shutil.copy2(PROMPTS_PATH, backup_path)
    print(f'Backed up {PROMPTS_PATH} -> {backup_path}')

    changed = 0
    for r in recs:
        if r['prompt_id'] in TARGET_IDS:
            if OLD not in r['prompt']:
                raise SystemExit(f"ERROR: expected substring not found in {r['prompt_id']}")
            before = r['prompt']
            r['prompt'] = r['prompt'].replace(OLD, NEW, 1)
            assert r['prompt'] != before
            changed += 1
            print(f"[OK] {r['prompt_id']}: clause strengthened")

    assert changed == len(TARGET_IDS), f'expected {len(TARGET_IDS)} changes, got {changed}'

    tmp_path = PROMPTS_PATH + '.tmp'
    with open(tmp_path, 'w', encoding='utf-8') as f:
        for r in recs:
            f.write(json.dumps(r, ensure_ascii=False) + '\n')
    os.replace(tmp_path, PROMPTS_PATH)

    print(f'Applied {changed} prompt edits to {PROMPTS_PATH}.')


if __name__ == '__main__':
    main()
