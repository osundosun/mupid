//! 업데이트 확인·내려받기 — 네트워크가 닿는 부분.
//!
//! 검증·전개·활성화는 `update.rs` 에 있다. 여기는 **바이트를 가져오는 곳**이고,
//! 그래서 기기 없이는 검증할 수 없다. 그래서 얇게 유지한다 — 규칙은 전부
//! `update.rs` 에 두고, 여기서는 가져와 넘기기만 한다.
//!
//! ## 이 파일이 지키는 것
//!
//! **`Content-Length` 를 믿지 않는다.** 헤더는 거짓말할 수 있고, 그대로
//! `bytes()` 를 부르면 서버가 얼마든 큰 본문을 보내 메모리를 터뜨린다.
//! `chunk()` 로 조각을 받으며 세면서 상한을 넘으면 **그 자리에서 끊는다.**
//! 헤더가 있으면 먼저 걸러 내려받기 자체를 시작하지 않는다(값싼 검사).
//!
//! **실패는 조용하다.** 업데이트 확인이 안 되는 것은 사용자가 알 필요가 없는
//! 일이다. 네트워크가 없거나 서버가 죽었으면 다음 기회로 넘긴다. 화면에
//! 오류를 띄우면 "앱이 자꾸 에러를 띄운다" 가 되고, 그건 삭제로 이어진다.
//!
//! **같은 실패를 반복하지 않는다.** 서버가 잘못된 번들을 올려 두면 앱이
//! 켤 때마다 받고 버리기를 반복한다. `update::rejected` 목록으로 막는다.
//!
//! ## HTTP 클라이언트는 새로 만들지 않는다
//!
//! `http::HttpState` 의 클라이언트를 그대로 쓴다 — 같은 TLS(rustls + 번들
//! 루트), 같은 커넥션 풀. 인증이 필요 없는 요청이라 쿠키 항아리를 거치지
//! 않으면 된다(설계 9장).

use serde::Serialize;

use crate::config;
use crate::db::Db;
use crate::http::HttpState;
use crate::store::kv;
use crate::update::{self, NativeManifest, WebManifest};

/// 매니페스트가 사는 주소. nginx 의 `/download/` 블록이 서빙한다.
const WEB_MANIFEST_URL: &str = "/download/web.json";
const NATIVE_MANIFEST_URL: &str = "/download/native.json";

/// 매니페스트 본문의 상한. 작은 JSON 이다 — 64KB 면 충분하고 넉넉하다.
const MAX_MANIFEST_BYTES: u64 = 64 * 1024;

/// 마지막으로 안내한 시각을 담는 kv 키. 재촉하지 않기 위한 것이다.
const PROMPTED_KEY: &str = "update.native_prompted_at";

/// 안내 간격. 앱을 켤 때마다 설치 창이 뜨면 삭제로 이어진다.
const PROMPT_INTERVAL_MS: i64 = 24 * 60 * 60 * 1000;

/// 업데이트 상태. 화면이 이 값을 받아 안내를 띄운다.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateState {
    /// 지금 쓰이는 웹 번들의 나이 (epoch ms).
    pub local_web_build: u64,
    /// APK 에 **임베드된** 번들의 나이 (epoch ms).
    ///
    /// 화면이 "왜 지금 이 번들이 쓰이나" 를 판단할 수 있게 함께 보낸다 —
    /// 디스크 번들이 없으면 이 값이 곧 `localWebBuild` 다. 진단 화면에서
    /// 둘을 나란히 보면 "업데이트가 적용됐는지" 가 한눈에 보인다.
    pub embedded_web_build: u64,
    /// 이 APK 의 빌드 시각 (epoch ms).
    pub local_native_build: u64,
    /// 이 APK 의 `versionCode` (초).
    pub local_version_code: u64,
    /// 서버가 알려준 최신 웹 번들의 나이. 확인에 실패했으면 `None`.
    pub server_web_build: Option<u64>,
    /// 서버가 알려준 최신 APK 의 `versionCode`.
    pub server_version_code: Option<u64>,
    /// 새 웹 번들이 있는가 (`builtAt` 이 다르면 참).
    pub web_available: bool,
    /// 새 APK 가 있는가 (`versionCode` 가 클 때만 참).
    pub native_available: bool,
    /// 서버가 거부한 값인가 — 있으면 화면에 "확인" 버튼을 보여 준다.
    pub web_rejected: bool,
}

/// 주소를 절대 URL 로 만든다. 경로만 온 요청은 API 원본을 붙인다 —
/// `http.rs` 가 하는 것과 같은 규칙이다.
fn absolute(path: &str) -> String {
    if path.starts_with("http://") || path.starts_with("https://") {
        path.to_string()
    } else {
        format!("{}{}", config::api_origin().trim_end_matches('/'), path)
    }
}

/// 상한을 지키며 본문을 받는다.
///
/// `chunk()` 로 조각을 받으며 세므로, `Content-Length` 가 없거나 거짓이어도
/// 상한이 지켜진다. 넘으면 **즉시** 오류로 끝낸다 — 다 받고 나서 자르면
/// 그 사이에 메모리가 이미 부풀어 있다.
async fn fetch_bounded(state: &HttpState, url: &str, limit: u64) -> Result<Vec<u8>, String> {
    let client = state.client().clone();
    let mut response = client
        .get(&absolute(url))
        .send()
        .await
        .map_err(|error| format!("요청 실패: {error}"))?;

    if !response.status().is_success() {
        return Err(format!("서버가 {} 를 돌려줬습니다", response.status()));
    }

    // 값싼 검사 — 헤더가 상한을 넘는다고 하면 본문을 받지 않는다.
    if let Some(length) = response.content_length() {
        if length > limit {
            return Err(format!("본문이 상한을 넘습니다 (헤더 {length} > {limit})"));
        }
    }

    let mut body: Vec<u8> = Vec::new();
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|error| format!("본문 읽기 실패: {error}"))?
    {
        if body.len() as u64 + chunk.len() as u64 > limit {
            return Err(format!("본문이 상한을 넘었습니다 (상한 {limit})"));
        }
        body.extend_from_slice(&chunk);
    }
    Ok(body)
}

/// 웹 매니페스트를 받는다.
pub async fn fetch_web_manifest(state: &HttpState) -> Result<WebManifest, String> {
    let body = fetch_bounded(state, WEB_MANIFEST_URL, MAX_MANIFEST_BYTES).await?;
    let text = String::from_utf8(body).map_err(|_| "매니페스트가 UTF-8 이 아닙니다".to_string())?;
    serde_json::from_str(&text).map_err(|error| format!("매니페스트를 읽지 못했습니다: {error}"))
}

/// 네이티브 매니페스트를 받는다.
///
/// 없으면 `None` 이다 — APK 를 아직 올리지 않은 상태가 정상일 수 있다.
/// 그 경우 "네이티브 업데이트가 있다" 고 잘못 말하면 안 된다.
pub async fn fetch_native_manifest(state: &HttpState) -> Result<Option<NativeManifest>, String> {
    let client = state.client().clone();
    let response = client
        .get(&absolute(NATIVE_MANIFEST_URL))
        .send()
        .await
        .map_err(|error| format!("요청 실패: {error}"))?;

    if response.status() == reqwest::StatusCode::NOT_FOUND {
        return Ok(None);
    }
    let body = fetch_bounded(state, NATIVE_MANIFEST_URL, MAX_MANIFEST_BYTES).await?;
    let text = String::from_utf8(body).map_err(|_| "매니페스트가 UTF-8 이 아닙니다".to_string())?;
    let manifest: NativeManifest =
        serde_json::from_str(&text).map_err(|error| format!("매니페스트를 읽지 못했습니다: {error}"))?;
    Ok(Some(manifest))
}

/// 지금 상태를 모은다. **네트워크 실패를 오류로 올리지 않는다** — 있는 정보만
/// 채워 돌려준다.
pub async fn check(db: &Db, state: &HttpState) -> UpdateState {
    let local_web_build = update::local_web_build();
    let local_native_build = update::native_build_ms();
    let local_version_code = update::local_version_code();

    let web = fetch_web_manifest(state).await.ok();
    let native = fetch_native_manifest(state).await.ok().flatten();

    let rejected = update::rejected(db);
    let rejected_now = web
        .as_ref()
        .is_some_and(|manifest| rejected.contains(&manifest.built_at));

    UpdateState {
        local_web_build,
        embedded_web_build: update::embedded_web_build(),
        local_native_build,
        local_version_code,
        server_web_build: web.as_ref().map(|manifest| manifest.built_at),
        server_version_code: native.as_ref().map(|manifest| manifest.version_code),
        // **같지 않으면** 교체한다. 롤백도 이 규칙으로 자연히 동작한다 (설계 6.3).
        web_available: web
            .as_ref()
            .is_some_and(|manifest| manifest.built_at != local_web_build),
        // **클 때만** 안내한다. versionCode 가 줄면 설치가 거부되므로
        // 내려가는 방향은 애초에 성립하지 않는다.
        native_available: native
            .as_ref()
            .is_some_and(|manifest| manifest.version_code > local_version_code),
        web_rejected: rejected_now,
    }
}

/// 웹 번들을 확인하고, 새것이면 내려받아 **디스크에 활성화**한다.
///
/// 활성화까지만 한다 — 화면은 옛 번들로 계속 돈다. 실제 교체는 다음 콜드
/// 스타트다. 재생을 끊지 않기 위한 것이고, 이 저장소가 이미 겪은 사고를
/// 되풀이하지 않기 위한 것이다(설계 7.1.1).
///
/// 반환값은 활성화된 빌드 시각. 할 일이 없으면 `None`.
pub async fn refresh_web(
    db: &Db,
    state: &HttpState,
    root: &std::path::Path,
) -> Result<Option<u64>, String> {
    let manifest = fetch_web_manifest(state).await?;

    // 이미 쓰고 있는 번들이면 끝. 여기서 멈추지 않으면 매 기동마다 같은 것을
    // 다시 내려받는다.
    if manifest.built_at == update::local_web_build() {
        return Ok(None);
    }
    // 두 번 실패한 값은 다시 시도하지 않는다. 서버가 잘못된 번들을 올려 둔
    // 상태에서 앱이 켤 때마다 받고 버리는 것을 막는다.
    if update::rejected(db).contains(&manifest.built_at) {
        return Ok(None);
    }

    // 아카이브도 같은 상한으로 받는다. 매니페스트가 말한 `bytes` 를 상한으로
    // 쓰면 서버가 그 값을 크게 적어 두었을 때 상한이 무의미해진다.
    let archive = match fetch_bounded(state, &manifest.bundle, update::MAX_ARCHIVE_BYTES).await {
        Ok(bytes) => bytes,
        Err(error) => {
            // 내려받기 실패는 서버 쪽 문제일 수 있으니 거부 목록에 넣지 않는다.
            // 넣으면 일시적인 장애가 그 빌드를 영영 못 쓰게 만든다.
            return Err(error);
        }
    };

    match update::activate(root, &archive, &manifest) {
        Ok(applied) => {
            log::info!("웹 번들을 활성화했습니다: {}", applied.built_at);
            Ok(Some(applied.built_at))
        }
        Err(error) => {
            // **검증 실패만** 거부 목록에 넣는다. 이건 서버의 잘못이고,
            // 다시 받아도 같은 결과다.
            let _ = update::reject(db, manifest.built_at);
            log::warn!(
                "웹 번들 {} 을 거부했습니다: {error}",
                manifest.built_at
            );
            Err(format!("{error}"))
        }
    }
}

/// APK 를 내려받아 설치를 유도할 자리에 놓는다.
///
/// `app_cache_dir()/update/` 에 둔다 —
/// `gen/android/app/src/main/res/xml/file_paths.xml` 의 `cache-path` 가 이미
/// 이 자리를 FileProvider 에 노출하고 있어 설정을 새로 만들 필요가 없다.
///
/// **설치는 하지 않는다.** 사용자에게 넘기는 것까지가 여기 일이다.
pub async fn download_native(
    state: &HttpState,
    cache_dir: &std::path::Path,
    manifest: &NativeManifest,
) -> Result<std::path::PathBuf, String> {
    if manifest.version_code <= update::local_version_code() {
        return Err("이미 더 새것이거나 같은 버전입니다".to_string());
    }

    let bytes = fetch_bounded(state, &manifest.apk, update::MAX_APK_BYTES).await?;

    // 내려받은 뒤 실제로 확인한다. 매니페스트를 믿고 넘기면 서버가 잘못된
    // 파일을 가리켰을 때 사용자가 그것을 설치한다.
    if bytes.len() as u64 != manifest.bytes {
        return Err(format!(
            "APK 크기가 다릅니다 (기대 {}, 실제 {})",
            manifest.bytes,
            bytes.len()
        ));
    }
    if !update::sha256_hex(&bytes).eq_ignore_ascii_case(&manifest.sha256) {
        return Err("APK 체크섬이 맞지 않습니다".to_string());
    }

    std::fs::create_dir_all(cache_dir).map_err(|error| format!("임시 디렉터리를 만들지 못했습니다: {error}"))?;
    let target = cache_dir.join(format!("mupid-{}.apk", manifest.version_code));
    std::fs::write(&target, &bytes).map_err(|error| format!("APK 를 쓰지 못했습니다: {error}"))?;
    log::info!("APK 를 받았습니다: {}", target.display());
    Ok(target)
}

/// 지금 안내를 띄워도 되는가. 24시간에 한 번으로 제한한다.
///
/// **아직 화면에 연결되지 않았다.** 네이티브 안내 흐름(설계 8장의
/// `promptNativeUpdate`)을 만들 때 이 함수를 부른다. 그때까지는 컴파일만
/// 되게 두고 경고를 끈다 — 지우면 그 흐름을 만들 때 규칙을 다시 쓰게 되고,
/// 그 재작성이 "앱을 켤 때마다 창이 뜬다" 로 돌아가기 쉽다.
#[allow(dead_code)]
pub fn should_prompt(db: &Db, now_ms: i64) -> bool {
    let last = kv::get(db, PROMPTED_KEY)
        .ok()
        .flatten()
        .and_then(|text| text.parse::<i64>().ok())
        .unwrap_or(0);
    now_ms.saturating_sub(last) >= PROMPT_INTERVAL_MS
}

/// 안내를 띄웠다고 기록한다.
#[allow(dead_code)]
pub fn note_prompted(db: &Db, now_ms: i64) {
    let _ = kv::put(db, PROMPTED_KEY, &now_ms.to_string());
}
