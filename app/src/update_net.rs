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
    /// **이 기기에서 APK 설치를 시도할 수 있는가.**
    ///
    /// 안드로이드에서만 참이다. 데스크톱 개발 빌드에서는 설치 인텐트 자체가
    /// 없으므로, 화면이 안내를 띄우면 "눌러도 아무 일도 안 일어나는 창" 이
    /// 된다. 플랫폼 판단은 여기 한 곳에서만 한다 — 화면이 창 너비로 추측하면
    /// 태블릿이나 가로 화면에서 어긋난다.
    pub can_install_native: bool,
    /// 지금 안내를 띄워도 되는가 (24시간에 한 번).
    ///
    /// 앱을 켤 때마다 설치 창이 뜨면 삭제로 이어진다. 그 판단을 화면에
    /// 맡기면 기록이 메모리에만 남아 앱을 껐다 켤 때마다 되살아난다 —
    /// 그래서 DB(`kv`)에 남기고 여기서 알려 준다.
    pub native_prompt_due: bool,
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

/// 선택적인 파일의 상태 코드를 **뜻으로 옮긴다. 순수 함수다.**
///
/// 이 구분이 이 함수의 존재 이유다. 뭉치면 두 방향으로 틀린다 —
/// `404` 를 오류로 보면 APK 를 아직 안 올린 **정상 상태**에서 앱이 오류를 겪고,
/// `500` 을 "없음" 으로 보면 서버 고장을 삼켜 조용히 지나간다.
///
/// 돌려주는 값: `true` = 파일이 있다, `false` = 없다(404).
fn classify_optional(status: u16) -> Result<bool, String> {
    if status == reqwest::StatusCode::NOT_FOUND.as_u16() {
        return Ok(false);
    }
    if (200..300).contains(&status) {
        return Ok(true);
    }
    Err(format!("서버가 {status} 를 돌려줬습니다"))
}

/// 이미 열린 응답에서 **상한을 지키며** 본문을 읽는다.
///
/// 상한 검사가 한 곳에 있어야 한다 — 부르는 쪽마다 루프를 쓰면 한쪽만
/// 고쳐지는 날이 온다. 이 앱에서 상한은 안전 규칙이다.
async fn read_bounded(response: &mut reqwest::Response, limit: u64) -> Result<Vec<u8>, String> {
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
    read_bounded(&mut response, limit).await
}

/// 상한을 지키며 본문을 받되, **`404` 를 오류로 만들지 않는다** — `None` 이다.
///
/// ## 왜 한 번에 받는가
///
/// 예전에는 `404` 를 보려고 요청을 한 번 보내고, 본문을 받으려고 **또** 한 번
/// 보냈다. 낭비가 두 가지다 — 요청 자체가 두 배이고, 파일이 **실제로 있는
/// 동안** 본문을 두 번 내려받는다. 콜드 스타트마다 그 값이 곱해졌다
/// (실측: 기동 1회에 `native.json` 6회 요청. 이제 2회다).
///
/// 상태와 본문을 함께 보면 그 구분을 잃지 않는다 — `classify_optional` 이
/// 그 판단을 한 곳에서 한다.
async fn fetch_bounded_optional(
    state: &HttpState,
    url: &str,
    limit: u64,
) -> Result<Option<Vec<u8>>, String> {
    let client = state.client().clone();
    let mut response = client
        .get(&absolute(url))
        .send()
        .await
        .map_err(|error| format!("요청 실패: {error}"))?;

    if !classify_optional(response.status().as_u16())? {
        // 없다. 본문을 읽지 않는다.
        return Ok(None);
    }
    Ok(Some(read_bounded(&mut response, limit).await?))
}

/// 상한을 지키며 본문을 **파일로 흘려보낸다.**
///
/// APK 는 수십 MB 라 `Vec<u8>` 에 통째로 담으면 안 된다 — 조각을 받는 대로
/// 디스크에 쓰고 해시만 누적한다. 램 사용량은 조각 하나 크기만큼이다.
///
/// 쓰는 도중 실패하면 **찌꺼기를 지운다.** 반쪽 파일이 캐시에 남으면 다음
/// 시도가 그것을 완성본으로 착각할 여지가 생긴다.
///
/// 돌려주는 값은 (쓴 바이트 수, sha256 16진수).
async fn fetch_to_file(
    state: &HttpState,
    url: &str,
    limit: u64,
    target: &std::path::Path,
) -> Result<(u64, String), String> {
    if let Some(parent) = target.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|error| format!("임시 디렉터리를 만들지 못했습니다: {error}"))?;
    }

    let result = stream_to_file(state, url, limit, target).await;
    if result.is_err() {
        // 실패한 반쪽을 남기지 않는다.
        let _ = std::fs::remove_file(target);
    }
    result
}

async fn stream_to_file(
    state: &HttpState,
    url: &str,
    limit: u64,
    target: &std::path::Path,
) -> Result<(u64, String), String> {
    use sha2::Digest;
    use tokio::io::AsyncWriteExt;

    let client = state.client().clone();
    let mut response = client
        .get(&absolute(url))
        .send()
        .await
        .map_err(|error| format!("요청 실패: {error}"))?;

    if !response.status().is_success() {
        return Err(format!("서버가 {} 를 돌려줬습니다", response.status()));
    }
    // 값싼 검사 — 헤더가 상한을 넘는다고 하면 받기 시작하지 않는다.
    if let Some(length) = response.content_length() {
        if length > limit {
            return Err(format!("APK 가 상한을 넘습니다 (헤더 {length} > {limit})"));
        }
    }

    let mut file = tokio::fs::File::create(target)
        .await
        .map_err(|error| format!("파일을 만들지 못했습니다: {error}"))?;
    let mut hasher = sha2::Sha256::new();
    let mut written: u64 = 0;

    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|error| format!("본문 읽기 실패: {error}"))?
    {
        written += chunk.len() as u64;
        // 헤더가 없거나 거짓이어도 여기서 막힌다.
        if written > limit {
            return Err(format!("APK 가 상한을 넘었습니다 (상한 {limit})"));
        }
        hasher.update(&chunk);
        file.write_all(&chunk)
            .await
            .map_err(|error| format!("파일 쓰기 실패: {error}"))?;
    }

    // 디스크로 실제로 내려갔는지 확인한다. 이걸 빠뜨리면 정전·디스크 만재에서
    // 조용히 잘린 파일이 남고, 다음 검사가 그것을 통과시킬 수 있다.
    file.flush()
        .await
        .map_err(|error| format!("파일 마무리 실패: {error}"))?;
    file.sync_all()
        .await
        .map_err(|error| format!("파일 동기화 실패: {error}"))?;
    drop(file);

    let digest = hasher.finalize();
    let mut hex = String::with_capacity(64);
    for byte in digest {
        hex.push(char::from_digit((byte >> 4) as u32, 16).unwrap_or('0'));
        hex.push(char::from_digit((byte & 0x0f) as u32, 16).unwrap_or('0'));
    }
    Ok((written, hex))
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
///
/// **요청은 한 번이다.** 상태 확인과 본문 받기를 따로 하면 파일이 있는 동안
/// 본문을 두 번 내려받는다(`fetch_bounded_optional` 참고).
pub async fn fetch_native_manifest(state: &HttpState) -> Result<Option<NativeManifest>, String> {
    let Some(body) =
        fetch_bounded_optional(state, NATIVE_MANIFEST_URL, MAX_MANIFEST_BYTES).await?
    else {
        return Ok(None);
    };
    let text = String::from_utf8(body).map_err(|_| "매니페스트가 UTF-8 이 아닙니다".to_string())?;
    let manifest: NativeManifest =
        serde_json::from_str(&text).map_err(|error| format!("매니페스트를 읽지 못했습니다: {error}"))?;
    Ok(Some(manifest))
}

/// 지금 상태를 모은다. **네트워크 실패를 오류로 올리지 않는다** — 있는 정보만
/// 채워 돌려준다.
///
/// 두 매니페스트를 **동시에** 받는다. 순서대로 받으면 이 함수를 부르는 횟수만큼
/// 왕복이 두 배가 되는데, 이 함수는 기동 한 번에 여러 번 불린다. 둘은 서로를
/// 기다릴 이유가 없다 — 각자 독립된 파일이고, 하나가 실패해도 다른 하나는
/// 그대로 쓴다(`.ok()` 가 실패를 흡수한다).
pub async fn check(db: &Db, state: &HttpState) -> UpdateState {
    let local_web_build = update::local_web_build();
    let local_native_build = update::native_build_ms();
    let local_version_code = update::local_version_code();

    let (web, native) = tokio::join!(
        fetch_web_manifest(state),
        fetch_native_manifest(state),
    );
    let web = web.ok();
    let native = native.ok().flatten();

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
        can_install_native: cfg!(target_os = "android"),
        native_prompt_due: should_prompt(db, crate::db::now_ms()),
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
    // 계측용 시작 시각. 아래에서 내려받기와 전개를 나눠 재고 로그로 남긴다 —
    // 화면의 진행 임계값(`UPDATE_PROGRESS_DELAY_MS`)을 실측으로 정하기 위해서다.
    let started = std::time::Instant::now();
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
    // 내려받기가 끝난 시각. 전개·검증 시간은 여기서부터 잰다.
    let downloaded = started.elapsed().as_millis() as u64;

    match update::activate(root, &archive, &manifest) {
        Ok(applied) => {
            // **계측.** 폰에서 전개가 실제로 얼마나 걸리는지 알 수 없어
            // 화면의 임계값을 감으로 정하게 된다. 그 값이 실제 시간보다 낮으면
            // 정상 업데이트에도 진행 화면이 떠서 "빠르면 안 띄운다" 는 의도가
            // 깨진다. 그래서 두 구간을 나눠 남긴다 — `adb logcat` 에서 보고
            // 값을 확정한다.
            log::info!(
                "웹 번들을 활성화했습니다: {} (내려받기 {downloaded}ms, 전개·검증 {}ms, 파일 {}개)",
                applied.built_at,
                started.elapsed().as_millis() as u64 - downloaded,
                applied.files,
            );
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

/// 매니페스트가 가리키는 APK 를 **체크섬 파일과 맞대어** 확인한다.
///
/// 왜 필요한가. `native.json` 하나만 믿으면 그 파일이 잘못됐을 때(서버가 옛
/// 매니페스트를 남겨 두거나 손으로 고쳤거나) 앱이 그것을 그대로 따른다.
/// `mupid.apk.sha256` 은 APK 와 함께 놓이는 **다른 단서**라, 둘이 어긋나면
/// 그 사실 자체가 신호다 — 설계 7.3 이 이것을 요구한다.
///
/// **체크섬 파일이 없어도 막지 않는다.** `404` 는 "아직 그 통로를 안 열었다"
/// 는 뜻일 수 있고, 그때까지 네이티브 갈래를 통째로 죽이면 안 된다. 이미
/// 매니페스트의 `sha256` 으로 내려받은 바이트를 검증하므로(그 검사는 그대로
/// 있다) 여기서 얻는 것은 **두 출처가 일치한다는 확인**이지 유일한 방어선이
/// 아니다. 다만 어긋나면 막는다 — 그쪽은 한쪽이 틀렸다는 확실한 신호다.
pub async fn verify_native_sidecar(
    state: &HttpState,
    manifest: &NativeManifest,
) -> Result<(), String> {
    let sidecar = match fetch_bounded(
        state,
        update::NATIVE_CHECKSUM_PATH,
        MAX_MANIFEST_BYTES,
    )
    .await
    {
        Ok(body) => body,
        Err(error) => {
            // 없다/못 받았다는 막을 이유가 아니다. 로그만 남긴다.
            log::info!("APK 체크섬 파일을 건너뜁니다: {error}");
            return Ok(());
        }
    };
    let text = String::from_utf8(sidecar)
        .map_err(|_| "체크섬 파일이 UTF-8 이 아닙니다".to_string())?;
    update::verify_native_checksum(manifest, &text).map_err(|error| error.to_string())
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

    std::fs::create_dir_all(cache_dir)
        .map_err(|error| format!("임시 디렉터리를 만들지 못했습니다: {error}"))?;
    let target = cache_dir.join(format!("mupid-{}.apk", manifest.version_code));

    // **파일로 흘려보내며 해시를 함께 누적한다.** APK 는 수십 MB 라 메모리에
    // 통째로 담으면 안 된다 — 조각마다 쓰면서 세고, 다 쓰고 나서 대조한다.
    let (written, digest) =
        fetch_to_file(state, &manifest.apk, update::MAX_APK_BYTES, &target).await?;

    // 실제로 확인한다. 매니페스트를 믿고 넘기면 서버가 잘못된 파일을
    // 가리켰을 때 사용자가 그것을 설치한다.
    if written != manifest.bytes {
        let _ = std::fs::remove_file(&target);
        return Err(format!(
            "APK 크기가 다릅니다 (기대 {}, 실제 {written})",
            manifest.bytes
        ));
    }
    if !digest.eq_ignore_ascii_case(&manifest.sha256) {
        let _ = std::fs::remove_file(&target);
        return Err("APK 체크섬이 맞지 않습니다".to_string());
    }

    log::info!("APK 를 받았습니다: {} ({written} bytes)", target.display());
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

/// 지금 안내를 띄웠다고 기록한다 (24시간 창을 닫는다).
pub fn note_prompted(db: &Db, now_ms: i64) {
    let _ = kv::put(db, PROMPTED_KEY, &now_ms.to_string());
}

#[cfg(test)]
mod optional_tests {
    use super::*;

    /// `404` 와 그 밖의 실패를 **구분하는가.**
    ///
    /// 이것이 무너지면 두 방향으로 조용히 틀린다 — APK 를 아직 안 올린 정상
    /// 상태에서 앱이 오류를 겪거나, 서버 고장(500)을 "없음" 으로 삼켜
    /// 업데이트 확인이 영영 안 되는 것을 아무도 모른다.
    #[test]
    fn a_missing_file_is_not_an_error_but_a_server_fault_is() {
        // 없다 — 오류가 아니라 `false`.
        assert_eq!(classify_optional(404), Ok(false));
        // 있다.
        for ok in [200u16, 201, 204, 299] {
            assert_eq!(classify_optional(ok), Ok(true), "{ok} 는 성공이다");
        }
        // 실패는 실패로 남는다. 조용히 None 이 되면 안 된다.
        for bad in [400u16, 401, 403, 500, 503] {
            let result = classify_optional(bad);
            assert!(result.is_err(), "{bad} 를 '없음' 으로 삼키면 안 된다");
            assert!(
                result.unwrap_err().contains(&bad.to_string()),
                "{bad} 가 메시지에 있어야 원인을 안다"
            );
        }
    }

    /// `not_found` 는 `reqwest` 가 정한 값이어야 한다. 숫자를 손으로 박으면
    /// 라이브러리가 바뀌는 날 조용히 어긋난다 — 여기서 그 결합을 고정한다.
    #[test]
    fn the_missing_code_is_the_one_reqwest_calls_not_found() {
        assert_eq!(reqwest::StatusCode::NOT_FOUND.as_u16(), 404);
        assert_eq!(classify_optional(404), Ok(false));
    }
}

#[cfg(test)]
mod prompt_tests {
    use super::*;
    use crate::db::Db;

    /// 안내 간격이 실제로 창을 닫는가. 기록이 없으면 규칙이 아무 일도 하지
    /// 않으므로(`should_prompt`), 두 함수가 짝으로 동작하는지 본다.
    #[test]
    fn noting_a_prompt_closes_the_window_for_a_day() {
        let directory = std::env::temp_dir().join(format!("mupid-prompt-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&directory);
        std::fs::create_dir_all(&directory).expect("스크래치");
        let db = Db::open(&directory.join("t.db")).expect("db");
        let start = 1_700_000_000_000i64;

        // 처음에는 띄운다.
        assert!(should_prompt(&db, start), "처음에는 안내해야 한다");
        note_prompted(&db, start);
        // 같은 순간에 다시 보면 창이 닫혀 있다.
        assert!(!should_prompt(&db, start), "기록 직후에는 안내하면 안 된다");
        assert!(
            !should_prompt(&db, start + PROMPT_INTERVAL_MS - 1),
            "24시간이 되기 전에는 안내하면 안 된다"
        );
        // 하루가 지나면 다시 띄운다.
        assert!(
            should_prompt(&db, start + PROMPT_INTERVAL_MS),
            "하루가 지나면 다시 안내한다"
        );
        let _ = std::fs::remove_dir_all(&directory);
    }
}
