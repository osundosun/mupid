//! SQLite 연결과 스키마.
//!
//! 앱에는 `localStorage` 가 없다 — 정확히는 있지만, 웹뷰 저장소는 OS 가 앱
//! 데이터를 정리할 때 함께 사라지고 백업 대상도 아니다. 사용자의 취향·컬렉션은
//! 앱 데이터 디렉터리의 SQLite 파일 한 곳에만 둔다.
//!
//! 연결은 하나를 뮤텍스로 감싼다. 화면 하나가 쓰는 앱이라 동시성이 낮고,
//! 커넥션 풀을 두면 WAL 체크포인트와 마이그레이션 순서를 더 신경 써야 한다.

use std::path::Path;
use std::sync::Mutex;

use rusqlite::Connection;

pub struct Db(pub Mutex<Connection>);

#[derive(Debug, thiserror::Error)]
pub enum DbError {
    #[error("저장소를 열지 못했어요: {0}")]
    Sqlite(#[from] rusqlite::Error),
    #[error("저장한 값을 읽지 못했어요: {0}")]
    Json(#[from] serde_json::Error),
}

/// 커맨드 반환용. Tauri 는 오류를 문자열로 넘긴다.
impl From<DbError> for String {
    fn from(value: DbError) -> Self {
        value.to_string()
    }
}

pub type DbResult<T> = Result<T, DbError>;

/// 스키마 버전. 새 마이그레이션을 넣을 때마다 하나 올린다.
const SCHEMA_VERSION: i64 = 2;

impl Db {
    pub fn open(path: &Path) -> DbResult<Self> {
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let connection = Connection::open(path)?;
        // WAL 은 읽기와 쓰기가 서로를 막지 않게 한다. 모바일에서 앱이 갑자기
        // 종료돼도 저널이 남아 복구된다.
        connection.pragma_update(None, "journal_mode", "WAL")?;
        connection.pragma_update(None, "synchronous", "NORMAL")?;
        connection.pragma_update(None, "foreign_keys", "ON")?;
        migrate(&connection)?;
        Ok(Self(Mutex::new(connection)))
    }

    /// 잠금이 오염돼도(다른 스레드가 패닉) 앱을 죽이지 않는다. 연결 자체는
    /// 멀쩡하므로 그대로 이어서 쓴다.
    pub fn lock(&self) -> std::sync::MutexGuard<'_, Connection> {
        self.0.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

fn migrate(connection: &Connection) -> DbResult<()> {
    let current: i64 =
        connection.query_row("PRAGMA user_version", [], |row| row.get(0))?;
    if current >= SCHEMA_VERSION {
        return Ok(());
    }
    if current < 1 {
        connection.execute_batch(SCHEMA_V1)?;
    }
    if current < 2 {
        connection.execute_batch(SCHEMA_V2)?;
    }
    connection.pragma_update(None, "user_version", SCHEMA_VERSION)?;
    Ok(())
}

/// v1 스키마.
///
/// `user_key` 는 계정을 구분한다. 로그인 전에는 `local`, 로그인하면 서버
/// 사용자 번호다. 한 기기를 여러 사람이 쓰는 경우가 드물지만, 키를 두지 않으면
/// 로그아웃 뒤 남은 취향이 다음 사람에게 그대로 보인다.
const SCHEMA_V1: &str = r#"
CREATE TABLE IF NOT EXISTS kv (
  key        TEXT PRIMARY KEY,
  value      TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS preferences (
  user_key                TEXT PRIMARY KEY,
  onboarding_submitted_at TEXT,
  nickname                TEXT NOT NULL DEFAULT '',
  moods                   TEXT NOT NULL DEFAULT '[]',
  genres                  TEXT NOT NULL DEFAULT '[]',
  last_played_id          INTEGER,
  updated_at              INTEGER NOT NULL
);

-- 좋아요·무시·재생 기록. 종류가 셋뿐이고 쓰임이 같아 한 표에 둔다.
-- position 은 화면이 보여 주는 순서다 (기록은 최근 순).
CREATE TABLE IF NOT EXISTS track_flags (
  user_key TEXT    NOT NULL,
  kind     TEXT    NOT NULL CHECK (kind IN ('liked', 'ignored', 'history')),
  track_id INTEGER NOT NULL,
  position INTEGER NOT NULL,
  PRIMARY KEY (user_key, kind, track_id)
);
CREATE INDEX IF NOT EXISTS track_flags_order
  ON track_flags (user_key, kind, position);

CREATE TABLE IF NOT EXISTS playlists (
  user_key TEXT    NOT NULL,
  id       INTEGER NOT NULL,
  name     TEXT    NOT NULL,
  position INTEGER NOT NULL,
  PRIMARY KEY (user_key, id)
);

CREATE TABLE IF NOT EXISTS playlist_tracks (
  user_key    TEXT    NOT NULL,
  playlist_id INTEGER NOT NULL,
  track_id    INTEGER NOT NULL,
  position    INTEGER NOT NULL,
  PRIMARY KEY (user_key, playlist_id, position),
  FOREIGN KEY (user_key, playlist_id) REFERENCES playlists (user_key, id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS moments (
  user_key   TEXT    NOT NULL,
  id         INTEGER NOT NULL,
  track_id   INTEGER NOT NULL,
  start_sec  REAL    NOT NULL,
  end_sec    REAL    NOT NULL,
  section    TEXT    NOT NULL DEFAULT '',
  PRIMARY KEY (user_key, id)
);

CREATE TABLE IF NOT EXISTS profile_photo (
  user_key TEXT PRIMARY KEY,
  data     TEXT NOT NULL
);

"#;

/// v2 — 서버 응답 캐시를 없앤다 (S15P21A204-444).
///
/// 서버에 닿지 않으면 서비스를 열지 않으므로(`ServerGate`) 묵은 응답으로 화면을
/// 채울 일이 없다. v1 로 깔린 기기에 남은 표를 지운다.
const SCHEMA_V2: &str = "DROP TABLE IF EXISTS cache;";

/// 지금 시각(밀리초).
pub fn now_ms() -> i64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|value| value.as_millis() as i64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use rusqlite::Connection;

    /// v1 로 깔린 기기에 남은 응답 캐시 표가 v2 에서 지워진다 (S15P21A204-444).
    #[test]
    fn v2_drops_the_response_cache_left_by_v1() {
        let connection = Connection::open_in_memory().expect("열기");
        connection
            .execute_batch(
                "CREATE TABLE cache (scope TEXT, key TEXT, payload TEXT, fetched_at INTEGER);
                 PRAGMA user_version = 1;",
            )
            .expect("v1 흉내");

        super::migrate(&connection).expect("이관");

        let left: i64 = connection
            .query_row(
                "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'cache'",
                [],
                |row| row.get(0),
            )
            .expect("조회");
        assert_eq!(left, 0);
        let version: i64 = connection
            .query_row("PRAGMA user_version", [], |row| row.get(0))
            .expect("판");
        assert_eq!(version, super::SCHEMA_VERSION);
    }
}
