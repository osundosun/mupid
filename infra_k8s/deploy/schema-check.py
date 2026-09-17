#!/usr/bin/env python3
"""운영 DB 가 코드와 맞는지 대조한다.

`ddl-auto` 가 `none` 이라 엔티티를 고쳐도 DB 는 따라오지 않는다. 어긋나면 배포
직후가 아니라 **그 컬럼을 처음 쓰는 요청에서** 터지므로, 머지 전에 여기서 잡는다.

실제로 이 대조가 없어서 겪은 것들:
  - object_storage_files.category ENUM 에 MUBTI 가 없어 부트스트랩이
    `Data truncated for column 'category'` 로 실패
  - onboarding_survey 가 mood_id/genre_id 인데 엔티티는 mood_code_id/genre_code_id
  - music_section_analysis.external_id 가 varchar(50) 인데 erd.sql 은 varchar(64)

세 방향을 본다.

  1. erd.sql ↔ DB     테이블·컬럼·타입·AUTO_INCREMENT·유니크 인덱스.
  2. 엔티티 ↔ DB      @Table / @Column / @JoinColumn 이 가리키는 것이 DB 에 있는지.
  3. erd.sql ↔ 엔티티  `@Id` 에 `@GeneratedValue` 가 있는지 ↔ 그 컬럼에
                      `AUTO_INCREMENT` 가 있는지. **양쪽 다 봐야 한다.**

2 번이 더 중요하다 — erd.sql 이 엔티티보다 뒤처진 적이 있고, 런타임에 깨지는 것은
엔티티 쪽이다. 다만 2 번은 이름을 명시한 것만 본다. `@Column` 없이 필드명으로
추론되는 컬럼과 네이티브 쿼리 안의 이름은 확인하지 못한다 — 1 번이 그 빈틈을 메운다.

## 왜 `extra` 와 인덱스까지 보는가

한동안 `information_schema.columns` 의 `table_name, column_name, column_type` 만
읽었다. 그러면 **`AUTO_INCREMENT` 와 인덱스가 아예 안 보인다** — `column_type` 은
`int` 이고, `AUTO_INCREMENT` 는 `extra` 에 있으며, 인덱스는 다른 표에 있다.
그래서 `music_analysis_instruments.music_section_analysis_id` 의 AUTO_INCREMENT 와
`music_section_analysis.external_id` 의 UNIQUE 가 **정반대여도 "차이 없음"** 이
나왔다. 둘 다 이 저장소가 실제로 밟은 함정이다(아래).

- **`music_analysis_instruments.music_section_analysis_id`** 는 남의 PK 를 가리키는
  FK 자리다. `AUTO_INCREMENT` 가 붙으면 `0` 이 들어올 때 MySQL 이 **새 번호를
  발급한다.** 로컬 재현: `(0, 200)` 을 넣었더니 `(2, 200)` 이 되어 엉뚱한 구간에
  악기가 붙었고 **외래키 검사까지 통과했다.** 떼면 같은 INSERT 가
  `ERROR 1452` 로 시끄럽게 실패한다 — 그게 맞는 동작이다.
- **`external_id` 의 UNIQUE** 는 문자열 UID 와 숫자 id 를 잇는 유일한 근거다.
  없으면 같은 값이 두 구간에 존재할 수 있고 `id_map` 이 어느 쪽인지 알 수 없다.

## 인덱스는 **이름을 비교하지 않는다**

`erd.md` 도 적어 두었듯 `uq_*` 라고 유니크인 것도, `uk_*` 라고 아닌 것도 있다.
이름은 ERD 도구가 붙이는 것이라 재산출할 때마다 바뀐다(`uk_` → `ux_`). 이름까지
비교하면 재산출만으로 가짜 차이가 나서, 이름을 맞추려고 DDL 을 다시 치게 된다.
그래서 **(테이블, 컬럼 집합, 유니크 여부)** 로만 본다.

사용법

  # 노드에서 스키마를 뜬 뒤 저장소가 있는 곳에서 대조한다 (권장)
  #   노드: sudo infra_k8s/deploy/schema-dump.sh > live.tsv
  python3 infra_k8s/deploy/schema-check.py --schema live.tsv

  # kubectl 이 이 클러스터를 보고 있으면 직접 조회한다
  python3 infra_k8s/deploy/schema-check.py --live

  # 브랜치의 erd.sql 로 미리 본다 (체크아웃하지 않아도 된다)
  #   Windows Git Bash 에서는 프로세스 치환 `<(...)` 이 안 먹는다 — 임시 파일로.
  git show <ref>:api/knowledge/domain/erd.sql > /tmp/erd.sql
  python3 infra_k8s/deploy/schema-check.py --schema live.tsv --erd /tmp/erd.sql

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
    "SELECT table_name, column_name, column_type, extra FROM information_schema.columns "
    "WHERE table_schema='mupid' ORDER BY table_name, ordinal_position"
)

# 인덱스는 **묶는 데만 이름을 쓴다.** 결과 판정에는 쓰지 않는다 — 이름은 ERD
# 도구가 붙이는 것이라 재산출할 때마다 바뀐다(`uk_` → `ux_`). 이름까지 비교하면
# 재산출만으로 가짜 차이가 나서, 이름을 맞추려고 DDL 을 다시 치게 된다.
# 그래서 `(테이블, 컬럼 집합, 유니크 여부)` 로만 본다.
#
# **이름 없이 컬럼으로만 묶으면 복합 인덱스가 쪼개진다** — 컬럼마다 한 줄로
# 오므로 `(code_type)` 과 `(code_name)` 이 따로 잡혀 `(code_type, code_name)` 을
# 영영 못 찾는다. 실제로 그렇게 만들어 두었더니 운영에 있는 유니크 7개를
# "없다" 고 잘못 보고했다.
INDEX_DUMP_SQL = (
    "SELECT table_name, index_name, column_name, non_unique "
    "FROM information_schema.statistics "
    "WHERE table_schema='mupid' AND index_name <> 'PRIMARY' "
    "ORDER BY table_name, index_name, seq_in_index"
)


def normalise(value):
    """타입 문자열을 비교할 수 있는 모양으로 만든다.

    ERD 는 `ENUM('A', 'B')`, MySQL 은 `enum('A','B')` 로 준다. 대소문자·공백·
    따옴표만 걷어내면 ENUM 값이 하나라도 다를 때 정확히 걸린다.
    """
    return re.sub(r"\s+", "", value.strip().lower().replace("'", "").replace('"', ""))


def parse_erd(path):
    """erd.sql 에서 테이블별 (컬럼 → 타입, AUTO_INCREMENT 여부) 와 유니크 인덱스를 모은다.

    `CREATE TABLE` 블록은 **괄호 짊이로** 끊는다. `\\n\\)` 로 끝을 찾으면
    `) COMMENT='…';` 로 닫히는 표를 놓쳐 본문이 다음 표까지 이어지고, 그 표에
    없는 컬럼이 있는 것처럼 읽힌다 (실제로 `object_storage_files` 뒤의 표들이
    그렇게 붙어 보였다).
    """
    source = path.read_text(encoding="utf-8")

    tables = {}
    for match in re.finditer(r"CREATE TABLE IF NOT EXISTS `([a-z_]+)` \(", source):
        name, index, depth = match.group(1), match.end(), 1
        while depth:
            char = source[index]
            if char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
            index += 1
        body = source[match.end():index - 1]

        columns = {}
        for line in body.splitlines():
            column = re.match(
                r"\s+`([a-z_]+)`\s+(.+?)(?:,\s*$|$)",
                line,
            )
            if not column:
                continue
            rest = column.group(2)
            # COMMENT 는 타입이 아니다. 잘라 낸 뒤 AUTO_INCREMENT 를 따로 본다.
            rest = re.split(r"\s+COMMENT\s", rest, maxsplit=1)[0]
            auto = "AUTO_INCREMENT" in rest.upper()
            rest = re.sub(r"\s+AUTO_INCREMENT", "", rest, flags=re.I)
            for suffix in ("NOT NULL", "NULL", "DEFAULT"):
                rest = re.split(r"\s+" + suffix + r"\b", rest, maxsplit=1)[0]
            columns[column.group(1)] = (normalise(rest), auto)
        tables[name] = columns

    # 인덱스는 별도 문장이다. 이름은 버리고 (테이블, 컬럼 집합, 유니크) 만 남긴다.
    indexes = set()
    for match in re.finditer(
        r"CREATE (UNIQUE )?INDEX\s+`[^`]+`\s+ON\s+`([a-z_]+)`\s*\(([^)]*)\)", source
    ):
        columns = tuple(sorted(
            c.strip().strip("`") for c in match.group(3).split(",") if c.strip()
        ))
        indexes.add((match.group(2), columns, bool(match.group(1))))
    # 인라인 UNIQUE KEY 도 같은 모양으로 담는다.
    for table, columns in tables.items():
        for match in re.finditer(r"UNIQUE KEY\s+`[^`]+`\s*\(([^)]*)\)", source):
            cols = tuple(sorted(
                c.strip().strip("`") for c in match.group(1).split(",") if c.strip()
            ))
            if cols and set(cols) <= set(columns):
                indexes.add((table, cols, True))
    return tables, indexes


def parse_live(text):
    """덤프 TSV → (테이블별 컬럼 정보, 인덱스 집합).

    두 조회를 한 파일에 담으므로 **첫 칸으로 구분**한다.

    - 컬럼 줄: `<table>\\t<column>\\t<type>\\t<extra>`
    - 인덱스 줄: `I\\t<table>\\t<인덱스키>\\t<column>\\t<non_unique>`

    인덱스는 **한 인덱스가 여러 줄**로 온다(복합 인덱스는 컬럼마다 한 줄).
    두 번째 칸(인덱스키)으로 묶어 (테이블, 컬럼집합, 유니크) 로 만든다 —
    여기서 뭉쳐야 `statistics` 를 그대로 준 `--schema` 파일과 `--live` 가 같은
    모양이 된다.

    컬럼의 `extra` 에 `AUTO_INCREMENT` 가 들어 있다 — `column_type` 에는 없다.
    """
    tables = {}
    grouped = {}
    for line in text.splitlines():
        parts = line.split("\t")
        if parts[0] == "I":
            if len(parts) != 5:
                continue
            table, index_key, column, non_unique = parts[1], parts[2], parts[3], parts[4]
            grouped.setdefault((table, index_key), []).append((non_unique, column))
            continue
        if len(parts) != 4:
            continue
        tables.setdefault(parts[0], {})[parts[1]] = (
            normalise(parts[2]), parts[3].strip().lower() == "auto_increment")

    indexes = set()
    for (table, _key), rows in grouped.items():
        columns = tuple(sorted(column for _n, column in rows))
        if columns:
            indexes.add((table, columns, rows[0][0].strip() == "0"))
    return tables, indexes


def parse_index_dump(text):
    """`information_schema.statistics` 덤프 → (테이블, 컬럼집합, 유니크) 집합.

    같은 인덱스의 컬럼이 여러 줄로 오므로 `(테이블, 인덱스명)` 으로 묶는다.
    인덱스명은 결과에 쓰지 않는다 — 이름이 아니라 모양만 본다.
    """
    grouped = {}
    for line in text.splitlines():
        parts = line.split("\t")
        if len(parts) != 4:
            continue
        table, index_name, column, non_unique = parts
        grouped.setdefault((table, index_name), []).append((int(non_unique), column))
    indexes = set()
    for (table, _name), rows in grouped.items():
        columns = tuple(sorted(column for _n, column in rows))
        indexes.add((table, columns, rows[0][0] == 0))
    return indexes


def dump_live():
    """노드에서 직접 조회한다. 비밀번호는 서버의 .env 에서만 읽는다."""
    exec_mysql = (
        'kubectl -n mupid exec -i statefulset/mysql -- '
        'mysql -uroot -p"$pw" -N -B -e'
    )
    script = (
        'pw=$(grep -m1 "^MUPID_MYSQL_ROOT_PASSWORD=" /srv/mupid-k8s/.env | cut -d= -f2-); '
        f'{exec_mysql} "{DUMP_SQL}"; '
        # 인덱스 줄은 첫 칸에 `I` 를 붙여 컬럼 줄과 구분한다. 두 번째 칸은
        # **묶음 키**로만 쓰는 인덱스명이다 (게이트는 이 값을 보지 않는다).
        # 이름으로 묶어야 복합 인덱스가 쪼개지지 않는다.
        f'{exec_mysql} "{INDEX_DUMP_SQL}" | '
        """awk -F'\\t' 'BEGIN{OFS="\\t"}{print "I",$1,$2,$3,$4}'"""
    )
    done = subprocess.run(["bash", "-c", script], capture_output=True, text=True)
    if done.returncode:
        raise SystemExit(f"스키마를 읽지 못했다:\n{done.stderr.strip()}")
    return "\n".join(l for l in done.stdout.splitlines() if "Using a password" not in l)


def parse_entities(root):
    """`@Table` 이 붙은 클래스에서 테이블명·명시한 컬럼·자동 생성 id 를 모은다."""
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
        # `@Id` 와 `@GeneratedValue` 가 붙은 필드의 컬럼명. 두 애너테이션이 같은
        # 필드에 있으면 자동 생성이다. `@Column(name=…)` 이 없으면 필드명을
        # 스네이크로 바꾼 것으로 본다 (`musicSectionAnalysisId` → …).
        generated = set()
        for block in re.split(r"\n\s*(?=@)", text):
            if "@Id" not in block or "@GeneratedValue" not in block:
                continue
            named = re.search(r'@Column\s*\([^)]*name\s*=\s*"([a-z_]+)"', block)
            if named:
                generated.add(named.group(1))
                continue
            field = re.search(r"\bprivate\s+[\w<>.\[\]]+\s+([a-zA-Z][\w]*)\s*;", block)
            if field:
                generated.add(re.sub(r"(?<!^)(?=[A-Z])", "_", field.group(1)).lower())
        found[table.group(1)] = (columns, path.name, generated)
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--schema", type=pathlib.Path, help="덤프한 TSV (table/column/type/extra)")
    source.add_argument("--live", action="store_true", help="kubectl 로 직접 조회")
    # 머지 전에 "이 브랜치는 ALTER 가 필요한가" 를 보려면 브랜치의 erd.sql 을
    # 꺼내 이 옵션으로 준다. 브랜치를 체크아웃하지 않아도 된다.
    #   git show <ref>:api/knowledge/domain/erd.sql > /tmp/erd.sql
    parser.add_argument("--erd", type=pathlib.Path, default=ERD, help="대조할 erd.sql")
    parser.add_argument(
        "--unique-only", action="store_true",
        help="인덱스 차이 중 일반 인덱스는 무시한다 (erd.sql 은 유니크만 적는다)")
    args = parser.parse_args()

    if args.live:
        live_text = dump_live()
    else:
        live_text = args.schema.read_text(encoding="utf-8")
    live, live_indexes = parse_live(live_text)
    if not live:
        raise SystemExit("스키마가 비어 있다. 덤프가 제대로 됐는지 본다.")
    erd, erd_indexes = parse_erd(args.erd)

    problems = []

    for table in sorted(set(erd) - set(live)):
        problems.append(f"erd.sql 에 있고 DB 에 없는 테이블: {table}")
    for table in sorted(set(live) - set(erd)):
        problems.append(f"DB 에 있고 erd.sql 에 없는 테이블: {table}")
    for table in sorted(set(erd) & set(live)):
        for column in sorted(set(erd[table]) - set(live[table])):
            problems.append(f"컬럼 없음: {table}.{column}  (erd.sql 에 있다)")
        for column in sorted(set(live[table]) & set(erd[table])):
            erd_type, erd_auto = erd[table][column]
            live_type, live_auto = live[table][column]
            if erd_type != live_type:
                problems.append(
                    f"타입 다름: {table}.{column}  DB={live_type} erd.sql={erd_type}")
            # DB 에만 있는 AUTO_INCREMENT 를 잡는다. 있으면 0 이 새 번호로 발급된다.
            if live_auto and not erd_auto:
                problems.append(
                    f"AUTO_INCREMENT 가 붙어 있다: {table}.{column}  "
                    f"(erd.sql 에는 없음 — 0 이 들어오면 MySQL 이 새 번호를 발급한다)")
            elif erd_auto and not live_auto:
                problems.append(
                    f"AUTO_INCREMENT 가 없다: {table}.{column}  "
                    f"(erd.sql 에는 있음 — INSERT 마다 값이 필요하다)")

    # 인덱스. 이름은 보지 않고 (테이블, 컬럼집합, 유니크) 만 본다.
    for table, columns, unique in sorted(erd_indexes - live_indexes):
        if not unique and args.unique_only:
            continue
        missing = [c for c in columns if c not in live.get(table, {})]
        if missing:
            continue                      # 컬럼 자체가 없는 건 위에서 이미 잡았다
        problems.append(
            f"{'유니크 ' if unique else ''}인덱스 없음: {table}({', '.join(columns)})"
            f"  (erd.sql 에 있다)")

    entities = parse_entities(ENTITIES)
    for table, (columns, filename, generated) in sorted(entities.items()):
        if table not in live:
            problems.append(f"엔티티가 없는 테이블을 가리킨다: {table}  ({filename})")
            continue
        for column in sorted(columns - set(live[table])):
            problems.append(f"엔티티가 없는 컬럼을 가리킨다: {table}.{column}  ({filename})")
        for column in sorted(generated & set(live[table])):
            if not live[table][column][1]:
                problems.append(
                    f"엔티티는 @GeneratedValue 인데 DB 에 AUTO_INCREMENT 가 없다: "
                    f"{table}.{column}  ({filename})")

    print(f"DB 테이블 {len(live)}개 / erd.sql {len(erd)}개 / 엔티티 {len(entities)}개")
    print(f"DB 인덱스 {len(live_indexes)}개 / erd.sql 인덱스 {len(erd_indexes)}개")
    if problems:
        print(f"\n차이 {len(problems)}건")
        for problem in problems:
            print(f"  {problem}")
        return 1
    print("차이 없음 — DB 가 erd.sql 과 엔티티 양쪽에 맞다")
    return 0


if __name__ == "__main__":
    sys.exit(main())
