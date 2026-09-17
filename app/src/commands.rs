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

// ─────────────────────────────────────────────────────────────
// 진단
// ─────────────────────────────────────────────────────────────

/// 지금 어느 번들이 화면을 그리고 있는지 알린다.
///
/// 디스크 번들 교체는 조용히 일어난다 — 잘못되면 조용히 임베드로 내려앉는다.
/// 그래서 **밖에서 확인할 수 있는 통로**가 필요하다. 실기기에서 `adb push` 로
/// 번들을 올린 뒤 이 커맨드(또는 같은 값을 화면에 찍는 것)로 어느 쪽이 쓰이는지
/// 확인한다. 로그만으로는 릴리스 빌드에서 볼 수 없다.
///
/// **웹 업데이트와 네이티브 버전도 함께 알린다.** "왜 업데이트가 안 되지" 를
/// 밖에서 판단하려면 세 값이 한 자리에 있어야 한다 — 어느 번들이 쓰이는지,
/// 앱이 자기를 몇 번으로 아는지, 서버가 무엇을 말하는지.
#[tauri::command]
pub fn assets_status(app: tauri::AppHandle) -> Result<serde_json::Value, String> {
    use tauri::Manager;
    let root = app
        .path()
        .app_data_dir()
        .ok()
        .map(|directory| directory.join("web"));

    Ok(serde_json::json!({
        "source": match crate::assets::active() {
            Some(_) => "disk",
            None => "embedded",
        },
        "root": crate::assets::active().map(|active| active.root.to_string_lossy().to_string()),
        "hashes": crate::assets::active().map(|active| active.hash_count()),
        // 앱이 아는 나이·버전. 업데이트 판단의 입력값이다.
        "localWebBuild": crate::update::local_web_build(),
        "embeddedWebBuild": crate::update::embedded_web_build(),
        "localNativeBuild": crate::update::native_build_ms(),
        "localVersionCode": crate::update::local_version_code(),
        // 디스크에 남아 있는 번들들 (정렬된 목록).
        "installed": root
            .as_ref()
            .map(|directory| crate::update::installed(directory))
            .unwrap_or_default(),
        "activePath": root
            .as_ref()
            .and_then(|directory| crate::update::active_path(directory))
            .map(|path| path.to_string_lossy().to_string()),
    }))
}

// ─────────────────────────────────────────────────────────────
// 자동 업데이트
// ─────────────────────────────────────────────────────────────

/// 업데이트가 사는 자리. `mupid.db` 옆이다 (`assets.rs` 의 `WEB_DIR` 과 같다).
fn web_root(app: &tauri::AppHandle) -> Result<std::path::PathBuf, String> {
    use tauri::Manager;
    app.path()
        .app_data_dir()
        .map(|directory| directory.join("web"))
        .map_err(|error| format!("앱 데이터 위치를 찾지 못했어요: {error}"))
}

/// APK 를 놓을 자리.
///
/// `app_cache_dir` 를 쓴다 — 캐시가 비워지면 사라져도 되는 임시 파일이기
/// 때문이다. 번들과 달리 **수명이 짧아야 맞다**(설치하고 나면 필요 없다).
/// `file_paths.xml` 의 `cache-path` 가 이미 이 자리를 FileProvider 에
/// 노출하고 있어 설정을 새로 만들지 않아도 된다.
fn native_cache(app: &tauri::AppHandle) -> Result<std::path::PathBuf, String> {
    use tauri::Manager;
    app.path()
        .app_cache_dir()
        .map(|directory| directory.join("update"))
        .map_err(|error| format!("캐시 위치를 찾지 못했어요: {error}"))
}

/// 지금 상태를 알린다. **네트워크가 없으면 로컬 값만 채워 돌려준다.**
#[tauri::command]
pub async fn update_status(
    app: tauri::AppHandle,
    db: State<'_, Db>,
    http: State<'_, crate::http::HttpState>,
) -> Result<crate::update_net::UpdateState, String> {
    let _ = &app;
    Ok(crate::update_net::check(&db, &http).await)
}

/// 웹 번들을 확인하고, 새것이면 내려받아 **디스크에 활성화**한다.
///
/// 활성화까지만 한다 — 화면은 옛 번들로 계속 돈다. 실제 교체는 다음 콜드
/// 스타트다. 그래야 듣던 곡이 끊기지 않는다(설계 7.1.1).
///
/// 반환값은 활성화된 빌드 시각. 할 일이 없거나 실패하면 `null` 이다 —
/// **업데이트 실패가 화면에 오류로 뜨면 안 된다.** 조용히 다음 기회로 넘긴다.
#[tauri::command]
pub async fn update_web(
    app: tauri::AppHandle,
    db: State<'_, Db>,
    http: State<'_, crate::http::HttpState>,
) -> Result<Option<u64>, String> {
    let root = web_root(&app)?;
    // 실패는 로그로만 남긴다. 화면에는 null 이 간다.
    match crate::update_net::refresh_web(&db, &http, &root).await {
        Ok(value) => Ok(value),
        Err(error) => {
            log::info!("웹 업데이트를 건너뜁니다: {error}");
            Ok(None)
        }
    }
}

/// 사용자가 "업데이트 확인" 을 직접 눌렀을 때.
///
/// 거부 목록을 비운다 — 서버가 고친 번들을 올렸을 수 있고, 자동으로는 그
/// 값을 영영 다시 시도하지 않기 때문이다.
#[tauri::command]
pub async fn update_check_now(
    app: tauri::AppHandle,
    db: State<'_, Db>,
    http: State<'_, crate::http::HttpState>,
) -> Result<crate::update_net::UpdateState, String> {
    let root = web_root(&app)?;
    crate::update::clear_rejected(&db).map_err(|error| error.to_string())?;
    let _ = crate::update_net::refresh_web(&db, &http, &root).await;
    Ok(crate::update_net::check(&db, &http).await)
}

/// APK 를 내려받아 설치 화면으로 넘긴다.
///
/// **설치는 사용자가 한다.** 여기서 하는 일은 검증된 파일을 시스템 설치기로
/// 넘기는 것까지다(설계 7.3).
#[tauri::command]
pub async fn update_native(
    app: tauri::AppHandle,
    db: State<'_, Db>,
    http: State<'_, crate::http::HttpState>,
) -> Result<(), String> {
    let Some(manifest) = crate::update_net::fetch_native_manifest(&http)
        .await
        .map_err(|error| format!("업데이트 정보를 받지 못했습니다: {error}"))?
    else {
        return Err("올려진 APK 정보가 없습니다".to_string());
    };

    let cache = native_cache(&app)?;
    let apk = crate::update_net::download_native(&http, &cache, &manifest).await?;
    let _ = &db;

    // Kotlin 플러그인에 넘긴다. `media.rs` 와 같은 모양이다 — 플러그인은
    // `setup` 에서 등록하고, 화면이 부르는 것은 이 커맨드다.
    install_apk(&app, &apk)
}

/// 안내를 **띄웠다**고 기록한다. 24시간 창을 닫는 유일한 자리다.
///
/// 화면이 안내 창을 띄운 직후에 부른다. 이걸 빠뜨리면 앱을 켤 때마다 같은
/// 창이 다시 떠서 삭제로 이어진다 — 간격 규칙(`should_prompt`)이 있지만
/// **기록이 남지 않으면 규칙이 아무 일도 하지 않는다.**
///
/// 사용자가 "나중에" 를 눌러도 부른다. 닫은 것도 안내를 본 것이므로 다시
/// 띄울 이유가 없다.
#[tauri::command]
pub fn update_note_prompted(db: State<'_, Db>) -> Result<(), String> {
    crate::update_net::note_prompted(&db, crate::db::now_ms());
    Ok(())
}

/// 시스템 설치기에 APK 를 넘긴다 (안드로이드 전용).
#[cfg(target_os = "android")]
fn install_apk(app: &tauri::AppHandle, apk: &std::path::Path) -> Result<(), String> {
    use tauri::Manager;
    let plugin = app
        .try_state::<crate::update_native::UpdatePlugin<tauri::Wry>>()
        .ok_or_else(|| "업데이트 플러그인이 등록되지 않았습니다".to_string())?;
    plugin.install(apk)
}

/// 데스크톱에서는 설치 유도가 없다. 빌드를 살려 두기 위해 존재한다.
#[cfg(not(target_os = "android"))]
fn install_apk(_app: &tauri::AppHandle, _apk: &std::path::Path) -> Result<(), String> {
    Err("이 플랫폼에서는 앱이 스스로를 갱신할 수 없습니다".to_string())
}
