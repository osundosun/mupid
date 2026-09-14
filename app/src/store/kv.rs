//! 작은 값 저장소.
//!
//! 세션(어떤 계정의 데이터를 보고 있는지)과 쿠키 항아리가 여기에 들어간다.
//! 액세스 토큰은 넣지 않는다 — 수명이 짧아 저장할 이유가 없고, 저장하면
//! 파일에 남는 만큼 새어 나갈 곳이 하나 늘어난다. 대신 **리프레시 토큰 쿠키**를
//! 보관해서 앱을 껐다 켜도 로그인이 유지되게 한다.

use rusqlite::{params, OptionalExtension};

use crate::db::{now_ms, Db, DbResult};

/// 현재 계정 키. 로그인 전에는 이 값을 쓴다.
pub const LOCAL_USER_KEY: &str = "local";

const SESSION_KEY: &str = "session.user_key";
pub const COOKIE_JAR_KEY: &str = "session.cookies";

pub fn get(db: &Db, key: &str) -> DbResult<Option<String>> {
    let connection = db.lock();
    let value = connection
        .query_row("SELECT value FROM kv WHERE key = ?1", params![key], |row| {
            row.get::<_, String>(0)
        })
        .optional()?;
    Ok(value)
}

pub fn put(db: &Db, key: &str, value: &str) -> DbResult<()> {
    let connection = db.lock();
    connection.execute(
        "INSERT INTO kv (key, value, updated_at) VALUES (?1, ?2, ?3)
         ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at",
        params![key, value, now_ms()],
    )?;
    Ok(())
}

pub fn remove(db: &Db, key: &str) -> DbResult<()> {
    let connection = db.lock();
    connection.execute("DELETE FROM kv WHERE key = ?1", params![key])?;
    Ok(())
}

/// 지금 열려 있는 계정 키. 없으면 `local`.
pub fn current_user_key(db: &Db) -> DbResult<String> {
    Ok(get(db, SESSION_KEY)?.unwrap_or_else(|| LOCAL_USER_KEY.to_string()))
}

pub fn set_current_user_key(db: &Db, user_key: &str) -> DbResult<()> {
    put(db, SESSION_KEY, user_key)
}
