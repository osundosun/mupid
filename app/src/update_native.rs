//! 네이티브 업데이트 — Kotlin 플러그인에 APK 설치를 넘긴다.
//!
//! 안드로이드 8+ 에서 앱이 자기 APK 를 설치하려면 **시스템 설치기(인텐트)** 를
//! 거쳐야 한다. 앱이 조용히 제 패키지를 갈아 끼울 수는 없다 — 그건 사용자가
//! 허용해야 하는 일이고(`REQUEST_INSTALL_PACKAGES` + "이 출처의 앱 설치"),
//! 그래서 여기서 하는 일은 **검증된 파일을 설치기에 넘기는 것까지**다.
//!
//! `media.rs` 와 같은 모양으로 둔다.
//!
//! - Kotlin 플러그인은 `register_android_plugin` 으로 `setup` 에서 등록한다
//!   (그 API 를 부를 수 있는 자리가 거기뿐이다).
//! - 화면이 부르는 것은 **앱 커맨드**로 남긴다(`commands.rs` 의 `update_native`).
//!   플러그인 커맨드로 두면 권한 파일과 capability 를 새로 써야 한다.
//!
//! 안드로이드가 아닌 곳(데스크톱 개발 빌드)에서는 **플러그인만 등록하지 않는다.**
//! `init()` 은 어느 플랫폼에서나 존재한다 — `Builder::plugin()` 이 그걸 부르기
//! 때문이다. 모듈을 통째로 빼 버리면 데스크톱 빌드가 컴파일되지 않는다.
//! (`media.rs` 의 판단과 같다.)

use std::path::Path;

use tauri::Runtime;

#[cfg(target_os = "android")]
use tauri::plugin::PluginHandle;

/// 설치를 넘길 때 Kotlin 이 받는 인자.
///
/// `path` 는 **앱 내부 캐시의 절대 경로**다. Kotlin 쪽에서
/// `FileProvider.getUriForFile` 로 감싸야 다른 앱(설치기)이 읽을 수 있다 —
/// 파일 경로를 그대로 넘기면 `FileUriExposedException` 으로 죽는다
/// (안드로이드 7+ 의 엄격 모드).
#[cfg(target_os = "android")]
#[derive(Debug, serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct InstallRequest<'a> {
    path: &'a str,
}

/// 등록된 플러그인 핸들. `setup` 에서 `manage` 로 넣는다.
#[cfg(target_os = "android")]
pub struct UpdatePlugin<R: Runtime> {
    pub handle: PluginHandle<R>,
}

#[cfg(target_os = "android")]
impl<R: Runtime> UpdatePlugin<R> {
    /// 시스템 설치기를 연다.
    ///
    /// **결과를 기다리지 않는다.** 설치기는 별도 화면이고, 사용자가 거기서
    /// 무엇을 하든(설치·취소) 우리 앱은 이미 백그라운드다. 결과를 기다리면
    /// 커맨드가 설치 화면이 닫힐 때까지 매달린다.
    ///
    /// 실패(인텐트를 못 여는 경우)만 돌려준다 — 그건 사용자에게 알려야 한다.
    pub fn install(&self, apk: &Path) -> Result<(), String> {
        let path = apk
            .to_str()
            .ok_or_else(|| "APK 경로가 UTF-8 이 아닙니다".to_string())?;
        if apk.extension().and_then(|value| value.to_str()) != Some("apk") {
            return Err("APK 파일이 아닙니다".to_string());
        }
        self.handle
            .run_mobile_plugin::<()>("install", InstallRequest { path })
            .map_err(|error| error.to_string())
    }
}

/// 안드로이드 쪽 플러그인을 붙인다.
///
/// 부르는 자리는 `lib.rs` 의 `Builder::plugin(update_native::init())` 이다 —
/// `media::init()` 옆이다.
pub fn init<R: Runtime>() -> tauri::plugin::TauriPlugin<R> {
    let builder = tauri::plugin::Builder::new("mupid-update");
    #[cfg(target_os = "android")]
    let builder = builder.setup(|app, api| {
        use tauri::Manager;
        let handle = api.register_android_plugin("com.osundosun.mupid", "AppUpdatePlugin")?;
        app.manage(UpdatePlugin { handle });
        Ok(())
    });
    builder.build()
}
