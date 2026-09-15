//! 뮤피드 앱 셸.
//!
//! 웹뷰는 `frontend/` 를 정적으로 빌드한 것을 그대로 띄운다. 웹과 앱이 같은
//! 화면 코드를 쓰고, 다른 것은 바깥 몇 가지뿐이다.
//!
//! - 네트워크: `http.rs` 의 커맨드와 `image.rs` 의 `mupidimg://` 스킴
//! - 저장소:   `db.rs` + `store/` 의 SQLite
//! - 알림:     `media.rs` 의 상단 알림·잠금화면 플레이어

mod commands;
mod config;
mod db;
mod http;
mod image;
mod media;
mod store;

use tauri::{Manager, WebviewWindowBuilder};

/// RSC 호환성 식별자. `frontend/scripts/static-target.mjs` 의 같은 이름과
/// **반드시 같아야 한다** — 다르면 화면을 옮길 때마다 전체가 다시 로딩된다.
const RSC_COMPATIBILITY_ID: &str = "mupid-static-1";

/// 앨범 아트 같은 원격 이미지를 Rust 가 받아 웹뷰에 넘긴다.
///
/// `<img src>` 하나를 위해 이렇게까지 하는 이유는, 그것만 웹뷰가 직접 인터넷에
/// 나가게 두면 CSP 를 그만큼 열어야 하고, 이미지가 인증 뒤로 옮겨가는 날
/// 조용히 깨지기 때문이다. 주소는 `mupidimg://localhost/<base64url 로 인코딩한
/// 원래 주소>` 모양이다.
fn serve_image(
    app: tauri::AppHandle,
    request: tauri::http::Request<Vec<u8>>,
    responder: tauri::UriSchemeResponder,
) {
    let encoded = request
        .uri()
        .path()
        .trim_start_matches('/')
        .split('?')
        .next()
        .unwrap_or_default()
        .to_string();

    tauri::async_runtime::spawn(async move {
        let fail = |status: u16| {
            tauri::http::Response::builder()
                .status(status)
                .header("Access-Control-Allow-Origin", "*")
                .body(Vec::new())
                .expect("빈 응답은 언제나 만들 수 있다")
        };

        // 푸는 규칙은 `image.rs` 한 곳에만 있다. 알림에 넣을 표지도 같은 것을
        // 쓴다 (`media.rs`).
        let Some(url) = image::decode(&encoded).as_deref().and_then(image::server_url) else {
            responder.respond(fail(400));
            return;
        };

        let client = {
            let state = app.state::<http::HttpState>();
            state.client().clone()
        };
        let result = client.get(&url).send().await;
        let response = match result {
            Ok(value) => value,
            Err(error) => {
                log::warn!("이미지를 받지 못했습니다 ({url}): {error}");
                responder.respond(fail(502));
                return;
            }
        };

        let status = response.status().as_u16();
        let content_type = response
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|value| value.to_str().ok())
            .unwrap_or("application/octet-stream")
            .to_string();
        let body = match response.bytes().await {
            Ok(value) => value.to_vec(),
            Err(error) => {
                log::warn!("이미지 본문을 읽지 못했습니다 ({url}): {error}");
                responder.respond(fail(502));
                return;
            }
        };

        let built = tauri::http::Response::builder()
            .status(status)
            .header("Content-Type", content_type)
            .header("Access-Control-Allow-Origin", "*")
            // 앨범 아트는 잘 바뀌지 않는다. 웹뷰가 들고 있게 둔다.
            .header("Cache-Control", "public, max-age=86400")
            .body(body)
            .unwrap_or_else(|_| fail(500));
        responder.respond(built);
    });
}

/// 화면을 옮길 때 라우터가 받아 가는 RSC 페이로드를 돌려준다.
///
/// 라우터는 `/explore?_rsc` 처럼 **화면 주소에 표시만 붙여서** 요청한다. 서버가
/// 있을 때는 그 표시를 보고 HTML 대신 RSC 페이로드를 내주지만, 자산 프로토콜은
/// 표시를 모른 채 `explore.html` 을 그대로 준다. 라우터는 그것이 RSC 응답이
/// 아니라는 것만 알고 문서를 통째로 다시 읽는다 — 탭을 누를 때마다 앱이 처음부터
/// 다시 뜨는 것처럼 보이는 것이 이 때문이다.
///
/// 그래서 여기서 같은 경로의 `.rsc` 파일로 갈아 끼우고, 서버가 붙여 주던 헤더
/// 둘을 대신 붙인다. 호환성 식별자는 정적 배포에 실어 줄 서버가 없어서 빌드 때
/// 고정해 둔 값이다 (`frontend/vite.config.ts`).
fn serve_rsc<R: tauri::Runtime>(
    assets: &tauri::AssetResolver<R>,
    request: tauri::http::Request<Vec<u8>>,
    response: &mut tauri::http::Response<std::borrow::Cow<'static, [u8]>>,
) {
    let uri = request.uri();
    let wants_rsc = uri
        .query()
        .is_some_and(|query| query.split('&').any(|part| part.split('=').next() == Some("_rsc")));
    if !wants_rsc {
        return;
    }

    // `/` 는 `index.rsc`, `/explore` 는 `explore.rsc` 다.
    let path = uri.path().trim_end_matches('/');
    let name = if path.is_empty() { "/index" } else { path };
    let Some(asset) = assets.get(format!("{name}.rsc")) else {
        // 페이로드가 없으면 손대지 않는다. 라우터는 전체 이동으로 내려앉는데,
        // 느릴 뿐 화면은 제대로 뜬다.
        log::warn!("RSC 페이로드를 찾지 못했습니다: {name}.rsc");
        return;
    };

    let length = asset.bytes.len();
    *response.body_mut() = std::borrow::Cow::Owned(asset.bytes);
    let headers = response.headers_mut();
    headers.insert(
        tauri::http::header::CONTENT_TYPE,
        tauri::http::HeaderValue::from_static("text/x-component"),
    );
    headers.insert(
        "X-Vinext-RSC-Compatibility-Id",
        tauri::http::HeaderValue::from_static(RSC_COMPATIBILITY_ID),
    );
    // 본문을 바꿨으니 길이도 바꿔야 한다. 그대로 두면 응답이 잘린다.
    if let Ok(value) = tauri::http::HeaderValue::from_str(&length.to_string()) {
        headers.insert(tauri::http::header::CONTENT_LENGTH, value);
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(media::init())
        .register_asynchronous_uri_scheme_protocol(config::IMAGE_SCHEME, |ctx, request, responder| {
            serve_image(ctx.app_handle().clone(), request, responder);
        })
        .setup(|app| {
            // 저장 위치는 OS 가 정하는 앱 데이터 디렉터리다. 앱을 지우면 함께
            // 사라지고, 다른 앱은 읽을 수 없다.
            let path = app
                .path()
                .app_data_dir()
                .map_err(|error| format!("앱 데이터 위치를 찾지 못했어요: {error}"))?
                .join("mupid.db");
            let database = db::Db::open(&path)?;
            let http_state = http::HttpState::new(&database)?;
            app.manage(database);
            app.manage(http_state);

            // 창은 설정(`tauri.conf.json` 의 `create: false`)이 아니라 여기서
            // 만든다. 자산 응답의 헤더를 손볼 수 있는 자리가 여기뿐이기 때문이다.
            let config = app
                .config()
                .app
                .windows
                .first()
                .cloned()
                .ok_or("창 설정이 없습니다.")?;
            let assets = app.asset_resolver();
            WebviewWindowBuilder::from_config(app.handle(), &config)?
                .on_web_resource_request(move |request, response| serve_rsc(&assets, request, response))
                .build()?;
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            http::http_send,
            http::http_abort,
            http::http_clear_cookies,
            commands::store_load_preferences,
            commands::store_save_preferences,
            commands::store_read_photo,
            commands::store_save_photo,
            commands::store_remove_photo,
            commands::session_set_user_key,
            commands::cache_get,
            commands::cache_put,
            commands::cache_clear,
            media::media_session_update,
            media::media_session_clear,
        ])
        .run(tauri::generate_context!())
        .expect("앱을 시작하지 못했습니다");
}
