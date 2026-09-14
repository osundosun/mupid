//! 앱의 HTTP 전송 계층.
//!
//! 웹뷰의 `fetch` 는 쓰지 않는다. 요청은 전부 여기로 온다.
//!
//! 왜 이렇게 하는가.
//!
//! - **CORS 가 사라진다.** 서버에 CORS 설정이 없어 웹에서는 Vite/nginx 프록시로
//!   같은 출처인 척해야 했다. 앱에는 출처가 없으니 프록시도 필요 없다.
//! - **리프레시 토큰 쿠키가 산다.** 쿠키는 `SameSite=Strict; Secure` 라 앱
//!   웹뷰의 커스텀 스킴(`tauri://`, `http://tauri.localhost`)에서는 붙지 않는다.
//!   여기서는 우리가 쿠키 항아리를 들고 있고, 그것을 SQLite 에 저장한다 —
//!   그래서 앱을 껐다 켜도 로그인이 유지된다.
//! - 오디오 청크처럼 큰 바이너리를 웹뷰 메모리를 거치지 않고 받을 수 있다.
//!
//! 응답은 한 덩어리의 바이트로 돌려준다. 앞 4바이트가 메타데이터(JSON) 길이고,
//! 그 뒤에 메타데이터, 그 뒤가 본문이다. JSON 커맨드와 바이너리 커맨드를 따로
//! 두면 같은 규약을 두 번 구현하게 되고, 둘이 조용히 어긋난다.

use std::collections::HashMap;
use std::io::{BufReader, Write};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use base64::Engine;
use reqwest_cookie_store::{CookieStore, CookieStoreMutex};
use serde::{Deserialize, Serialize};
use tauri::ipc::Response;
use tokio::sync::oneshot;

use crate::config::api_origin;
use crate::db::Db;
use crate::store::kv;

const BASE64: base64::engine::general_purpose::GeneralPurpose =
    base64::engine::general_purpose::STANDARD;

// ─────────────────────────────────────────────────────────────
// 요청/응답 모양
// ─────────────────────────────────────────────────────────────

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MultipartPart {
    pub name: String,
    /// 있으면 파일 파트, 없으면 일반 필드.
    #[serde(default)]
    pub file_name: Option<String>,
    #[serde(default)]
    pub content_type: Option<String>,
    /// 내용은 언제나 base64 다. 숫자 배열로 넘기면 IPC 직렬화가 파일 크기에
    /// 비례해 느려진다.
    pub data_base64: String,
}

#[derive(Debug, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum RequestBody {
    Text { text: String },
    Binary { data_base64: String },
    Multipart { parts: Vec<MultipartPart> },
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HttpRequest {
    pub method: String,
    /// 절대 URL 이거나 `/` 로 시작하는 경로. 경로면 API 원본 주소를 붙인다.
    pub url: String,
    #[serde(default)]
    pub headers: HashMap<String, String>,
    #[serde(default)]
    pub body: Option<RequestBody>,
    #[serde(default)]
    pub timeout_ms: Option<u64>,
    /// 취소용 식별자. 화면이 요청을 끊으면 이 값으로 `http_abort` 를 부른다.
    #[serde(default)]
    pub request_id: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ResponseMeta {
    status: u16,
    /// 키는 소문자로 맞춘다. 화면 쪽 `Transport` 규약이다.
    headers: HashMap<String, String>,
}

// ─────────────────────────────────────────────────────────────
// 상태
// ─────────────────────────────────────────────────────────────

pub struct HttpState {
    client: reqwest::Client,
    cookies: Arc<CookieStoreMutex>,
    /// 진행 중인 요청의 취소 통로.
    inflight: Mutex<HashMap<String, oneshot::Sender<()>>>,
}

impl HttpState {
    /// 저장해 둔 쿠키를 되살려 클라이언트를 만든다.
    pub fn new(db: &Db) -> Result<Self, String> {
        let store = match kv::get(db, kv::COOKIE_JAR_KEY) {
            Ok(Some(saved)) => cookie_store::serde::json::load_all(BufReader::new(saved.as_bytes()))
                .unwrap_or_else(|error| {
                    // 형식이 바뀌었거나 깨졌다. 로그인만 다시 하면 되는 일이라
                    // 앱을 멈추지 않는다.
                    log::warn!("저장된 쿠키를 읽지 못해 새로 시작합니다: {error}");
                    CookieStore::default()
                }),
            Ok(None) => CookieStore::default(),
            Err(error) => {
                log::warn!("쿠키 저장소를 열지 못했습니다: {error}");
                CookieStore::default()
            }
        };
        let cookies = Arc::new(CookieStoreMutex::new(store));

        let client = reqwest::Client::builder()
            .cookie_provider(cookies.clone())
            .user_agent(concat!("MupidApp/", env!("CARGO_PKG_VERSION")))
            // 커넥션을 살려 두면 청크를 이어 받을 때 핸드셰이크를 반복하지 않는다.
            .pool_idle_timeout(Duration::from_secs(60))
            .build()
            .map_err(|error| format!("HTTP 클라이언트를 만들지 못했어요: {error}"))?;

        Ok(Self {
            client,
            cookies,
            inflight: Mutex::new(HashMap::new()),
        })
    }

    /// 쿠키 항아리를 SQLite 에 적는다. `Set-Cookie` 를 받은 응답 뒤에만 부른다.
    fn persist_cookies(&self, db: &Db) {
        let mut buffer: Vec<u8> = Vec::new();
        {
            let store = match self.cookies.lock() {
                Ok(value) => value,
                Err(poisoned) => poisoned.into_inner(),
            };
            // 만료·세션 쿠키까지 함께 적는다. 리프레시 토큰이 세션 쿠키로 올
            // 수도 있는데, 그것을 빼면 앱을 껐다 켤 때마다 로그인이 풀린다.
            if let Err(error) =
                cookie_store::serde::json::save_incl_expired_and_nonpersistent(&store, &mut buffer)
            {
                log::warn!("쿠키를 직렬화하지 못했습니다: {error}");
                return;
            }
        }
        let _ = buffer.flush();
        match String::from_utf8(buffer) {
            Ok(text) => {
                if let Err(error) = kv::put(db, kv::COOKIE_JAR_KEY, &text) {
                    log::warn!("쿠키를 저장하지 못했습니다: {error}");
                }
            }
            Err(error) => log::warn!("쿠키 직렬화 결과가 UTF-8 이 아닙니다: {error}"),
        }
    }

    /// 로그아웃. 쿠키를 기기에서도 지운다.
    pub fn clear_cookies(&self, db: &Db) {
        {
            let mut store = match self.cookies.lock() {
                Ok(value) => value,
                Err(poisoned) => poisoned.into_inner(),
            };
            store.clear();
        }
        if let Err(error) = kv::remove(db, kv::COOKIE_JAR_KEY) {
            log::warn!("쿠키를 지우지 못했습니다: {error}");
        }
    }

    /// 이미지 스킴 핸들러도 같은 커넥션 풀과 쿠키를 쓴다.
    pub fn client(&self) -> &reqwest::Client {
        &self.client
    }
}

// ─────────────────────────────────────────────────────────────
// 실행
// ─────────────────────────────────────────────────────────────

/// 경로만 온 요청에 API 원본 주소를 붙인다.
fn resolve_url(url: &str) -> String {
    if url.starts_with('/') {
        format!("{}{}", api_origin().trim_end_matches('/'), url)
    } else {
        url.to_string()
    }
}

fn build_request(
    client: &reqwest::Client,
    request: &HttpRequest,
) -> Result<reqwest::RequestBuilder, String> {
    let method = reqwest::Method::from_bytes(request.method.as_bytes())
        .map_err(|_| format!("알 수 없는 HTTP 메서드예요: {}", request.method))?;
    let mut builder = client.request(method, resolve_url(&request.url));

    for (key, value) in &request.headers {
        builder = builder.header(key.as_str(), value.as_str());
    }
    if let Some(timeout) = request.timeout_ms {
        builder = builder.timeout(Duration::from_millis(timeout));
    }

    builder = match &request.body {
        None => builder,
        Some(RequestBody::Text { text }) => builder.body(text.clone()),
        Some(RequestBody::Binary { data_base64 }) => {
            let bytes = BASE64
                .decode(data_base64)
                .map_err(|error| format!("본문을 해독하지 못했어요: {error}"))?;
            builder.body(bytes)
        }
        Some(RequestBody::Multipart { parts }) => {
            // 멀티파트는 여기서 만든다. 경계 문자열을 reqwest 가 정하고
            // `Content-Type` 도 직접 붙이므로, 화면은 그 헤더를 보내지 않는다
            // (`ApiClient.dispatch` 가 form 일 때 헤더를 비워 둔다).
            let mut form = reqwest::multipart::Form::new();
            for part in parts {
                let bytes = BASE64
                    .decode(&part.data_base64)
                    .map_err(|error| format!("첨부를 해독하지 못했어요: {error}"))?;
                let mut piece = reqwest::multipart::Part::bytes(bytes);
                if let Some(name) = &part.file_name {
                    piece = piece.file_name(name.clone());
                }
                if let Some(mime) = &part.content_type {
                    piece = piece
                        .mime_str(mime)
                        .map_err(|error| format!("첨부 형식이 올바르지 않아요: {error}"))?;
                }
                form = form.part(part.name.clone(), piece);
            }
            builder.multipart(form)
        }
    };

    Ok(builder)
}

/// 4바이트 길이 + 메타데이터 JSON + 본문.
fn frame(status: u16, headers: HashMap<String, String>, body: &[u8]) -> Result<Vec<u8>, String> {
    let meta = serde_json::to_vec(&ResponseMeta { status, headers })
        .map_err(|error| format!("응답 정보를 담지 못했어요: {error}"))?;
    let mut out = Vec::with_capacity(4 + meta.len() + body.len());
    out.extend_from_slice(&(meta.len() as u32).to_le_bytes());
    out.extend_from_slice(&meta);
    out.extend_from_slice(body);
    Ok(out)
}

#[tauri::command]
pub async fn http_send(
    request: HttpRequest,
    http: tauri::State<'_, HttpState>,
    db: tauri::State<'_, Db>,
) -> Result<Response, String> {
    let builder = build_request(http.client(), &request)?;

    // 취소 통로. 식별자가 없으면 보내는 쪽이 끊을 생각이 없다는 뜻이라
    // 송신부를 이 함수가 그대로 들고 있는다(그래야 수신부가 깨어나지 않는다).
    let (sender, mut receiver) = oneshot::channel::<()>();
    let _keep_alive = match &request.request_id {
        Some(id) => {
            http.inflight
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner())
                .insert(id.clone(), sender);
            None
        }
        None => Some(sender),
    };

    // 본문을 다 읽는 것까지 한 덩어리로 묶어 취소를 건다. 헤더만 받고 나면
    // 못 끊는다면, 큰 오디오 청크를 받는 도중에 화면을 떠나도 끝까지 받는다.
    let work = async {
        let response = builder.send().await?;
        let status = response.status().as_u16();
        let mut headers = HashMap::new();
        let mut got_cookie = false;
        for (key, value) in response.headers() {
            let name = key.as_str().to_ascii_lowercase();
            if name == "set-cookie" {
                got_cookie = true;
                // Set-Cookie 는 화면에 넘기지 않는다. 쿠키는 여기가 관리한다.
                continue;
            }
            if let Ok(text) = value.to_str() {
                headers.insert(name, text.to_string());
            }
        }
        let body = response.bytes().await?;
        Ok::<_, reqwest::Error>((status, headers, got_cookie, body))
    };

    let outcome = tokio::select! {
        result = work => Some(result),
        _ = &mut receiver => None,
    };

    if let Some(id) = &request.request_id {
        http.inflight
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .remove(id);
    }

    let Some(result) = outcome else {
        return Err("요청이 취소됐어요.".to_string());
    };
    let (status, headers, got_cookie, body) = result.map_err(|error| {
        if error.is_timeout() {
            "요청 시간이 초과됐어요.".to_string()
        } else {
            format!("네트워크 요청에 실패했어요: {error}")
        }
    })?;

    if got_cookie {
        http.persist_cookies(&db);
    }

    Ok(Response::new(frame(status, headers, &body)?))
}

/// 진행 중인 요청을 끊는다. 이미 끝났으면 아무 일도 일어나지 않는다.
#[tauri::command]
pub fn http_abort(request_id: String, http: tauri::State<'_, HttpState>) {
    let sender = http
        .inflight
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .remove(&request_id);
    if let Some(sender) = sender {
        let _ = sender.send(());
    }
}

/// 기기에 남은 인증 흔적을 지운다. 로그아웃이 실패해도 부른다.
#[tauri::command]
pub fn http_clear_cookies(http: tauri::State<'_, HttpState>, db: tauri::State<'_, Db>) {
    http.clear_cookies(&db);
}
