//! 서버 응답 캐시.
//!
//! 곡 카탈로그와 구간 분석은 잘 바뀌지 않는데, 없으면 화면이 통째로 비어
//! 버린다. 지하철에서 앱을 열었을 때 목록이라도 뜨게 하려고 마지막 응답을
//! 그대로 보관한다.
//!
//! **얼마나 묵은 것까지 쓸지는 여기서 정하지 않는다.** 부르는 쪽이
//! `max_age_ms` 를 넘긴다 — 목록은 하루가 지나도 보여 줄 만하지만 분석은
//! 파이프라인이 다시 돌면 바뀌기 때문이다.

use rusqlite::{params, OptionalExtension};
use serde::Serialize;

use crate::db::{now_ms, Db, DbResult};

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CacheEntry {
    /// 저장해 둔 JSON 값 그대로.
    pub payload: serde_json::Value,
    /// 받아 온 시각(밀리초). 화면이 "n분 전 기준" 을 보여 줄 수 있게 함께 준다.
    pub fetched_at: i64,
    /// 요청한 신선도를 넘겼는가. 넘겼어도 값은 돌려준다 — 네트워크가 실패하면
    /// 묵은 값이라도 보여 주는 편이 빈 화면보다 낫다.
    pub stale: bool,
}

pub fn get(
    db: &Db,
    scope: &str,
    key: &str,
    max_age_ms: Option<i64>,
) -> DbResult<Option<CacheEntry>> {
    let connection = db.lock();
    let row = connection
        .query_row(
            "SELECT payload, fetched_at FROM cache WHERE scope = ?1 AND key = ?2",
            params![scope, key],
            |row| Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?)),
        )
        .optional()?;

    let Some((payload, fetched_at)) = row else {
        return Ok(None);
    };
    // `>=` 다. `max_age_ms: 0` 은 "무엇이든 묵은 값" 이라는 뜻이어야 한다 —
    // `>` 면 방금 넣은 값이 같은 밀리초 안에서는 신선한 것으로 나온다.
    let stale = match max_age_ms {
        Some(limit) => now_ms().saturating_sub(fetched_at) >= limit,
        None => false,
    };
    Ok(Some(CacheEntry {
        payload: serde_json::from_str(&payload)?,
        fetched_at,
        stale,
    }))
}

pub fn put(db: &Db, scope: &str, key: &str, payload: &serde_json::Value) -> DbResult<()> {
    let connection = db.lock();
    connection.execute(
        "INSERT INTO cache (scope, key, payload, fetched_at) VALUES (?1, ?2, ?3, ?4)
         ON CONFLICT (scope, key) DO UPDATE SET
           payload = excluded.payload, fetched_at = excluded.fetched_at",
        params![scope, key, serde_json::to_string(payload)?, now_ms()],
    )?;
    Ok(())
}

/// 한 갈래를 통째로 비운다. `scope` 를 생략하면 캐시 전체.
pub fn clear(db: &Db, scope: Option<&str>) -> DbResult<()> {
    let connection = db.lock();
    match scope {
        Some(value) => connection.execute("DELETE FROM cache WHERE scope = ?1", params![value])?,
        None => connection.execute("DELETE FROM cache", [])?,
    };
    Ok(())
}
