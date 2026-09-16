#!/usr/bin/env python3
"""운영 DB 가 코드와 맞는지 대조한다.

`ddl-auto` 가 `none` 이라 엔티티를 고쳐도 DB 는 따라오지 않는다. 어긋나면 배포
직후가 아니라 **그 컬럼을 처음 쓰는 요청에서** 터지므로, 머지 전에 여기서 잡는다.

실제로 이 대조가 없어서 겪은 것들:
  - object_storage_files.category ENUM 에 MUBTI 가 없어 부트스트랩이
    `Data truncated for column 'category'` 로 실패
  - onboarding_survey 가 mood_id/genre_id 인데 엔티티는 mood_code_id/genre_code_id
  - music_section_analysis.external_id 가 varchar(50) 인데 erd.sql 은 varchar(64)

두 방향을 본다.

  1. erd.sql ↔ DB     테이블·컬럼·타입. ENUM 값 추가도 타입 비교에서 걸린다.
  2. 엔티티 ↔ DB      @Table / @Column / @JoinColumn 이 가리키는 것이 DB 에 있는지.

2 번이 더 중요하다 — erd.sql 이 엔티티보다 뒤처진 적이 있고, 런타임에 깨지는 것은
엔티티 쪽이다. 다만 2 번은 이름을 명시한 것만 본다. `@Column` 없이 필드명으로
추론되는 컬럼과 네이티브 쿼리 안의 이름은 확인하지 못한다 — 1 번이 그 빈틈을 메운다.

사용법

  # 노드에서 스키마를 뜬 뒤 저장소가 있는 곳에서 대조한다 (권장)
  #   노드: sudo infra_k8s/deploy/schema-dump.sh > live.tsv
  python3 infra_k8s/deploy/schema-check.py --schema live.tsv

  # kubectl 이 이 클러스터를 보고 있으면 직접 조회한다
  python3 infra_k8s/deploy/schema-check.py --live

차이가 있으면 종료 코드가 1 이다. 파이프라인에서 게이트로 쓸 수 있다.
"""
import argparse
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
ERD = ROOT / "api" / "knowledge" / "domain" / "erd.sql"
ENTITIES = ROOT / "api" / "src" / "main" / "java"

DUMP_SQL = (
    "SELECT table_name, column_name, column_type FROM information_schema.columns "
    "WHERE table_schema='mupid' ORDER BY table_name, ordinal_position"
)


def normalise(value):
    """타입 문자열을 비교할 수 있는 모양으로 만든다.

    ERD 는 `ENUM('A', 'B')`, MySQL 은 `enum('A','B')` 로 준다. 대소문자·공백·
    따옴표만 걷어내면 ENUM 값이 하나라도 다를 때 정확히 걸린다.
    """
    return re.sub(r"\s+", "", value.strip().lower().replace("'", "").replace('"', ""))


def parse_erd(path):
    tables = {}
    source = path.read_text(encoding="utf-8")
    for block in re.finditer(r"CREATE TABLE IF NOT EXISTS `([a-z_]+)` \((.*?)\n\)", source, re.S):
        columns = {}
        for line in block.group(2).splitlines():
            column = re.match(
                r"\s+`([a-z_]+)`\s+(.+?)"
                r"(?:\s+NOT NULL|\s+NULL|\s+DEFAULT|\s+COMMENT|\s+AUTO_INCREMENT|,\s*$|$)",
                line,
            )
            if column:
                columns[column.group(1)] = normalise(column.group(2))
        tables[block.group(1)] = columns
    return tables


def parse_live(text):
    tables = {}
    for line in text.splitlines():
        parts = line.split("\t")
        if len(parts) == 3:
            tables.setdefault(parts[0], {})[parts[1]] = normalise(parts[2])
    return tables


def dump_live():
    """노드에서 직접 조회한다. 비밀번호는 서버의 .env 에서만 읽는다."""
    script = (
        'pw=$(grep -m1 "^MUPID_MYSQL_ROOT_PASSWORD=" /srv/mupid-k8s/.env | cut -d= -f2-); '
        f'kubectl -n mupid exec -i statefulset/mysql -- mysql -uroot -p"$pw" -N -B -e "{DUMP_SQL}"'
    )
    done = subprocess.run(["bash", "-c", script], capture_output=True, text=True)
    if done.returncode:
        raise SystemExit(f"스키마를 읽지 못했다:\n{done.stderr.strip()}")
    return "\n".join(l for l in done.stdout.splitlines() if "Using a password" not in l)


def parse_entities(root):
    """@Table 이 붙은 클래스에서 테이블명과, 이름을 명시한 컬럼을 모은다."""
    found = {}
    for path in root.rglob("*.java"):
        text = path.read_text(encoding="utf-8", errors="replace")
        if "@Entity" not in text:
            continue
        table = re.search(r'@Table\s*\(\s*name\s*=\s*"([a-z_]+)"', text)
        if not table:
            continue
        columns = {
            m.group(1)
            for m in re.finditer(r'@(?:Join)?Column\s*\([^)]*name\s*=\s*"([a-z_]+)"', text)
        }
        found[table.group(1)] = (columns, path.name)
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--schema", type=pathlib.Path, help="덤프한 TSV (table/column/type)")
    source.add_argument("--live", action="store_true", help="kubectl 로 직접 조회")
    # 머지 전에 "이 브랜치는 ALTER 가 필요한가" 를 보려면 브랜치의 erd.sql 을
    # 꺼내 이 옵션으로 준다. 브랜치를 체크아웃하지 않아도 된다.
    #   git show <ref>:api/knowledge/domain/erd.sql > /tmp/erd.sql
    parser.add_argument("--erd", type=pathlib.Path, default=ERD, help="대조할 erd.sql")
    args = parser.parse_args()

    live = parse_live(dump_live() if args.live else args.schema.read_text(encoding="utf-8"))
    if not live:
        raise SystemExit("스키마가 비어 있다. 덤프가 제대로 됐는지 본다.")
    erd = parse_erd(args.erd)

    problems = []

    for table in sorted(set(erd) - set(live)):
        problems.append(f"erd.sql 에 있고 DB 에 없는 테이블: {table}")
    for table in sorted(set(live) - set(erd)):
        problems.append(f"DB 에 있고 erd.sql 에 없는 테이블: {table}")
    for table in sorted(set(erd) & set(live)):
        for column in sorted(set(erd[table]) - set(live[table])):
            problems.append(f"컬럼 없음: {table}.{column}  (erd.sql 에 있다)")
        for column in sorted(set(live[table]) & set(erd[table])):
            if erd[table][column] != live[table][column]:
                problems.append(
                    f"타입 다름: {table}.{column}  DB={live[table][column]} "
                    f"erd.sql={erd[table][column]}"
                )

    entities = parse_entities(ENTITIES)
    for table, (columns, filename) in sorted(entities.items()):
        if table not in live:
            problems.append(f"엔티티가 없는 테이블을 가리킨다: {table}  ({filename})")
            continue
        for column in sorted(columns - set(live[table])):
            problems.append(f"엔티티가 없는 컬럼을 가리킨다: {table}.{column}  ({filename})")

    print(f"DB 테이블 {len(live)}개 / erd.sql {len(erd)}개 / 엔티티 {len(entities)}개")
    if problems:
        print(f"\n차이 {len(problems)}건")
        for problem in problems:
            print(f"  {problem}")
        return 1
    print("차이 없음 — DB 가 erd.sql 과 엔티티 양쪽에 맞다")
    return 0


if __name__ == "__main__":
    sys.exit(main())
