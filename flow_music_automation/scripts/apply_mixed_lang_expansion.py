import json
import os
import shutil
from datetime import datetime, timezone

PROMPTS_PATH = 'prompts.jsonl'
PLAN_PATH = 'mixed_lang_expansion_plan.json'


def main():
    with open(PLAN_PATH, encoding='utf-8') as f:
        new_records = json.load(f)
    assert len(new_records) == 40

    with open(PROMPTS_PATH, encoding='utf-8') as f:
        existing_lines = f.readlines()
    assert len(existing_lines) == 1000, f'expected 1000 existing lines, got {len(existing_lines)}'

    ts = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    backup_path = f'{PROMPTS_PATH}.bak_before_mixed_lang_expansion_{ts}'
    shutil.copy2(PROMPTS_PATH, backup_path)
    print(f'Backed up {PROMPTS_PATH} -> {backup_path}')

    tmp_path = PROMPTS_PATH + '.tmp'
    with open(tmp_path, 'w', encoding='utf-8') as f:
        f.writelines(existing_lines)
        for rec in new_records:
            f.write(json.dumps(rec, ensure_ascii=False) + '\n')
    os.replace(tmp_path, PROMPTS_PATH)

    with open(PROMPTS_PATH, encoding='utf-8') as f:
        final_lines = f.readlines()
    print(f'prompts.jsonl now has {len(final_lines)} lines (expected 1040)')


if __name__ == '__main__':
    main()
