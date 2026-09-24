//! 뮤피드 앱 셸.
//!
//! 웹뷰는 `frontend/` 를 정적으로 빌드한 것을 그대로 띄운다. 웹과 앱이 같은
//! 화면 코드를 쓰고, 다른 것은 바깥 몇 가지뿐이다.
//!
//! - 네트워크: `http.rs` 의 커맨드와 `image.rs` 의 `mupidimg://` 스킴
//! - 저장소:   `db.rs` + `store/` 의 SQLite
//! - 알림:     `media.rs` 의 상단 알림·잠금화면 플레이어

mod assets;
mod commands;
mod config;
// 예외 보고 (S15P21A204-383). JS 예외는 `commands::crash_report` 로 들어오고,
// Rust 패닉은 `crash::install_panic_hook` 이 잡는다.
mod crash;
// 예외 보고의 SDK 다리 (S15P21A204-383 2단계, B안). Kotlin `CrashlyticsPlugin`
// 을 등록하고 핸들을 `crash.rs` 에 넘긴다 — 부르는 쪽은 화면이 아니라
// `crash.rs` 다.
mod crash_native;
mod db;
mod http;
mod image;
// 진단 로그 (S15P21A204-353). `log` 는 파사드라 이 등록이 없으면 매크로가
// 조용히 버려진다 — 그 20곳을 살린 것이 이 모듈이다. **내가 만들려던 것과
// 같은 일이라 이쪽을 남기고 중복 구현을 만들지 않았다**(crash.rs 모듈 주석 참조).
mod logging;
mod media;
mod store;
mod update;
mod update_native;
mod update_net;

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
    // **가장 먼저 로거를 세운다.** `log` 는 파사드라 로거가 없으면 매크로가
    // 조용히 버려진다 — 그러면 아래의 모든 진단이 사라진다(실제로 그랬다).
    // 무엇보다 먼저 불러야 그 뒤의 실패도 남는다.
    logging::init();
    // 로거 **뒤에** 경고 이상을 예외 보고로도 흘려보내는 후크를 꽂는다
    // (S15P21A204-383). 순서가 뒤바뀌면 꽂을 자리가 없어 조용히 아무 일도
    // 일어나지 않는다 — 출력은 `logging.rs` 몫이고 여기서는 보고만 얹는다.
    crash::install_log_bridge();

    // 기동을 한 줄 남긴다. **이 줄이 로거가 살아 있다는 증거다.**
    //
    // 다른 진단은 전부 실패 경로에 있어서 정상 기동에서는 아무것도 안 나온다 —
    // 그러면 "로거가 죽어 있는 것" 과 "실패가 없는 것" 을 구별할 수 없다.
    // 실제로 그 구별이 안 돼서 한참 헤맸다. 버전을 함께 남겨 로그만 보고도
    // 어느 판이 도는지 알 수 있게 한다.
    log::info!(
        "앱을 시작합니다: versionCode {}, 웹 번들 {}, 네이티브 빌드 {}",
        update::local_version_code(),
        update::local_web_build(),
        update::native_build_ms(),
    );

    // 자산 프로바이더를 갈아 끼운다 — 디스크에 활성화된 웹 번들이 있으면 거기서,
    // 없으면 임베드 번들에서 준다 (`assets.rs`).
    //
    // **여기서 바꿔야 한다.** `Assets::setup` 은 `Builder::build` 안에서 불리므로
    // 그때는 이미 `AppManager` 가 프로바이더를 들고 난 뒤다. `Context.assets` 가
    // `pub` 필드라 이 자리에서 그대로 갈아 끼울 수 있다.
    //
    // 임베드 프로바이더는 버리지 않고 감싼다. 업데이트가 깨져도 앱은 벽돌이 되지
    // 않는다 — 매니페스트가 어긋나거나 디렉터리가 사라지면 조용히 그쪽으로
    // 내려앉는다.
    let mut context = tauri::generate_context!();
    let embedded = std::mem::replace(&mut context.assets, Box::new(assets::EmptyAssets));
    context.assets = Box::new(assets::LayeredAssets::new(embedded));

    tauri::Builder::default()
        .plugin(media::init())
        .plugin(update_native::init())
        // 예외 보고의 SDK 다리 (S15P21A204-383 2단계, B안). 이 플러그인의
        // `setup` 이 **앱 `setup` 보다 먼저** 돈다(`app.rs` 의
        // `initialize_plugins` → 그 다음 앱 `setup`) — 그래서 아래에서 패닉
        // 훅을 걸 때는 이미 Crashlytics 싱크가 들어가 있다.
        .plugin(crash_native::init())
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

            // ── 예외 보고 (S15P21A204-383) ──────────────────────
            // 순서가 있다.
            //
            //  1. 설치 식별자를 **먼저** 확정한다. 첫 보고에 이미 정해진 값이
            //     실려야 앱을 껐다 켜도 같은 설치로 세어진다.
            //  2. 그 다음 패닉 훅. 이 뒤에 나는 패닉은 전부 잡힌다.
            //  3. 마지막으로 수집 루프.
            //
            // 셋 다 **키가 없으면 스스로 아무 일도 하지 않는다**(`crash.rs`).
            // 기동 경로에 조건문을 두지 않은 이유가 그것이다 — 여기서
            // `enabled()` 를 따로 물어보면 그 판단이 두 곳으로 갈라진다.
            //
            // **로거는 여기서 세우지 않는다.** 위 `run()` 의 첫 줄에서
            // `logging::init()` 이 이미 세웠다(진단 로그 브리지가 거기 있다 —
            // `logging.rs`). 여기서 다시 등록하면 두 번째 호출이 조용히 무시될
            // 뿐이라 아무 일도 일어나지 않지만, 판단이 두 곳으로 갈라지지 않게
            // 한 자리에만 둔다.
            {
                let database = app.state::<db::Db>();
                crash::bind_install_id(&database);
            }
            crash::install_panic_hook();
            crash::start_collector();

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
            media::media_session_update,
            media::media_session_clear,
            commands::assets_status,
            // 예외 보고 (S15P21A204-383) — Rust 는 훅으로, JS 는 이 커맨드로.
            commands::crash_report,
            commands::crash_report_status,
            // 자동 업데이트 (update.rs · update_net.rs)
            commands::update_status,
            commands::update_web,
            commands::update_check_now,
            commands::update_native,
            commands::update_note_prompted,
        ])
        .run(context)
        .expect("앱을 시작하지 못했습니다");
}
