import json
import os
import shutil
import sys
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
METADATA_DIR = os.path.join(ROOT, 'downloads', 'metadata')
PLAN_PATH = os.path.join(ROOT, 'title_rework_plan_wave6.json')
LOG_PATH = os.path.join(ROOT, 'logs', 'title_rework_log.jsonl')

METHOD = 'wave6_genre_label_declustering'


def main():
    with open(PLAN_PATH, encoding='utf-8') as f:
        plan = json.load(f)

    ts = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    backup_dir = os.path.join(ROOT, 'downloads', f'metadata.bak_before_wave6_{ts}')
    print(f'Backing up {METADATA_DIR} -> {backup_dir}')
    shutil.copytree(METADATA_DIR, backup_dir)

    before_files = sorted(os.listdir(METADATA_DIR))
    assert len(before_files) == 1000, f'expected 1000 files before apply, got {len(before_files)}'

    log_entries = []
    changed = 0
    for track_id, new_title in plan.items():
        fp = os.path.join(METADATA_DIR, f'{track_id}.json')
        if not os.path.exists(fp):
            raise SystemExit(f'ERROR: {fp} not found')

        with open(fp, encoding='utf-8') as f:
            data = json.load(f)

        old_title = data.get('flow_title', '')
        if old_title == new_title:
            print(f'SKIP {track_id}: already equals planned title')
            continue

        data['flow_title'] = new_title

        tmp_fp = fp + '.tmp'
        with open(tmp_fp, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        os.replace(tmp_fp, fp)

        log_entries.append({
            'ts': datetime.now(timezone.utc).isoformat(),
            'track_id': track_id,
            'prompt_id': data.get('prompt_id'),
            'old_title': old_title,
            'new_title': new_title,
            'anchor': None,
            'method': METHOD,
        })
        changed += 1

    with open(LOG_PATH, 'a', encoding='utf-8') as f:
        for entry in log_entries:
            f.write(json.dumps(entry, ensure_ascii=False) + '\n')

    print(f'Applied {changed} title changes (plan had {len(plan)} entries).')
    print(f'Backup saved at: {backup_dir}')


if __name__ == '__main__':
    main()
