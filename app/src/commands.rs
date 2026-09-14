//! 화면이 부르는 저장소 커맨드.
//!
//! SQL 은 전부 Rust 안에 있다. 화면은 `MupidService` 계약(`lib/services/contracts.ts`)
//! 그대로의 모양만 주고받는다 — 스키마가 바뀌어도 고칠 곳이 여기뿐이 되도록.

use tauri::State;

use crate::db::Db;
use crate::store::{cache, kv, prefs};

/// 계정 키를 정한다. 화면이 넘기지 않으면 지금 열려 있는 계정.
fn user_key(db: &Db, given: Option<String>) -> Result<String, String> {
    match given {
        Some(value) if !value.trim().is_empty() => Ok(value),
        _ => kv::current_user_key(db).map_err(String::from),
    }
}

// ─────────────────────────────────────────────────────────────
// 취향·컬렉션
// ─────────────────────────────────────────────────────────────

#[tauri::command]
pub fn store_load_preferences(
    user_key_hint: Option<String>,
    db: State<'_, Db>,
) -> Result<prefs::StoredPreferences, String> {
    let key = user_key(&db, user_key_hint)?;
    prefs::load(&db, &key).map_err(String::from)
}

#[tauri::command]
pub fn store_save_preferences(
    value: prefs::StoredPreferences,
    user_key_hint: Option<String>,
    db: State<'_, Db>,
) -> Result<(), String> {
    let key = user_key(&db, user_key_hint)?;
    prefs::save(&db, &key, &value).map_err(String::from)
}

#[tauri::command]
pub fn store_read_photo(
    user_key_hint: Option<String>,
    db: State<'_, Db>,
) -> Result<String, String> {
    let key = user_key(&db, user_key_hint)?;
    Ok(prefs::read_photo(&db, &key)
        .map_err(String::from)?
        .unwrap_or_default())
}

#[tauri::command]
pub fn store_save_photo(
    data: String,
    user_key_hint: Option<String>,
    db: State<'_, Db>,
) -> Result<(), String> {
    let key = user_key(&db, user_key_hint)?;
    prefs::save_photo(&db, &key, &data).map_err(String::from)
}

#[tauri::command]
pub fn store_remove_photo(
    user_key_hint: Option<String>,
    db: State<'_, Db>,
) -> Result<(), String> {
    let key = user_key(&db, user_key_hint)?;
    prefs::remove_photo(&db, &key).map_err(String::from)
}

// ─────────────────────────────────────────────────────────────
// 세션
// ─────────────────────────────────────────────────────────────

/// 로그인에 성공하면 그 사용자 번호로 바꾼다. 이 뒤의 저장은 전부 그 계정 몫이다.
#[tauri::command]
pub fn session_set_user_key(value: String, db: State<'_, Db>) -> Result<(), String> {
    let key = if value.trim().is_empty() {
        kv::LOCAL_USER_KEY.to_string()
    } else {
        value
    };
    kv::set_current_user_key(&db, &key).map_err(String::from)
}

// ─────────────────────────────────────────────────────────────
// 캐시
// ─────────────────────────────────────────────────────────────

#[tauri::command]
pub fn cache_get(
    scope: String,
    key: String,
    max_age_ms: Option<i64>,
    db: State<'_, Db>,
) -> Result<Option<cache::CacheEntry>, String> {
    cache::get(&db, &scope, &key, max_age_ms).map_err(String::from)
}

#[tauri::command]
pub fn cache_put(
    scope: String,
    key: String,
    payload: serde_json::Value,
    db: State<'_, Db>,
) -> Result<(), String> {
    cache::put(&db, &scope, &key, &payload).map_err(String::from)
}

#[tauri::command]
pub fn cache_clear(scope: Option<String>, db: State<'_, Db>) -> Result<(), String> {
    cache::clear(&db, scope.as_deref()).map_err(String::from)
}
