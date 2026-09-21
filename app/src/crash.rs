//! 예외(패닉·오류)를 모아 밖으로 내보내는 계층.
//!
//! 티켓 S15P21A204-383 — *"Tauri Rust 예외는 물론 프론트엔드(JS) 예외도 기록한다."*
//! 그래서 이 모듈은 **Rust 와 JS 양쪽에서 같은 길**로 들어온다.
//!
//! ```text
//! Rust 패닉 ─────────▸ panic::set_hook ────┐
//! Result::Err(로그) ─▸ install_log_bridge ─┤─▸ 보고 큐 ─▸ 백그라운드 전송 ─▸ 수집기
//! JS 예외 ─▸ window.onerror ─▸ crash_report ┘
//! ```
//!
//! # 왜 이렇게 생겼나 (설계 근거)
//!
//! ## 0. 로그 출력은 이 모듈이 만들지 않는다 (S15P21A204-353 과의 경계)
//!
//! `log` 파사드에 로거가 없어 진단 20곳이 조용히 버려지던 문제는 **이미
//! 해결돼 있었다** — `app/src/logging.rs`(`68f4b62`, S15P21A204-353)가 stdout
//! 로거를 세우고 접두사 `[mupid-LEVEL]`·최대 레벨 `Info` 계약을 시험으로
//! 박아 두었다. 이 모듈은 그 **뒤에** 붙어(`install_log_bridge`) 경고 이상을
//! 예외 보고로도 흘려보낼 뿐, **로거를 새로 만들지 않는다** — 등록할 수 있는
//! 로거는 하나뿐이고, 둘을 두면 서로 다른 접두사와 레벨을 주장하게 된다.
//!
//! ## 1. 의존성을 늘리지 않는다
//!
//! 이 크레이트는 필요한 것만 들고 있다(`Cargo.toml` 의 방침). Rust 쪽 공식
//! Crashlytics 크레이트는 없고(Firebase 의 공식 지원은 Android·iOS·Flutter·Unity
//! 뿐이다), 대안인 `sentry` 크레이트는 의존성 트리를 수십 개 늘린다. 그래서
//! 이미 있는 `reqwest`·`serde_json`·`log`·`sha2`·`tauri` 만 쓴다.
//!
//! ## 2. 전송 실패가 앱을 깨뜨리지 않는다 (불변 조건)
//!
//! **이 앱은 오프라인에서도 재생되어야 한다.** 그래서
//!
//! - `report()` 는 **큐에 넣기만** 하고 곧바로 돌려준다. 패닉 훅도, 화면이
//!   부르는 커맨드도 네트워크를 기다리지 않는다 — 기다리면 예외 하나가
//!   화면을 멈춰 세운다.
//! - 실제 전송은 **따로 도는 수집 루프**가 한다. 실패는 `let _ =` 로 버리고
//!   **재시도하지 않는다.** 실패한 보고를 다시 보내려다 뒤따르는 보고가
//!   막히면, 고칠 수 없는 한 건 때문에 고칠 수 있는 것까지 잃는다
//!   (`frontend/lib/analytics/transport.ts` 의 `PermanentTransportError` 와 같은 판단).
//! - 큐 상한을 넘으면 **오래된 것부터** 버린다. 무한정 쌓으면 예외 폭주가
//!   곧 메모리 폭주가 된다.
//!
//! ## 3. 키가 없으면 조용히 비활성이다
//!
//! 엔드포인트·키는 `MUPID_CRASHLYTICS_ENDPOINT` / `MUPID_CRASHLYTICS_API_KEY`
//! 에서 온다(`config.rs`). 둘 중 하나라도 비어 있으면 `enabled()` 가 거짓이고
//! **큐에 아무것도 쌓이지 않는다.** 수집 루프도 뜨지 않는다. 앱 기동 경로는
//! `enabled()` 를 한 번 부를 뿐이라, 키가 없는 빌드와 있는 빌드의 기동 차이가 없다.
//!
//! # ⚠️ 이 전송은 비공개(undocumented) 통로다
//!
//! `MUPID_CRASHLYTICS_ENDPOINT` 가 가리키는 것은 **공개 API 가 아니다.**
//! Firebase Crashlytics 의 실제 수집 경로는 SDK 가 쓰는
//! `crashlyticsreports-pa.googleapis.com/v1/firelog/legacy/batchlog` 이고,
//! 본문이 **protobuf**(`BatchedLogRequest`)이며 Firebase Installations 가
//! 발급한 자격을 요구한다. Google 이 공개한 REST API
//! (`firebasecrashlytics.googleapis.com/v1alpha/...`)는 **이슈를 읽고 상태를
//! 바꾸는** 쪽이라 보고를 올릴 수 없다.
//!
//! 그래서 여기서는 **엔드포인트를 하드코딩하지 않는다.** 설정으로 받는다.
//! 그 자리에 무엇을 넣을지는 SDK 없이 받을 수 있는 수집기를 정한 사람이 정한다
//! (운영 Crashlytics 가 아니라 사내 수집기일 수도 있다).
//!
//! **위험을 명시한다:** 이 통로는 예고 없이 바뀔 수 있고, 바뀌면 위의 불변
//! 조건 덕분에 **앱은 멀쩡한 채 조용히 아무것도 안 보내게 된다.** 그래서
//! `crash_report_status` 커맨드로 **밖에서 확인할 수 있게** 해 두었다 —
//! "조용히 죽었다" 와 "보낼 게 없었다" 를 구분하려면 그 통로가 있어야 한다
//! (`commands.rs` 의 `assets_status` 와 같은 이유).
//!
//! ## 실제 Crashlytics 로 보내는 길 — 두 갈래 (S15P21A204-383 2단계, B안)
//!
//! 전송로가 **둘**이고, 기기에서 무엇이 살아 있는지에 따라 자동으로 갈린다.
//!
//! ```text
//! ① Firebase Crashlytics SDK  (정공법, B안 — 이번에 붙였다)
//!    google-services.json → Gradle 플러그인 → FirebaseApp 초기화
//!    Kotlin `CrashlyticsPlugin` ← Rust 가 run_mobile_plugin 으로 넘긴다
//!    ─ 비공개 API 를 쓰지 않고 **심볼화된 스택트레이스**까지 얻는다.
//!    ─ 엔드포인트·키가 **필요 없다.** 이게 B안의 요점이다.
//!
//! ② 비공개 배치 수집 엔드포인트  (1단계가 만든 길, 그대로 남긴다)
//!    MUPID_CRASHLYTICS_ENDPOINT + MUPID_CRASHLYTICS_API_KEY 둘 다 있을 때만
//! ```
//!
//! **우선순위는 ①이다.** SDK 싱크가 등록돼 있으면(=`sdk_registered()`, 안드로이드
//! 빌드에 `google-services.json` 이 들어가 FirebaseApp 이 초기화된 경우) 그쪽으로
//! 보내고, 없으면 ②로 간다. ②를 지우지 않은 이유는 두 가지다 — ①이 아직
//! 실기기에서 검증되지 않았고(빌드까지만 확인했다), 사내 수집기를 쓰는 빌드가
//! 있을 수 있기 때문이다.
//!
//! ### 로그와 예외를 섞지 않는다 (`Kind`)
//!
//! Crashlytics SDK 에는 `log()`(빵부스러기)와 `recordException()`(비정상 종료
//! 이벤트)이 **따로** 있다. 1단계는 로그 브리지가 흘려보낸 경고를 예외 보고와
//! 같은 큐에 넣었다 — HTTP 수집기에는 "보고" 하나뿐이라 그게 맞았다.
//! SDK 쪽은 다르다. `log::warn!` 하나하나를 `recordException` 으로 올리면
//! **비정상 종료율이 부풀고 진짜 예외가 그 속에 묻힌다.** 그래서 보고에
//! `Kind` 를 붙여 갈래를 나눈다 — 패닉·JS 예외는 `recordException`,
//! 로그 브리지가 보낸 것은 `log` 로 간다(`Kind::native_command`).
//!
//! ### 무엇이 아직 검증되지 않았나 (정직하게)
//!
//! **실기기·Crashlytics 대시보드 도달은 확인하지 않았다.** 확인한 것은
//! ① Gradle 이 두 플러그인과 의존성을 해석한다는 것, ② `google-services.json`
//! 이 `applicationId` 와 맞는다는 것, ③ 이 모듈의 배선과 갈래 판단이
//! 시험으로 고정됐다는 것까지다. "예외가 대시보드에 떴다" 고는 말하지 않는다.
//! 또한 **`google-services.json` 은 커밋되지 않는다**(`app/.gitignore:29`) —
//! CI 는 이 파일을 **아직 주입하지 않는다**(`infra/ci/build-apk.sh` 는 키스토어만
//! 넣는다). 그래서 지금 CI 에서 그대로 빌드하면 google-services 플러그인이
//! `missingGoogleServicesStrategy=ERROR` 기본값으로 **실패한다.** 그 결정
//! (CI 주입 vs `IGNORE`/`WARN` 전략)은 `infra/` 소관이라 여기서 정하지 않았다.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};

/// 큐 상한. 넘으면 **오래된 것부터** 버린다.
///
/// 예외 폭주(렌더 루프에서 매 프레임 던지는 JS 등)가 곧 메모리 폭주가 되면
/// 안 된다. 200건이면 한 세션에서 봐야 할 것은 거의 다 들어오고, 본문은
/// 장당 4KB 로 잘리므로 최악이 800KB 다.
const QUEUE_LIMIT: usize = 200;

/// 한 번에 내보내는 최대 건수. 이만큼 모이면 기다리지 않고 바로 나간다 —
/// 예외가 쏟아지는 동안 큐가 계속 차 있는데도 1.5초씩 쉬면 밀린다.
const BATCH_LIMIT: usize = 20;

/// 보고 본문 상한(바이트). 스택트레이스가 수 MB 인 경우가 있는데 그것을
/// 그대로 들고 있으면 위의 상한 계산이 무너진다.
const MESSAGE_LIMIT: usize = 4096;

/// 한 건을 보낼 때 허용하는 시간. **오프라인에서 오래 매달리지 않는다.**
const SEND_TIMEOUT: Duration = Duration::from_secs(5);

/// 수집 루프가 한 번 도는 간격. 보고가 띄엄띄엄 와도 이만큼 모아서 보내고,
/// 조용할 때는 이 간격으로 한 번씩만 깨어난다.
const COLLECT_INTERVAL: Duration = Duration::from_millis(1500);

/// 이 기기·설치를 가리키는 값. **계정이 아니라 설치 단위**다 — 사용자가
/// 바뀌어도 같은 설치면 같은 값이고, 그래야 "한 기기에서 몇 번 났나" 를 셀 수 있다.
const INSTALL_KEY: &str = "crash.install_id";

/// 보고 낸 누적 건수(전송 시도 기준). `crash_report_status` 가 읽는다.
static SENT: AtomicUsize = AtomicUsize::new(0);

/// 로그 브리지가 걸렸는가. `status()` 가 밖에 알린다.
static BRIDGED: AtomicBool = AtomicBool::new(false);

/// 큐와 수집 루프의 시작 여부.
static QUEUE: OnceLock<Mutex<VecDeque<CrashReport>>> = OnceLock::new();
static COLLECTOR: OnceLock<()> = OnceLock::new();
/// 프로세스 동안 유지되는 설치 식별자. 저장소가 열리기 전에도 쓸 수 있어야 한다.
static INSTALL: OnceLock<String> = OnceLock::new();

// ─────────────────────────────────────────────────────────────
// 보고의 모양
// ─────────────────────────────────────────────────────────────

/// 어디서 났는가. Rust 쪽과 JS 쪽을 구분한다.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Source {
    Rust,
    Js,
}

impl Source {
    fn as_str(self) -> &'static str {
        match self {
            Source::Rust => "rust",
            Source::Js => "js",
        }
    }
}

/// 이 보고가 **예외인가 로그인가.**
///
/// Crashlytics SDK 의 두 통로가 서로 다른 뜻을 갖기 때문에 필요하다.
///
/// - [`Kind::Exception`] — 실제 예외. 패닉, JS `window.onerror`, `unhandledrejection`.
///   SDK 의 `recordException()` 으로 간다. **비정상 종료율에 잡히는 것이 맞다.**
/// - [`Kind::Log`] — 로그 브리지가 흘려보낸 경고·오류. SDK 의 `log()` 로 가서
///   다음 예외에 붙는 **빵부스러기**가 된다. 예외로 올리면 안 된다 — 그러면
///   경고 한 줄마다 이벤트가 하나씩 생겨 대시보드가 오염되고, 정작 봐야 할
///   예외가 그 속에 묻힌다.
///
/// HTTP 경로(②)에는 이 구분이 원래 없었다 — 수집기가 하나뿐이라 전부 "보고"였다.
/// 그래서 본문에 `kind` 를 실어 **수집기가 거를 수 있게** 했다. 기존 수집기가
/// 이 필드를 모르더라도 무시하면 그만이라 호환은 깨지지 않는다.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Kind {
    Exception,
    Log,
}

impl Default for Kind {
    /// 없으면 예외로 본다 — 1단계가 만든 보고는 전부 예외였기 때문이다.
    /// (로그 브리지 경로만 이 값을 명시적으로 덮어쓴다.)
    fn default() -> Self {
        Kind::Exception
    }
}

impl Kind {
    fn as_str(self) -> &'static str {
        match self {
            Kind::Exception => "exception",
            Kind::Log => "log",
        }
    }

    /// Kotlin `CrashlyticsPlugin` 의 어느 커맨드로 보낼 것인가.
    ///
    /// **이 판단이 여기 한 곳에만 있어야 한다.** Rust 쪽과 Kotlin 쪽이 각자
    /// "이건 예외인가" 를 정하면 두 판단이 갈라지고, 갈라진 쪽이 대시보드를
    /// 오염시킨다. Kotlin 은 넘어온 커맨드 이름만 보고 그대로 실행한다.
    ///
    /// 데스크톱에서는 부르는 곳(`android_sink`)이 컴파일되지 않지만, **이
    /// 함수는 남긴다** — 매핑 자체가 계약이고 시험이 그걸 고정한다
    /// (`logs_and_exceptions_take_different_commands`).
    #[cfg_attr(not(target_os = "android"), allow(dead_code))]
    fn native_command(self) -> &'static str {
        match self {
            Kind::Exception => "recordException",
            Kind::Log => "log",
        }
    }
}

/// 보고 한 건. 화면이 넘기는 모양(`JsCrash`)과 큐가 들고 있는 모양이 같다.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CrashReport {
    pub source: Source,
    /// **예외인가 로그인가.** SDK 싱크가 `recordException` 과 `log` 중 어디로
    /// 보낼지 정하는 근거다(`Kind`). HTTP 본문에도 `kind` 로 실린다.
    #[serde(default)]
    pub kind: Kind,
    /// 패닉 메시지, 혹은 JS 오류 메시지.
    pub message: String,
    /// 패닉 위치(`file:line:col`) 또는 JS 스택.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub stack: Option<String>,
    /// 화면이 알려 준 자리(라우트·컴포넌트 등). 진단에만 쓴다.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub context: Option<String>,
    /// 발생 시각(epoch ms).
    pub at_ms: i64,
    /// 이 보고를 올린 앱이 몇 번 빌드인가 (`update.rs` 의 `local_version_code`).
    pub version_code: u64,
    /// 앱이 아는 웹 번들의 나이. 화면 문제인지 셸 문제인지 가르는 단서다.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub web_build: Option<u64>,
    /// 설치 식별자. 계정과 무관하고 개인을 가리키지 않는다.
    pub install_id: String,
}

impl CrashReport {
    /// 본문 길이를 제한한다. **char 경계에서 자른다** — 바이트로 자르면
    /// 한글이 깨지고, 그러면 JSON 직렬화가 통째로 실패해 보고가 사라진다.
    fn trimmed(text: &str, limit: usize) -> String {
        if text.len() <= limit {
            return text.to_string();
        }
        let mut end = limit;
        while end > 0 && !text.is_char_boundary(end) {
            end -= 1;
        }
        let mut out = String::with_capacity(end + 3);
        out.push_str(&text[..end]);
        out.push('…');
        out
    }

    /// 큐에 넣기 전에 크기를 정리한다.
    fn clamp(mut self) -> Self {
        self.message = Self::trimmed(&self.message, MESSAGE_LIMIT);
        if let Some(stack) = &self.stack {
            self.stack = Some(Self::trimmed(stack, MESSAGE_LIMIT * 2));
        }
        if let Some(context) = &self.context {
            self.context = Some(Self::trimmed(context, 512));
        }
        self
    }

    /// 화면이 넘긴 보고에 이 기기의 값을 채운다.
    pub fn from_js(value: JsCrash) -> Self {
        Self {
            source: Source::Js,
            kind: Kind::Exception,
            message: value.message,
            stack: value.stack,
            context: value.context,
            at_ms: value.at_ms.unwrap_or_else(now_ms),
            version_code: crate::update::local_version_code(),
            web_build: Some(crate::update::local_web_build()),
            install_id: install_id(None),
        }
    }

    /// Rust 쪽(패닉·로그)에서 만드는 기본형.
    ///
    /// `kind` 를 인자로 받는다 — 같은 Rust 출처라도 **패닉은 예외**로,
    /// **로그 브리지가 보낸 경고는 로그**로 가야 하기 때문이다(`Kind`).
    pub fn from_rust(message: String, stack: Option<String>, context: &str, kind: Kind) -> Self {
        Self {
            source: Source::Rust,
            kind,
            message,
            stack,
            context: Some(context.to_string()),
            at_ms: now_ms(),
            version_code: crate::update::local_version_code(),
            web_build: Some(crate::update::local_web_build()),
            install_id: install_id(None),
        }
    }
}

/// 화면이 넘기는 인자. `commands::crash_report` 의 짝이다.
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JsCrash {
    pub message: String,
    #[serde(default)]
    pub stack: Option<String>,
    #[serde(default)]
    pub context: Option<String>,
    /// 화면이 직접 잡은 시각. 없으면 받은 시각을 쓴다.
    #[serde(default)]
    pub at_ms: Option<i64>,
}

// ─────────────────────────────────────────────────────────────
// 설정 — 키가 없으면 조용히 비활성
// ─────────────────────────────────────────────────────────────

/// 런타임 환경변수를 먼저 본다 — 비밀값을 실행 파일에 박지 않고 주입할 수
/// 있어야 한다. 없으면 빌드 시점 값(`option_env!`)으로 내려앉는다
/// (`config.rs` 의 `api_origin` 과 같은 규칙).
fn read_env(runtime: &str, build: Option<&'static str>) -> Option<String> {
    std::env::var(runtime)
        .ok()
        .or_else(|| build.map(str::to_string))
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
}

pub fn endpoint() -> Option<String> {
    read_env(
        "MUPID_CRASHLYTICS_ENDPOINT",
        option_env!("MUPID_CRASHLYTICS_ENDPOINT"),
    )
}

pub fn api_key() -> Option<String> {
    read_env(
        "MUPID_CRASHLYTICS_API_KEY",
        option_env!("MUPID_CRASHLYTICS_API_KEY"),
    )
}

/// 지금 이 기기에서 보고를 내보내는가.
///
/// **키와 엔드포인트가 둘 다 있어야** 켜진다. 하나만 있으면 꺼진다 —
/// 엔드포인트만 있고 키가 없으면 인증 없이 남의 수집기에 밀어 넣는 셈이다.
///
/// 거짓이면 `report()` 가 **아무것도 하지 않는다.** 앱 기동 경로는 이 함수를
/// 한 번 부를 뿐이라 키 유무가 기동에 영향을 주지 않는다.
pub fn enabled() -> bool {
    sdk_registered() || (endpoint().is_some() && api_key().is_some())
}

/// 이 기기에서 **실제로 쓸 전송로**를 이름으로 알린다. `status()` 가 밖에 알린다.
///
/// `crash_report_status` 의 판단 기준이 1단계와 달라진 자리다. 1단계는
/// "엔드포인트·키가 있나" 를 물었고 그게 곧 켜짐이었다. B안에서는 **SDK 가
/// 붙었으면 키 없이도 켜진다** — 그래서 무엇으로 보내는지를 따로 알려야
/// "왜 키가 없는데 켜져 있지" 또는 그 반대를 밖에서 판단할 수 있다.
fn route() -> &'static str {
    if sdk_registered() {
        "sdk"
    } else if enabled() {
        "endpoint"
    } else {
        "off"
    }
}

// ─────────────────────────────────────────────────────────────
// ① Firebase Crashlytics SDK 싱크 (B안)
// ─────────────────────────────────────────────────────────────

/// 안드로이드 앱이 초기화됐는가. **시험에서는 항상 거짓이다.**
///
/// 데스크톱에는 Crashlytics 가 없다 — 그래서 이 값이 거짓이면 전부 ②(HTTP)
/// 로 가고, 1단계가 만든 기존 동작·시험이 그대로 산다. 그게 **키 없으면
/// 비활성** 계약을 건드리지 않는 방법이다.
#[cfg(target_os = "android")]
fn sdk_registered() -> bool {
    android_sink::is_registered()
}

#[cfg(not(target_os = "android"))]
fn sdk_registered() -> bool {
    false
}

/// Rust → Kotlin `CrashlyticsPlugin` 다리.
///
/// # 왜 `update_native.rs`·`media.rs` 와 모양이 다른가 (설계 근거)
///
/// 그 둘은 **커맨드에서** 플러그인을 부른다(Tauri 가 메인 스레드에서 부르므로
/// 핸들이 준비돼 있다). 그런데 이 모듈의 보고는 **패닉 훅에서도** 나온다.
/// 패닉 훅은 아무 스레드에서나 돌고, 그때 JNI 로 Activity 를 건드리면
///  - `PluginHandle` 은 내부적으로 호출이 끝날 때까지 **블로킹으로 기다리고**
///    (`plugin/mobile.rs` 의 `rx.recv()`),
///  - 안드로이드 `PluginManager.runCommand` 는 **메인 스레드에서 커맨드를
///    실행한다**(`@Command` 핸들러가 `activity` 를 그대로 만지므로 그럴 수밖에 없다).
///
/// 그래서 메인 스레드에서 죽은 경우 그대로 블로킹하면 **영원히 안 돌아온다.**
/// 이미 예외를 처리하는 중에 교착이 걸리면 그 교착이 곧 데이터 손실이다.
/// 그래서 두 가지를 강제한다.
///
///  1. **보내기는 절대 블로킹하지 않는다** — 별도 스레드로 넘기고 즉시 돌려준다.
///     `report()` 가 이미 그 계약이다(패닉 훅도 화면 커맨드도 네트워크를 안 기다린다).
///  2. **받는 것은 `recordException`/`log` 두 커맨드뿐**이고, 인자는
///     `message`·`stack`·`context` 문자열 셋이다. 인자를 복잡하게 만들면
///     그만큼 실패할 자리가 늘고, 실패는 조용히 삼켜진다(불변 조건).
#[cfg(target_os = "android")]
mod android_sink {
    use super::{Kind, MESSAGE_LIMIT};
    use std::sync::OnceLock;
    use tauri::plugin::PluginHandle;

    /// `lib.rs` 의 `setup` 에서 넣는다. **설치 전에 부르면 조용히 아무 일도
    /// 일어나지 않는다** — 기동 순서상 그 전에 보고가 날 수 있고, 그때
    /// 훅을 세우지 않는 편이 안전하다(등록 전 부팅 보고는 로그로만 남는다).
    ///
    /// **`Runtime` 을 import 하지 않는다.** 이 `static` 은 generic `R` 을 담을 수
    /// 없어 `Wry` 로 구체화돼 있고, 그래서 `R: Runtime` 바운드가 필요 없다.
    /// (`use tauri::Runtime;` 이 여기 있었지만 아무 것도 바인딩하지 않아
    /// 안드로이드 빌드에서 `unused_imports` 경고를 냈다.)
    static SINK: OnceLock<PluginHandle<tauri::Wry>> = OnceLock::new();

    pub fn register(handle: PluginHandle<tauri::Wry>) {
        let _ = SINK.set(handle);
    }

    pub fn is_registered() -> bool {
        SINK.get().is_some()
    }

    /// Kotlin 이 받는 인자. 짝은 `CrashlyticsPlugin.kt` 의 `CrashArgs`.
    ///
    /// 본문은 여기서 이미 잘려 있다(`CrashReport::clamp`). SDK 쪽에도 한도가
    /// 있으므로 넘길 때 한 번 더 방어한다.
    #[derive(serde::Serialize)]
    #[serde(rename_all = "camelCase")]
    struct CrashArgs {
        message: String,
        stack: Option<String>,
        context: Option<String>,
    }

    /// 보고 하나를 SDK 로 넘긴다. **부르는 쪽을 막지 않는다.**
    ///
    /// `tauri::async_runtime::spawn_blocking` 을 쓴다 — `spawn`(비동기 태스크)에
    /// 블로킹 JNI 호출을 넣으면 워커 스레드 하나를 통째로 잡아먹는다.
    /// `spawn_blocking` 은 그 용도로 따로 두는 스레드 풀이라 안전하고,
    /// **호출자가 기다리지 않는다**는 점이 여기서는 더 중요하다.
    pub fn emit(kind: Kind, message: String, stack: Option<String>, context: Option<String>) {
        let Some(handle) = SINK.get() else {
            return;
        };
        // 핸들은 복제해 넘긴다(`PluginHandle: Clone`). `'static` 스레드로
        // 보내야 하기 때문이다.
        let handle = handle.clone();
        let args = CrashArgs {
            message: super::CrashReport::trimmed(&message, MESSAGE_LIMIT),
            stack: stack.map(|text| super::CrashReport::trimmed(&text, MESSAGE_LIMIT * 2)),
            context,
        };
        let command = kind.native_command();
        tauri::async_runtime::spawn_blocking(move || {
            // 실패는 **삼킨다**(불변 조건). SDK 가 없는 빌드·초기화 전 호출·
            // 커맨드 이름 오타가 전부 여기로 오는데, 그것 때문에 앱이 죽으면
            // 안 된다. `log` 로만 남긴다 — 로그 브리지가 이 줄을 다시 보고로
            // 올리지 않도록 `warn!` 이 아니라 `debug!` 를 쓴다(무한 증식 방지).
            if handle.run_mobile_plugin::<()>(command, args).is_err() {
                log::debug!("Crashlytics SDK 로 보내지 못했습니다 ({command})");
            }
        });
    }
}

// ─────────────────────────────────────────────────────────────
// 큐
// ─────────────────────────────────────────────────────────────

fn queue() -> &'static Mutex<VecDeque<CrashReport>> {
    QUEUE.get_or_init(|| Mutex::new(VecDeque::new()))
}

/// 보고 하나를 어느 전송로로 보낼지 고르는 자리.
///
/// **여기가 유일한 분기점이다.** 이 위로는(패닉 훅·로그 브리지·JS 커맨드)
/// "무엇이 났는가"만 알고, 아래로는(SDK·HTTP) "어디로 가는가"만 안다.
/// 전송로가 늘어도 이 함수만 고치면 되고, 판단이 두 곳으로 갈라지지 않는다.
///
/// **여기서 네트워크를 건드리지 않는다.** 패닉 훅과 JS 커맨드가 함께 쓰는
/// 자리이고, 둘 다 빠르게 돌려줘야 한다.
///
/// ## SDK 가 있을 때 큐를 건너뛰는 이유
///
/// 수집 루프(②)는 **살아 있는 프로세스에서** 큐를 비워 보내는 장치다. SDK 는
/// 다르게 논다 — 자체적으로 디바이스에 쌓아 두었다가 **다음 기동에** 올린다.
/// 그래서 SDK 로 갈 때는 우리 큐를 거칠 이유가 없다. 오히려 거치면 손해다 —
/// 비정상 종료로 프로세스가 죽으면 그 큐는 통째로 사라지고, 정작 SDK 가
/// 받았어야 할 마지막 예외를 못 넘긴다.
pub fn report(report: CrashReport) {
    if !enabled() {
        return;
    }
    // ① SDK 경로. 넘기기만 하고 곧바로 돌아온다(`android_sink::emit` 참고 —
    //    블로킹하지 않는 것이 계약이다).
    if sdk_registered() {
        let report = report.clamp();
        // `context` 가 없으면 출처라도 붙인다 — 대시보드에서 rust/js 를
        // 가르는 단서가 사라지면 1단계의 핵심 요구가 무너진다.
        let context = report
            .context
            .clone()
            .or_else(|| Some(report.source.as_str().to_string()));
        sdk_emit(report.kind, report.message, report.stack, context);
        SENT.fetch_add(1, Ordering::Relaxed);
        return;
    }
    // ② HTTP 경로 (1단계 그대로).
    let mut guard = queue().lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    while guard.len() >= QUEUE_LIMIT {
        guard.pop_front();
    }
    guard.push_back(report.clamp());
}

/// SDK 로 넘기는 자리. 데스크톱에서는 **컴파일만 되게 자리를 둔다.**
///
/// `sdk_registered()` 가 데스크톱에서 항상 거짓이라 여기까지 오지 않는다 —
/// 그래도 함수가 있어야 위 `report()` 가 어느 타깃에서나 컴파일된다.
#[cfg(target_os = "android")]
fn sdk_emit(kind: Kind, message: String, stack: Option<String>, context: Option<String>) {
    android_sink::emit(kind, message, stack, context);
}

#[cfg(not(target_os = "android"))]
fn sdk_emit(kind: Kind, message: String, stack: Option<String>, context: Option<String>) {
    // 도달하지 않는다. 인자를 소비해 경고만 막는다.
    let _ = (kind, message, stack, context);
}

/// 큐에서 최대 `BATCH_LIMIT` 건을 꺼낸다. 수집 루프만 부른다.
fn take_batch() -> Vec<CrashReport> {
    let mut guard = queue().lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    let count = guard.len().min(BATCH_LIMIT);
    guard.drain(..count).collect()
}

/// 큐를 통째로 비운다. **시험 전용이다.**
///
/// 큐는 프로세스 전역이라 시험이 병렬로 돌면 서로의 보고가 섞인다 —
/// 실제로 그렇게 실패했다("키가 없는데 큐에 쌓였다" 가 142건을 봤다).
/// 그래서 큐를 보는 시험은 전부 하나의 잠금으로 직렬화하고, 시작할 때
/// 비운다(`tests` 모듈의 `TEST_LOCK`).
#[cfg(test)]
fn reset_queue() {
    let mut guard = queue().lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    guard.clear();
}

// ─────────────────────────────────────────────────────────────
// 수집 루프와 전송
// ─────────────────────────────────────────────────────────────

/// 수집 루프를 띄운다. **키가 없으면 아무 일도 하지 않는다.**
///
/// `tauri::async_runtime` 을 쓴다 — 이 크레이트의 `tokio` 는 `time`·`sync`
/// 기능만 켜 두었고 런타임은 Tauri 가 만든다. 여기서 `tokio::runtime` 을
/// 직접 쓰려면 `rt` 기능이 더 필요한데, 그건 의존성 방침을 건드리는 일이다.
///
/// 기동 때 한 번만 부른다. 여러 번 불러도 루프는 하나다.
pub fn start_collector() {
    if !enabled() {
        return;
    }
    if COLLECTOR.set(()).is_err() {
        return;
    }
    tauri::async_runtime::spawn(collector_loop());
}

/// 큐를 비우는 루프.
///
/// 기다렸다가 모아서 보낸다 — 예외가 쏟아질 때 건당 한 번씩 요청을 만들면
/// 네트워크와 배터리를 그만큼 더 쓴다. 조용할 때는 이 간격으로 한 번씩만
/// 깨어나므로 비용이 거의 없다.
async fn collector_loop() {
    loop {
        tokio::time::sleep(COLLECT_INTERVAL).await;
        for report in take_batch() {
            // 실패는 **버린다.** 재시도하지 않는다(모듈 상단의 불변 조건).
            send(&report).await;
            SENT.fetch_add(1, Ordering::Relaxed);
        }
    }
}

/// 보고 하나를 실제로 보낸다. **모든 실패는 삼킨다.**
async fn send(report: &CrashReport) {
    let (Some(endpoint), Some(key)) = (endpoint(), api_key()) else {
        return;
    };

    let body = match serde_json::to_vec(&serde_json::json!({
        "source": report.source.as_str(),
        // 예외인가 로그인가. 기존 수집기는 이 필드를 모르면 무시하면 그만이라
        // 호환이 깨지지 않는다(`Kind`).
        "kind": report.kind.as_str(),
        "message": report.message,
        "stack": report.stack,
        "context": report.context,
        "atMs": report.at_ms,
        "versionCode": report.version_code,
        "webBuild": report.web_build,
        "installId": report.install_id,
        "platform": std::env::consts::OS,
    })) {
        Ok(value) => value,
        Err(_) => return,
    };

    let client = match reqwest::Client::builder().timeout(SEND_TIMEOUT).build() {
        Ok(value) => value,
        Err(_) => return,
    };

    // 응답 본문은 보지 않는다. 필요한 것은 "갔는가" 뿐이고, 수집기가 무엇을
    // 돌려주든 여기서 할 일이 없다.
    let _ = client
        .post(&endpoint)
        .header("X-Mupid-Crash-Key", key)
        .header("Content-Type", "application/json")
        .body(body)
        .send()
        .await;
}

// ─────────────────────────────────────────────────────────────
// 설치 식별자
// ─────────────────────────────────────────────────────────────

fn hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push(DIGITS[(byte >> 4) as usize] as char);
        out.push(DIGITS[(byte & 0x0f) as usize] as char);
    }
    out
}

/// 설치 식별자. 없으면 만들어 DB 에 남긴다.
///
/// `sha2` 는 이미 의존성에 있고(자동 업데이트가 쓴다) 새 크레이트를 들이지
/// 않으려고 여기서도 같은 것을 쓴다. 시각·프로세스 번호를 섞은 것이라
/// **개인과 무관**하고, 앱을 지웠다 깔면 새 값이 된다.
///
/// 저장소가 아직 없을 때(패닉 훅을 거는 시점)는 프로세스 안에서만 유지되는
/// 값으로 시작하고, 나중에 `bind_install_id` 가 DB 값과 맞춘다.
fn install_id(db: Option<&crate::db::Db>) -> String {
    if let Some(cached) = INSTALL.get() {
        return cached.clone();
    }
    if let Some(db) = db {
        match crate::store::kv::get(db, INSTALL_KEY) {
            Ok(Some(saved)) if !saved.trim().is_empty() => {
                let _ = INSTALL.set(saved.clone());
                return saved;
            }
            Ok(_) => {}
            Err(error) => log::warn!("설치 식별자를 읽지 못했습니다: {error}"),
        }
    }

    let seed = format!("{}:{}:{}", now_ms(), std::process::id(), std::env::consts::OS);
    let digest = {
        use sha2::{Digest, Sha256};
        let mut hasher = Sha256::new();
        hasher.update(seed.as_bytes());
        hex(&hasher.finalize())
    };
    let value = digest[..32].to_string();

    if let Some(db) = db {
        // 저장 실패는 치명적이지 않다 — 이번 프로세스 동안만 달라질 뿐이다.
        if let Err(error) = crate::store::kv::put(db, INSTALL_KEY, &value) {
            log::warn!("설치 식별자를 저장하지 못했습니다: {error}");
        }
    }
    // 먼저 들어간 값이 이긴다. 경합해도 한쪽으로 정해지기만 하면 된다.
    let _ = INSTALL.set(value.clone());
    value
}

/// 저장소가 열린 뒤 설치 식별자를 DB 값으로 확정한다.
///
/// 기동 때 한 번 부른다. 첫 보고 전에 정해져야 앱을 껐다 켜도 같은 값이
/// 남는다 — 그래야 "설치 몇 개에서 났나" 를 셀 수 있다.
pub fn bind_install_id(db: &crate::db::Db) {
    let _ = install_id(Some(db));
}

/// 지금 시각(밀리초).
fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|value| value.as_millis() as i64)
        .unwrap_or(0)
}

// ─────────────────────────────────────────────────────────────
// Rust 쪽 입구 두 개
// ─────────────────────────────────────────────────────────────

/// 패닉 훅을 건다. **기본 훅을 지우지 않고 감싼다** — 지우면 `RUST_BACKTRACE`
/// 로 보던 출력이 사라져 로컬 디버깅이 나빠진다.
///
/// 백트레이스는 여기서 만들지 않는다. 비용이 큰 작업을 예외 처리 경로에
/// 얹지 않기 위해서다 — 위치(`file:line:col`)와 메시지만으로 묶기에 충분하다.
pub fn install_panic_hook() {
    if !enabled() {
        return;
    }
    let previous = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        let location = info
            .location()
            .map(|spot| format!("{}:{}:{}", spot.file(), spot.line(), spot.column()));
        // **패닉은 뒤늦게 도착해도 잃지 않는다.**
        //
        // `report()` 는 SDK 가 있으면 자체 저장소에 쌓아 다음 기동에 올린다.
        // 그 뒤 앱이 죽어도 보고는 디바이스에 남는다. 그게 이 순서의 요점이다 —
        // 훅이 **패닉 위치를 붙여** 넘길 때 `info.location()` 이 아직 살아 있다.
        report(CrashReport::from_rust(
            panic_message(info),
            location,
            "panic",
            Kind::Exception,
        ));
        previous(info);
    }));
}

fn panic_message(info: &std::panic::PanicHookInfo<'_>) -> String {
    if let Some(text) = info.payload().downcast_ref::<&str>() {
        (*text).to_string()
    } else if let Some(text) = info.payload().downcast_ref::<String>() {
        text.clone()
    } else {
        "패닉 (메시지를 읽지 못했습니다)".to_string()
    }
}

/// `log` 를 타고 흐르는 **경고·오류를** 보고로 옮긴다.
///
/// # develop 과 겹쳐서 이렇게 정리했다 (S15P21A204-353 ↔ 383)
///
/// 이 자리에는 원래 `log` 구현체(`struct Logger`)가 통째로 있었다. 그런데
/// 리베이스해 보니 **`68f4b62`(S15P21A204-353, `app/src/logging.rs`)가 이미
/// 같은 일을 하고 있었다** — `log` 파사드에 로거가 없어 20곳의 진단이 조용히
/// 버려지던 것을 stdout 로거로 살린 작업이다. 접두사 `[mupid-LEVEL]`, 개행
/// 눌러 담기, `Info` 최대 레벨이라는 **계약이 이미 문서·시험에 박혀 있었다**
/// (`adb logcat | grep mupid-`, `logging.rs` 시험 5개).
///
/// 그래서 **로거를 둘 두지 않는다.** 등록할 수 있는 것은 어차피 하나뿐이고
/// (`log::set_logger` 는 프로세스당 한 번), 둘을 남기면 서로 다른 접두사와
/// 다른 최대 레벨을 주장하게 된다. **출력은 `logging.rs` 가 맡고, 여기서는
/// 그 출력을 예외 보고로도 흘려보내는 일만 한다.**
///
/// 구현은 `logging::forward_to` 로 후크를 꽂는 것이다. "이미 세워진 로거를
/// 감싸는" 방법은 없다 — `log::logger()` 가 현재 로거를 꺼내는 공개 API 를
/// 주지 않기 때문이다(`&'static dyn Log` 를 그대로 준다). 그래서 로거를
/// 세우는 쪽이 자리를 열어 두었다(`logging.rs` 의 `ON_RECORD`).
fn forward(record: &log::Record) {
    // 경고 이상만 밖으로 낸다. `info!` 까지 올리면 업데이트 확인 같은 정상
    // 동작이 전부 보고가 된다 — 진단 로그와 예외 보고는 목적이 다르다.
    if record.level() > log::Level::Warn {
        return;
    }
    // **`Kind::Log` 로 보낸다.** Crashlytics SDK 는 `recordException`(비정상
    // 종료 이벤트)과 `log`(빵부스러기)를 따로 둔다. 경고 한 줄을 예외로
    // 올리면 비정상 종료율이 부풀고 **진짜 예외가 그 속에 묻힌다** — 그래서
    // 로그 브리지가 흘려보낸 것은 `log()` 로 간다.
    //
    // HTTP 경로(②)는 이 구분을 쓰지 않는다(수집기가 하나뿐이다). 다만 본문에
    // `kind` 가 실리므로 수집기가 거를 수 있다.
    report(CrashReport::from_rust(
        format!("{}: {}", record.level(), record.args()),
        record
            .file()
            .map(|file| format!("{}:{}", file, record.line().unwrap_or(0))),
        "log",
        Kind::Log,
    ));
}

/// `logging::init()` 이 세운 로거에 **경고 이상을 예외 보고로 보내는 후크를
/// 꽂는다.**
///
/// **`logging::init()` 뒤에 부른다**(`lib.rs` 의 순서). 여러 번 불러도
/// 후크는 하나만 등록된다.
///
/// 키가 없으면 `report()` 가 첫 줄에서 돌아서므로 보고는 조용히 꺼지고
/// **로그 출력은 그대로 남는다** — 그게 이 분리의 요점이다.
pub fn install_log_bridge() {
    crate::logging::forward_to(forward);
    BRIDGED.store(true, Ordering::Relaxed);
}

/// 브리지가 걸렸는가. `status()` 가 밖에 알린다.
///
/// 안 걸렸으면 경고가 예외 보고로 넘어가지 않는다 — "왜 예외가 안 올라오지"
/// 를 밖에서 판단할 근거다.
fn log_bridged() -> bool {
    BRIDGED.load(Ordering::Relaxed)
}

/// SDK 싱크를 설치한다. `crash_native.rs` 의 플러그인 `setup` 이 부른다.
///
/// **여기서 패닉 훅을 다시 걸지 않는다.** 훅은 `install_panic_hook` 에서
/// 이미 걸렸고, 그 훅은 `report()` 를 부른다 — 그 함수가 이 싱크를 보므로
/// 패닉도 자동으로 SDK 로 간다. 훅을 두 번 걸면 `previous` 가 자기 자신을
/// 감싸 무한 재귀가 된다(패닉 훅 안에서 패닉).
///
/// 데스크톱에서는 **아무 일도 하지 않는다** — `sdk_registered()` 가 언제나
/// 거짓이라 `report()` 가 ②(HTTP)로 간다. 그게 1단계 계약을 지키는 방법이다.
#[cfg(target_os = "android")]
pub fn register_sdk(handle: tauri::plugin::PluginHandle<tauri::Wry>) {
    android_sink::register(handle);
}

#[cfg(not(target_os = "android"))]
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
pub fn register_sdk(_handle: tauri::plugin::PluginHandle<tauri::Wry>) {
    // 데스크톱 개발 빌드. Crashlytics 가 없다. (플러그인 `setup` 이
    // 컴파일되지 않으므로 여기까지 오는 호출도 없다 — 남겨 두는 이유는
    // `crash_native.rs` 가 어느 타깃에서나 같은 모양으로 컴파일되게 하기 위해서다.)
}

/// 보고를 보내는가, 어디로 보내는가, 큐에 몇 건이 남았는가.
/// **밖에서 확인하는 통로다.**
///
/// \"조용히 죽었다\"(전송로가 바뀌어 실패만 하고 있음)와 \"보낼 게 없었다\"
/// 를 구분하려면 이 값이 필요하다.
///
/// ## 1단계와 달라진 판단 기준 (B안)
///
/// 1단계는 \"엔드포인트·키가 있나\" 를 물었고 **그게 곧 켜짐**이었다. B안에서는
/// 그 둘이 **없어도 켜진다** — SDK 가 붙으면 `google-services.json` 만으로
/// 전송된다. 그래서 이제 두 축을 따로 알린다.
///
///  - `route` — 지금 무엇으로 보내는가. `\"sdk\"` / `\"endpoint\"` / `\"off\"`.
///  - `enabled` — 아무 전송로든 살아 있는가(= 위가 `off` 가 아닌가).
///
/// **`endpoint`/`hasApiKey` 는 그대로 남긴다.** ②를 지우지 않았으므로 그
/// 설정이 실제로 쓰이는지 확인할 길이 있어야 한다. 키는 여전히 알리지 않는다.
pub fn status() -> serde_json::Value {
    let pending = queue()
        .lock()
        .map(|guard| guard.len())
        .unwrap_or(0);
    serde_json::json!({
        "enabled": enabled(),
        // 어느 전송로인가. B안에서 새로 생긴 축이다.
        "route": route(),
        // ① Firebase Crashlytics SDK 가 이 기기에서 살아 있는가.
        //   안드로이드 빌드에 `google-services.json` 이 들어가 FirebaseApp 이
        //   초기화됐고 Kotlin 플러그인이 등록됐으면 참이다.
        //   **데스크톱·시험에서는 언제나 거짓**이다.
        "sdk": sdk_registered(),
        // 엔드포인트는 알려도 되고 **키는 알리지 않는다.** 진단에 필요한 것은
        // "어디로 보내는가" 지 "무엇으로 인증하는가" 가 아니다.
        "endpoint": endpoint(),
        "hasApiKey": api_key().is_some(),
        "pending": pending,
        "sent": SENT.load(Ordering::Relaxed),
        "installId": install_id(None),
        // 로그 출력은 `logging.rs` 가 정한다(접두사 `[mupid-LEVEL]`, 최대 레벨
        // `Info`). 여기서는 **우리 브리지가 걸렸는지**만 알린다 — 안 걸렸으면
        // 경고가 예외 보고로 넘어가지 않는다.
        "logBridge": log_bridged(),
        "logMaxLevel": log::max_level().to_string(),
        "queueLimit": QUEUE_LIMIT,
        "versionCode": crate::update::local_version_code(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 이 모듈의 모든 시험은 이 잠금을 잡는다.
    ///
    /// 두 가지를 한 번에 해결한다.
    ///
    ///  1. **환경변수**는 프로세스 전역이다. 병렬로 돌면 한쪽이 다른 쪽의
    ///     키를 지워 "키가 있는데 꺼져 있다" 같은 거짓 결과가 난다.
    ///  2. **큐도 프로세스 전역이다.** 직렬화하지 않으면 앞 시험이 남긴
    ///     보고가 뒤 시험의 개수 단언에 섞인다.
    ///
    /// 그래서 큐를 보는 시험은 예외 없이 이 잠금 안에서 돌고, 시작할 때 큐를
    /// 비운다. 시험 하나가 늘어도 규칙은 그대로다.
    static TEST_LOCK: Mutex<()> = Mutex::new(());

    fn with_keys<T>(endpoint: Option<&str>, key: Option<&str>, body: impl FnOnce() -> T) -> T {
        let _guard = TEST_LOCK.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        let before_endpoint = std::env::var("MUPID_CRASHLYTICS_ENDPOINT").ok();
        let before_key = std::env::var("MUPID_CRASHLYTICS_API_KEY").ok();

        match endpoint {
            Some(value) => std::env::set_var("MUPID_CRASHLYTICS_ENDPOINT", value),
            None => std::env::remove_var("MUPID_CRASHLYTICS_ENDPOINT"),
        }
        match key {
            Some(value) => std::env::set_var("MUPID_CRASHLYTICS_API_KEY", value),
            None => std::env::remove_var("MUPID_CRASHLYTICS_API_KEY"),
        }
        // 앞 시험이 남긴 것을 지운다. 이 뒤의 개수 단언이 결정적이게 된다.
        reset_queue();

        let outcome = body();

        match before_endpoint {
            Some(value) => std::env::set_var("MUPID_CRASHLYTICS_ENDPOINT", value),
            None => std::env::remove_var("MUPID_CRASHLYTICS_ENDPOINT"),
        }
        match before_key {
            Some(value) => std::env::set_var("MUPID_CRASHLYTICS_API_KEY", value),
            None => std::env::remove_var("MUPID_CRASHLYTICS_API_KEY"),
        }
        reset_queue();
        outcome
    }

    fn sample(seed: usize) -> CrashReport {
        CrashReport {
            source: Source::Js,
            // 기본값은 예외다 — 이 시험들이 재는 것은 큐 동작이지 갈래가 아니다.
            kind: Kind::Exception,
            message: format!("시험 {seed}"),
            stack: None,
            context: None,
            at_ms: seed as i64 + 1,
            version_code: 1,
            web_build: Some(1),
            install_id: "test".to_string(),
        }
    }

    /// **가장 중요한 시험** — 키가 없으면 조용히 비활성이다.
    ///
    /// 이것이 깨지면 키 없는 빌드가 매 기동마다 네트워크로 예외를 밀어낸다.
    #[test]
    fn without_keys_nothing_is_recorded() {
        with_keys(None, None, || {
            assert!(!enabled(), "키가 없는데 켜져 있다");
            for index in 0..5 {
                report(sample(index));
            }
            assert_eq!(
                queue().lock().unwrap_or_else(|poisoned| poisoned.into_inner()).len(),
                0,
                "키가 없는데 큐에 쌓였다"
            );
        });
    }

    /// 키가 **하나만** 있어도 꺼진다. 엔드포인트만 있으면 인증 없이 남의
    /// 수집기에 밀어 넣게 된다.
    #[test]
    fn a_half_configuration_is_still_off() {
        with_keys(Some("https://example.invalid/crash"), None, || {
            assert!(!enabled(), "엔드포인트만 있고 키가 없는데 켜졌다");
        });
        with_keys(None, Some("secret"), || {
            assert!(!enabled(), "키만 있고 엔드포인트가 없는데 켜졌다");
        });
    }

    /// 키가 있으면 쌓이고, **꺼낸 순서가 들어간 순서와 같다.**
    #[test]
    fn reports_are_queued_in_order_when_configured() {
        with_keys(Some("https://example.invalid/crash"), Some("secret"), || {
            assert!(enabled());
            for index in 0..3 {
                report(sample(index));
            }
            let drained = take_batch();
            assert_eq!(drained.len(), 3, "꺼낸 개수가 다르다");
            assert_eq!(drained[0].message, "시험 0");
            assert_eq!(drained[2].message, "시험 2");
        });
    }

    /// 상한을 넘으면 **오래된 것부터** 버린다. 새것을 버리면 정작 봐야 할
    /// 마지막 예외를 놓친다.
    #[test]
    fn the_queue_drops_the_oldest_when_full() {
        with_keys(Some("https://example.invalid/crash"), Some("secret"), || {
            // 이 시험만 큐를 크게 쓴다 — 앞 시험이 남긴 것이 있으면 비운다.
            let _ = take_batch();
            for index in 0..(QUEUE_LIMIT + 5) {
                report(sample(index));
            }
            assert_eq!(
                queue().lock().unwrap().len(),
                QUEUE_LIMIT,
                "상한이 지켜지지 않았다"
            );
            let drained = take_batch();
            // 가장 오래된 다섯 건이 사라졌다. `take_batch` 는 20건까지만
            // 꺼내므로 앞쪽만 본다 — 버려진 것을 확인하는 데는 충분하다.
            assert_eq!(drained[0].message, "시험 5", "오래된 것이 남아 있다");
        });
    }

    /// 긴 본문은 잘린다. **UTF-8 경계에서** 잘려야 JSON 직렬화가 살아남는다.
    #[test]
    fn long_messages_are_trimmed_on_a_character_boundary() {
        with_keys(Some("https://example.invalid/crash"), Some("secret"), || {
            let _ = take_batch();
            let mut one = sample(0);
            // 한글은 3바이트다 — 바이트로 자르면 경계가 깨진다.
            one.message = "가".repeat(MESSAGE_LIMIT);
            one.stack = Some("나".repeat(MESSAGE_LIMIT * 4));
            report(one);

            let drained = take_batch();
            assert_eq!(drained.len(), 1);
            let message = &drained[0].message;
            assert!(message.len() <= MESSAGE_LIMIT + 3, "본문이 잘리지 않았다");
            assert!(message.ends_with('…'), "잘린 표시가 없다");
            assert!(
                drained[0].stack.as_ref().unwrap().len() <= MESSAGE_LIMIT * 2 + 3,
                "스택이 잘리지 않았다"
            );
            // 잘린 뒤에도 유효한 UTF-8 이고 직렬화된다.
            let json = serde_json::to_string(&drained[0]).expect("직렬화");
            assert!(json.contains('…'));
        });
    }

    /// 화면이 넘긴 시각을 존중한다. 없으면 받은 시각을 쓴다.
    #[test]
    fn js_reports_keep_their_timestamp_or_get_one() {
        let given = JsCrash {
            message: "boom".to_string(),
            stack: None,
            context: None,
            at_ms: Some(42),
        };
        let report_one = CrashReport::from_js(given);
        assert_eq!(report_one.at_ms, 42);
        assert_eq!(report_one.source, Source::Js);

        let missing = JsCrash {
            message: "boom".to_string(),
            stack: None,
            context: None,
            at_ms: None,
        };
        assert!(CrashReport::from_js(missing).at_ms > 0);
    }

    /// 출처가 Rust·JS 로 갈린다 — 대시보드에서 둘을 구분할 수 있어야 한다.
    /// **티켓의 핵심 요구다**("Rust 예외는 물론 JS 예외도").
    ///
    /// 큐가 아니라 **직렬화된 값**을 본다 — 큐를 거치면 앞 시험의 보고가
    /// 섞이고, 여기서 확인하려는 것은 그 구분 자체다.
    #[test]
    fn the_source_field_separates_rust_from_js() {
        let from_rust = CrashReport::from_rust(
            "패닉".to_string(),
            Some("src/main.rs:1:1".to_string()),
            "panic",
            Kind::Exception,
        );
        let from_js = CrashReport::from_js(JsCrash {
            message: "js boom".to_string(),
            stack: Some("at foo (app.js:1)".to_string()),
            context: Some("/report".to_string()),
            at_ms: None,
        });

        assert_eq!(from_rust.source, Source::Rust);
        assert_eq!(from_js.source, Source::Js);

        let rust_json = serde_json::to_string(&from_rust).expect("직렬화");
        let js_json = serde_json::to_string(&from_js).expect("직렬화");
        assert!(rust_json.contains("\"source\":\"rust\""), "Rust 출처가 없다");
        assert!(js_json.contains("\"source\":\"js\""), "JS 출처가 없다");
        // 화면이 알려 준 자리와 스택이 실려야 진단이 된다.
        assert!(js_json.contains("/report"), "JS 자리가 없다");
        assert!(js_json.contains("app.js:1"), "JS 스택이 없다");
        assert!(rust_json.contains("src/main.rs:1:1"), "패닉 위치가 없다");
    }

    /// 상태 통로는 **키를 밝히지 않는다.** 진단에 필요한 것은 어디로 보내는가다.
    #[test]
    fn status_never_leaks_the_key() {
        with_keys(
            Some("https://example.invalid/crash"),
            Some("super-secret-value"),
            || {
                let text = status().to_string();
                assert!(text.contains("example.invalid"), "엔드포인트가 없다");
                assert!(text.contains("\"hasApiKey\":true"), "키 유무가 없다");
                assert!(
                    !text.contains("super-secret-value"),
                    "상태 통로가 키를 흘렸다"
                );
            },
        );
    }

    /// 키가 없을 때 상태는 꺼져 있다고 말한다 — 조용한 비활성의 근거다.
    #[test]
    fn status_reports_itself_off_without_keys() {
        with_keys(None, None, || {
            let value = status();
            assert_eq!(value["enabled"], serde_json::json!(false));
            assert_eq!(value["hasApiKey"], serde_json::json!(false));
            assert_eq!(value["endpoint"], serde_json::json!(null));
        });
    }

    /// 설치 식별자는 개인·계정과 무관하고 32자 16진수다.
    #[test]
    fn the_install_id_is_stable_and_not_a_user_key() {
        let path = std::env::temp_dir().join(format!(
            "mupid-crash-install-{}.db",
            std::process::id()
        ));
        let db = crate::db::Db::open(&path).expect("DB 열기");

        let first = install_id(Some(&db));
        assert_eq!(first.len(), 32, "길이가 32자가 아니다");
        assert!(first.bytes().all(|byte| byte.is_ascii_hexdigit()));
        // 두 번째 호출도 같은 값이다 (프로세스 캐시든 DB 든).
        assert_eq!(install_id(Some(&db)), first);
        // 계정 키(`local` 또는 사용자 번호)와 겹치지 않는다.
        let account = crate::store::kv::current_user_key(&db).expect("계정 키");
        assert_ne!(first, account, "설치 식별자가 계정 키와 같다");

        let _ = std::fs::remove_file(&path);
    }

    /// 로그 수준은 **`logging.rs` 가 정한다.**
    ///
    /// 원래 여기서 `MUPID_LOG_LEVEL` 로 조절하는 `log_level()` 을 시험했다.
    /// develop 의 `logging.rs` 가 로거를 가져가면서 그 함수는 없어졌다 —
    /// 최대 레벨은 이제 그쪽이 `Info` 로 고정한다(계측이 `log::info!` 라
    /// 그보다 낮으면 조용히 사라진다). **그 계약은 `logging.rs` 의
    /// `the_configured_level_lets_info_through` 가 지킨다** — 여기서 다시
    /// 주장하면 규칙이 두 곳으로 갈라진다.
    ///
    /// 그래서 여기서는 **우리가 그 레벨을 낮추지 않았다는 것**만 본다.
    #[test]
    fn the_bridge_does_not_lower_the_configured_level() {
        let _guard = TEST_LOCK.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        // `logging::init()` 이 세우는 것과 같은 값을 여기서도 세운다.
        crate::logging::init();
        install_log_bridge();
        assert!(
            log::max_level() >= log::LevelFilter::Info,
            "Info 가 걸러지면 logging.rs 의 계측이 조용히 사라진다"
        );
        assert!(log::log_enabled!(log::Level::Info), "Info 가 비활성이다");
    }

    /// 자르기가 경계에서 정확히 동작하는지 — 짧으면 손대지 않는다.
    #[test]
    fn short_text_is_left_alone() {
        assert_eq!(CrashReport::trimmed("그대로", MESSAGE_LIMIT), "그대로");
        // 경계에 정확히 걸치는 길이도 그대로 남는다.
        let exact = "a".repeat(MESSAGE_LIMIT);
        assert_eq!(CrashReport::trimmed(&exact, MESSAGE_LIMIT), exact);
    }

    /// **로그와 예외가 다른 커맨드로 간다** — B안에서 가장 중요한 판단이다.
    ///
    /// Crashlytics SDK 는 `recordException`(비정상 종료 이벤트)과 `log`
    /// (빵부스러기)를 따로 둔다. 로그 브리지가 흘려보낸 경고를 예외로 올리면
    /// 비정상 종료율이 부풀고 **진짜 예외가 그 속에 묻힌다.** 그래서 갈래를
    /// 여기서 고정한다 — 두 판단이 갈라지면 그쪽이 대시보드를 오염시킨다.
    #[test]
    fn logs_and_exceptions_take_different_commands() {
        // 패닉·JS 예외는 `recordException` 으로.
        assert_eq!(Kind::Exception.native_command(), "recordException");
        assert_eq!(Kind::Log.native_command(), "log");

        // 기본값은 예외다 — 1단계가 만든 보고는 전부 예외였다.
        assert_eq!(Kind::default(), Kind::Exception);

        // 화면이 넘긴 JS 오류와 패닉은 예외로 분류된다.
        let js = CrashReport::from_js(JsCrash {
            message: "boom".to_string(),
            stack: None,
            context: None,
            at_ms: None,
        });
        assert_eq!(js.kind, Kind::Exception);
        let rust = CrashReport::from_rust("패닉".to_string(), None, "panic", Kind::Exception);
        assert_eq!(rust.kind, Kind::Exception);
        // 로그 브리지만 로그로 분류된다.
        let logged = CrashReport::from_rust("log".to_string(), None, "log", Kind::Log);
        assert_eq!(logged.kind, Kind::Log);

        // HTTP 본문에도 실린다 — 수집기가 거를 수 있어야 한다(`Kind`).
        let json = serde_json::to_string(&logged).expect("직렬화");
        assert!(json.contains("\"kind\":\"log\""), "갈래가 본문에 없다");
    }

    /// `crash_report_status` 의 B안 판단 기준을 고정한다.
    ///
    /// 1단계는 `enabled` 하나로 "키가 있나" 를 답했다. B안에서는 SDK 라는
    /// **키가 필요 없는** 전송로가 생겼으므로 `route` 축이 따로 있어야 한다.
    /// 데스크톱·시험에는 SDK 가 없으니 여기서는 항상 `endpoint` 아니면 `off` 다.
    #[test]
    fn status_reports_which_route_is_live() {
        with_keys(Some("https://example.invalid/crash"), Some("secret"), || {
            let value = status();
            assert_eq!(value["route"], serde_json::json!("endpoint"));
            assert_eq!(value["enabled"], serde_json::json!(true));
        });
        with_keys(None, None, || {
            let value = status();
            assert_eq!(value["route"], serde_json::json!("off"));
            assert_eq!(value["enabled"], serde_json::json!(false));
            // 시험(데스크톱)에서는 SDK 가 언제나 꺼져 있다 — 그 계약 위에서
            // 1단계 시험들이 살아 있다.
            assert_eq!(value["sdk"], serde_json::json!(false));
        });
    }

    /// **기동 경로를 키 없이 그대로 밟는다 — acceptance 3 의 실증이다.**
    ///
    /// "키가 없어도 앱이 기동하고 기존 기능이 그대로 동작해야 한다" 를
    /// 코드 근거가 아니라 **실행으로** 보인다. `lib.rs` 의 `setup` 이 부르는
    /// 네 함수를 그대로 부르고(`bind_install_id` 는 DB 를 연 뒤다),
    ///
    ///  - 하나도 패닉하지 않고,
    ///  - 보고가 한 건도 쌓이지 않고,
    ///  - 예외가 나도 앱이 계속 돌고,
    ///  - **기존 저장소 기능이 그대로 동작하는지**(왕복 저장)를 본다.
    #[test]
    fn the_boot_path_is_safe_and_lifeless_without_keys() {
        with_keys(None, None, || {
            let path = std::env::temp_dir().join(format!(
                "mupid-crash-boot-{}.db",
                std::process::id()
            ));
            let db = crate::db::Db::open(&path).expect("키 없이 DB 가 열려야 한다");

            // `lib.rs::run()` 의 순서 그대로 밟는다. **패닉이 하나도 나면 안 된다.**
            //
            // develop 의 `logging::init()` 이 로거를 세우고, 우리 브리지가 그
            // **뒤에** 그것을 감싼다(출력 형식은 전부 `logging.rs` 몫이다).
            crate::logging::init();
            install_log_bridge();
            bind_install_id(&db);
            install_panic_hook();
            start_collector();

            // 로거가 실제로 세워졌는가. `logger()` 는 `NopLogger` 를 돌려주므로
            // (`log` 는 현재 로거를 꺼내는 공개 API 를 주지 않는다) 레벨과
            // 매크로 활성 여부로 확인한다 — "로거가 죽어 있다" 와 "실패가
            // 없다" 를 가르는 것이 `logging.rs` 를 만든 이유였다.
            assert!(
                log::log_enabled!(log::Level::Info),
                "로거가 세워지지 않아 진단이 조용히 버려진다"
            );

            assert!(!enabled(), "키가 없는데 켜졌다");

            // 예외가 나도 앱은 계속 돈다 — 그리고 아무것도 쌓이지 않는다.
            let panicked = std::panic::catch_unwind(|| {
                report(sample(1));
                panic!("시험용 패닉");
            });
            assert!(panicked.is_err(), "패닉이 전파되지 않았다");
            assert_eq!(
                queue().lock().unwrap_or_else(|poisoned| poisoned.into_inner()).len(),
                0,
                "키가 없는데 패닉이 쌓였다"
            );

            // **기존 기능이 그대로 동작한다** — 저장소 왕복.
            crate::store::kv::put(&db, "시험키", "시험값").expect("저장");
            assert_eq!(
                crate::store::kv::get(&db, "시험키").expect("읽기").as_deref(),
                Some("시험값"),
                "키가 없다고 기존 저장소가 망가졌다"
            );
            crate::store::kv::remove(&db, "시험키").expect("삭제");

            let _ = std::fs::remove_file(&path);
        });
    }
}
