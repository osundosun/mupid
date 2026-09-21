//! Firebase Crashlytics 플러그인 등록 — Kotlin 쪽 다리를 세운다.
//!
//! 티켓 S15P21A204-383 2단계 (B안: SDK 정공법).
//!
//! `update_native.rs` 와 **같은 모양**이다. 다른 점은 **커맨드를 부르는 쪽이
//! 화면이 아니라 `crash.rs`** 라는 것뿐이다 — 패닉 훅·로그 브리지·JS 커맨드가
//! 전부 거기를 지나므로 거기서 핸들을 쥐고 있어야 한다. 그래서 여기서 하는
//! 일은 **플러그인을 등록하고 핸들을 `crash.rs` 에 넘기는 것까지**다.
//!
//! ## `invoke_handler` 를 두지 않는 이유
//!
//! `update_native.rs` 도 두지 않는다. 안드로이드에서 `run_mobile_plugin` 은
//! Tauri 의 Rust 쪽 허용 목록이 아니라 **Kotlin `PluginManager.runCommand`**
//! 로 내려가 `@Command` 메서드를 부른다(Rust 측에는 그 목록이 없다).
//! 그래서 화면용 커맨드를 억지로 만들 필요가 없다 — 만들면 쓰이지 않는
//! 껍데기가 남는다.
//!
//! ## 순서가 중요하다
//!
//! `register_sdk()` 는 이 `setup` 안에서 부른다. 그때 앱의 `setup` 은 아직
//! 시작 전이다 — Tauri 의 빌드 순서가 `initialize_plugins`(`app.rs:2440`) →
//! 앱 `setup`(`app.rs:2530`) 이기 때문이다. 그래서 `lib.rs` 의 `setup` 에서
//! 패닉 훅을 걸 때는 **이미 핸들이 들어가 있다**(패닉 훅이 이 싱크를 본다).

#![cfg_attr(not(target_os = "android"), allow(dead_code, unused_imports))]

/// 안드로이드 쪽 Crashlytics 플러그인을 붙인다.
///
/// 부르는 자리는 `lib.rs` 의 `Builder::plugin(crash_native::init())` 이다 —
/// `update_native::init()` 옆이다.
///
/// ## 왜 `update_native.rs` 와 달리 **`R` 이 아니라 `tauri::Wry` 인가**
///
/// `update_native.rs` 는 `init<R: Runtime>()` 이다. 핸들을 `app.manage()` 로 넣은
/// **generic 컨테이너**(`UpdatePlugin<R>`)에 담기 때문이다 — 꺼낼 때
/// `try_state::<UpdatePlugin<tauri::Wry>>()` 로 구체 타입을 적어 준다
/// (`commands.rs`).
///
/// 이쪽은 다르다. 핸들을 **`crash.rs` 의 `static SINK`** 에 넣어야 한다 —
/// 패닉 훅·로그 브리지·JS 커맨드가 전부 그 `static` 을 보기 때문이다.
/// **`static` 은 generic 을 담을 수 없다**(모노모픽화 대상이 아니다). 그래서
/// `SINK` 는 `OnceLock<PluginHandle<tauri::Wry>>` 이고, `register_sdk` 도
/// `PluginHandle<tauri::Wry>` 를 받는다.
///
/// 그런데 여기서 `init<R: Runtime>()` 이라고 선언하면 **모순이 된다**:
/// `register_sdk(handle)` 에 넘기는 순간 `api` 가 `PluginApi<Wry>` 로 추론되고,
/// `builder.build()` 가 `TauriPlugin<Wry>` 를 내는데 **반환 타입은 `TauriPlugin<R>`**
/// 이다 → `error[E0308]: mismatched types`.
///
/// **이 오류는 데스크톱에서 보이지 않는다.** `builder.setup(...)` 이
/// `#[cfg(target_os = "android")]` 라 데스크톱에서는 `Builder::new(...)` 만 남아
/// `R` 이 자유롭게 잡힌다. 그래서 `cargo test`(데스크톱)는 통과하고
/// **안드로이드 빌드에서만** 깨진다 — 실제로 master 의 `app-build` 가 이것으로
/// 실패했다(2026-09-21, job 598113).
///
/// `Wry` 는 이 앱의 실제 런타임이다. `lib.rs` 가 `tauri::Builder::default()` 를
/// 쓰고, `commands.rs` 도 `UpdatePlugin<tauri::Wry>` 를 꺼낸다. 그래서
/// **generic 을 주장하지 않고 `Wry` 를 정직하게 적는 것**이 맞다.
pub fn init() -> tauri::plugin::TauriPlugin<tauri::Wry> {
    let builder = tauri::plugin::Builder::new("mupid-crashlytics");
    #[cfg(target_os = "android")]
    let builder = builder.setup(|_app, api| {
        // 플러그인 이름은 `@TauriPlugin` 이 붙은 Kotlin 클래스 이름과 같아야
        // 한다. **그 클래스 이름을 바꾸면 여기도 바꿔야 한다**
        // (`CrashlyticsPlugin.kt`).
        let handle = api.register_android_plugin("com.osundosun.mupid", "CrashlyticsPlugin")?;
        // 핸들을 `crash.rs` 에 넘긴다. 그쪽이 실제로 커맨드를 부른다.
        crate::crash::register_sdk(handle);
        Ok(())
    });
    // 안드로이드가 아닌 곳(데스크톱 개발 빌드)에서는 **플러그인만 등록하지
    // 않는다.** `init()` 은 어느 플랫폼에서나 존재한다 — `Builder::plugin()`
    // 이 그걸 부르기 때문이다. 모듈을 통째로 빼면 데스크톱 빌드가 컴파일되지
    // 않는다(`update_native.rs`·`media.rs` 의 판단과 같다).
    builder.build()
}
