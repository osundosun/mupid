//! 상단 알림과 잠금화면의 플레이어.
//!
//! 소리는 웹뷰가 낸다 (`frontend/lib/player/`). 안드로이드는 그 사실을 모르므로,
//! 곡 정보와 재생 상태를 **따로 알려 줘야** 알림 그늘과 잠금화면에 플레이어가
//! 뜬다. 그 창구가 `MediaSession` 이고, 여기서 하는 일은 셋뿐이다.
//!
//! 1. 화면이 넘긴 상태를 안드로이드 플러그인(`MediaSessionPlugin.kt`)에 전달한다.
//! 2. 앨범 아트를 받아 파일로 떨군다 — 안드로이드는 `Bitmap` 을 원하는데 웹뷰가
//!    쓰는 `mupidimg:` 주소는 웹뷰 안에서만 통한다.
//! 3. 알림·잠금화면·이어폰 버튼을 누르면 그것을 웹뷰에 이벤트로 돌려준다.
//!
//! 안드로이드가 아닌 곳(데스크톱 개발 빌드)에서는 커맨드가 조용히 아무것도 하지
//! 않는다. 화면 코드가 플랫폼을 묻지 않아도 되도록.

// 아래 정의는 대부분 안드로이드에서만 읽힌다. 데스크톱 개발 빌드에서는 커맨드가
// 값을 받아 그대로 버리므로 "쓰지 않는다"는 경고가 붙는다 — 그 경고만 끈다.
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Runtime};

#[cfg(target_os = "android")]
use std::sync::atomic::{AtomicU64, Ordering};

/// 알림 버튼을 누르면 웹뷰가 받는 이벤트. 짝은 `frontend/lib/platform/media-session.ts`.
pub const CONTROL_EVENT: &str = "media-session://control";

/// 화면이 넘기는 지금 재생 상태.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NowPlaying {
    pub title: String,
    pub artist: String,
    #[serde(default)]
    pub album: String,
    pub duration_sec: f64,
    pub position_sec: f64,
    pub playing: bool,
    /// 앨범 아트. 화면이 들고 다니는 주소 그대로다 — 앱에서는 `mupidimg` 로
    /// 감싸인 모양이라 `image::remote_url` 로 푼다.
    #[serde(default)]
    pub artwork: Option<String>,
    pub can_previous: bool,
    pub can_next: bool,
}

/// 알림·잠금화면에서 누른 버튼.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Control {
    /// `play` · `pause` · `next` · `previous` · `stop` · `seek`.
    pub action: String,
    /// `seek` 일 때만 의미가 있다. 나머지는 음수.
    #[serde(default)]
    pub position_ms: i64,
}

// ─────────────────────────────────────────────────────────────
// 커맨드
// ─────────────────────────────────────────────────────────────

#[tauri::command]
pub async fn media_session_update<R: Runtime>(
    state: NowPlaying,
    app: AppHandle<R>,
) -> Result<(), String> {
    #[cfg(target_os = "android")]
    {
        android::update(&app, state).await
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = (state, app);
        Ok(())
    }
}

#[tauri::command]
pub async fn media_session_clear<R: Runtime>(app: AppHandle<R>) -> Result<(), String> {
    #[cfg(target_os = "android")]
    {
        android::clear(&app).await
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = app;
        Ok(())
    }
}

/// 안드로이드 쪽 플러그인을 붙인다.
///
/// 플러그인으로 감싸는 이유는 `register_android_plugin` 을 부를 수 있는 자리가
/// 플러그인 `setup` 뿐이기 때문이다. 화면이 부르는 커맨드는 앱 커맨드로 남겨
/// 둔다 — 플러그인 커맨드로 두면 권한 파일과 capability 를 따로 써야 한다.
pub fn init<R: Runtime>() -> tauri::plugin::TauriPlugin<R> {
    tauri::plugin::Builder::new("mupid-media")
        .setup(|_app, _api| {
            #[cfg(target_os = "android")]
            {
                use tauri::Manager;
                let handle =
                    _api.register_android_plugin("com.osundosun.mupid", "MediaSessionPlugin")?;
                _app.manage(android::MediaPlugin {
                    handle,
                    listening: Default::default(),
                    generation: AtomicU64::new(0),
                });
            }
            Ok(())
        })
        .build()
}

/// 앨범 아트 주소를 캐시 파일 이름으로 바꾼다.
///
/// FNV-1a 64비트. 암호용이 아니라 이름을 짓는 용도라 짧고 빠른 쪽이 맞다 —
/// 앨범 몇 만 장을 넣어도 두 장이 같은 이름을 받을 일은 없다.
fn digest(url: &str) -> String {
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for byte in url.as_bytes() {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    format!("{hash:016x}")
}

// ─────────────────────────────────────────────────────────────
// 안드로이드
// ─────────────────────────────────────────────────────────────

#[cfg(target_os = "android")]
mod android {
    use super::*;

    use std::path::PathBuf;
    use std::sync::atomic::AtomicBool;

    use tauri::ipc::InvokeResponseBody;
    use tauri::{Emitter, Manager};

    /// 앨범 아트를 들고 있을 파일 수. 한 장이 수십 KB 라 이 정도면 충분하고,
    /// 넘기면 오래된 것부터 지운다 — 앨범을 옮겨 다닐수록 무한히 쌓이면 안 된다.
    const ARTWORK_KEEP: usize = 24;

    pub struct MediaPlugin<R: Runtime> {
        pub handle: tauri::plugin::PluginHandle<R>,
        /// 버튼 통로를 이미 열었는가.
        pub listening: AtomicBool,
        /// 몇 번째 상태인가. 늦게 도착한 앨범 아트가 다음 곡을 덮어쓰지 않게 한다.
        pub generation: AtomicU64,
    }

    /// 안드로이드 플러그인에 넘기는 모양. 시간은 전부 밀리초 정수다.
    #[derive(Debug, Clone, Serialize)]
    #[serde(rename_all = "camelCase")]
    struct AndroidState {
        title: String,
        artist: String,
        album: String,
        duration_ms: i64,
        position_ms: i64,
        playing: bool,
        artwork_path: Option<String>,
        can_previous: bool,
        can_next: bool,
    }

    impl AndroidState {
        fn of(state: &NowPlaying, artwork_path: Option<String>) -> Self {
            let ms = |value: f64| {
                if value.is_finite() && value > 0.0 {
                    (value * 1000.0) as i64
                } else {
                    0
                }
            };
            Self {
                title: state.title.clone(),
                artist: state.artist.clone(),
                album: state.album.clone(),
                duration_ms: ms(state.duration_sec),
                position_ms: ms(state.position_sec),
                playing: state.playing,
                artwork_path,
                can_previous: state.can_previous,
                can_next: state.can_next,
            }
        }
    }

    #[derive(Serialize)]
    #[serde(rename_all = "camelCase")]
    struct RegisterListener {
        event: &'static str,
        handler: tauri::ipc::Channel,
    }

    pub async fn update<R: Runtime>(app: &AppHandle<R>, state: NowPlaying) -> Result<(), String> {
        // 통로를 못 열어도 알림은 띄운다. 버튼이 죽을 뿐 보여 주는 일은 된다.
        // 다음 갱신에서 다시 시도한다.
        if let Err(error) = listen(app).await {
            log::warn!("{error}");
        }

        let generation = bump(app);

        // 이미 받아 둔 앨범 아트가 있으면 처음부터 함께 올린다. 없으면 글자만
        // 먼저 띄우고 그림은 뒤따라 보낸다 — 알림이 뜨는 것이 더 급하다.
        let cached = state.artwork.as_deref().and_then(|url| cached(app, url));
        push(app, AndroidState::of(&state, cached.clone())).await?;

        if cached.is_none() {
            // 받아 올 수 있는 주소일 때만 나선다. 앱 안의 파일이 표지 자리에
            // 들어 있으면(`/sky-background.webp`) 받을 것이 없다.
            if let Some(url) = state
                .artwork
                .clone()
                .filter(|url| crate::image::remote_url(url).is_some())
            {
                let app = app.clone();
                tauri::async_runtime::spawn(async move {
                    let Some(path) = download(&app, &url).await else {
                        return;
                    };
                    // 그 사이 곡이 바뀌었으면 버린다. 안 그러면 방금 넘긴 곡의
                    // 표지가 다음 곡 위에 올라앉는다.
                    if generation_of::<R>(&app) != generation {
                        return;
                    }
                    if let Err(error) = push(&app, AndroidState::of(&state, Some(path))).await {
                        log::warn!("앨범 아트를 알림에 넣지 못했습니다: {error}");
                    }
                });
            }
        }
        Ok(())
    }

    pub async fn clear<R: Runtime>(app: &AppHandle<R>) -> Result<(), String> {
        // 세대를 올려 두면 아직 받는 중인 앨범 아트가 알림을 되살리지 못한다.
        bump(app);
        call(app, "clear", serde_json::Value::Null).await
    }

    /// 상태를 한 칸 넘기고 새 번호를 준다.
    fn bump<R: Runtime>(app: &AppHandle<R>) -> u64 {
        app.try_state::<MediaPlugin<R>>()
            .map_or(0, |plugin| plugin.generation.fetch_add(1, Ordering::SeqCst) + 1)
    }

    fn generation_of<R: Runtime>(app: &AppHandle<R>) -> u64 {
        app.try_state::<MediaPlugin<R>>()
            .map_or(0, |plugin| plugin.generation.load(Ordering::SeqCst))
    }

    async fn push<R: Runtime>(app: &AppHandle<R>, state: AndroidState) -> Result<(), String> {
        call(app, "setState", state).await
    }

    async fn call<R: Runtime>(
        app: &AppHandle<R>,
        command: &str,
        payload: impl Serialize,
    ) -> Result<(), String> {
        // 상태 가드를 `await` 너머로 들고 가지 않는다. 핸들은 복제가 싸다.
        let handle = {
            let Some(plugin) = app.try_state::<MediaPlugin<R>>() else {
                return Ok(());
            };
            plugin.handle.clone()
        };
        handle
            .run_mobile_plugin_async::<()>(command, payload)
            .await
            .map_err(|error| format!("재생 알림을 갱신하지 못했어요: {error}"))
    }

    /// 버튼을 받을 통로를 한 번만 연다.
    async fn listen<R: Runtime>(app: &AppHandle<R>) -> Result<(), String> {
        let handle = {
            let Some(plugin) = app.try_state::<MediaPlugin<R>>() else {
                return Ok(());
            };
            if plugin.listening.swap(true, Ordering::SeqCst) {
                return Ok(());
            }
            plugin.handle.clone()
        };

        let target = app.clone();
        let channel = tauri::ipc::Channel::new(move |body| {
            let text = match body {
                InvokeResponseBody::Json(text) => text,
                InvokeResponseBody::Raw(bytes) => String::from_utf8_lossy(&bytes).into_owned(),
            };
            match serde_json::from_str::<Control>(&text) {
                Ok(control) => {
                    let _ = target.emit(CONTROL_EVENT, control);
                }
                Err(error) => log::warn!("알 수 없는 재생 제어입니다 ({text}): {error}"),
            }
            Ok(())
        });

        let result = handle
            .run_mobile_plugin_async::<()>(
                "registerListener",
                RegisterListener {
                    event: "control",
                    handler: channel,
                },
            )
            .await;
        if let Err(error) = result {
            // 다음 갱신에서 다시 시도한다. 통로가 없으면 알림은 떠도 버튼이 죽는다.
            if let Some(plugin) = app.try_state::<MediaPlugin<R>>() {
                plugin.listening.store(false, Ordering::SeqCst);
            }
            return Err(format!("재생 제어를 연결하지 못했어요: {error}"));
        }
        Ok(())
    }

    // ─────────────────────────────────────────────────────────
    // 앨범 아트
    // ─────────────────────────────────────────────────────────

    fn directory<R: Runtime>(app: &AppHandle<R>) -> Option<PathBuf> {
        let path = app.path().app_cache_dir().ok()?.join("artwork");
        std::fs::create_dir_all(&path).ok()?;
        Some(path)
    }

    fn cached<R: Runtime>(app: &AppHandle<R>, url: &str) -> Option<String> {
        let path = directory(app)?.join(digest(url));
        path.is_file().then(|| path.to_string_lossy().into_owned())
    }

    async fn download<R: Runtime>(app: &AppHandle<R>, url: &str) -> Option<String> {
        // 화면이 준 주소는 웹뷰 안에서만 통하는 모양이다. 원래 주소로 되돌린다.
        let target = crate::image::remote_url(url)?;

        // 이미지 스킴과 같은 클라이언트다 — 커넥션 풀도 쿠키도 함께 쓴다.
        let client = app.state::<crate::http::HttpState>().client().clone();
        let response = match client.get(&target).send().await {
            Ok(value) => value,
            Err(error) => {
                log::warn!("앨범 아트를 받지 못했습니다 ({target}): {error}");
                return None;
            }
        };
        if !response.status().is_success() {
            log::warn!("앨범 아트 응답이 {} 입니다 ({target})", response.status());
            return None;
        }
        let bytes = response.bytes().await.ok()?;

        let directory = directory(app)?;
        let path = directory.join(digest(url));
        if let Err(error) = std::fs::write(&path, &bytes) {
            log::warn!("앨범 아트를 저장하지 못했습니다: {error}");
            return None;
        }
        prune(&directory);
        Some(path.to_string_lossy().into_owned())
    }

    /// 오래된 것부터 지워 개수를 맞춘다.
    fn prune(directory: &std::path::Path) {
        let Ok(entries) = std::fs::read_dir(directory) else {
            return;
        };
        let mut files: Vec<_> = entries
            .filter_map(|entry| {
                let entry = entry.ok()?;
                let modified = entry.metadata().ok()?.modified().ok()?;
                Some((modified, entry.path()))
            })
            .collect();
        if files.len() <= ARTWORK_KEEP {
            return;
        }
        files.sort_by_key(|(modified, _)| *modified);
        for (_, path) in files.iter().take(files.len() - ARTWORK_KEEP) {
            let _ = std::fs::remove_file(path);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::digest;

    #[test]
    fn artwork_file_names_are_one_per_url() {
        let one = "/api/v1/storage/image/ALBUM/1";
        assert_eq!(digest(one), digest(one), "같은 주소는 같은 파일을 가리킨다");
        assert_ne!(digest(one), digest("/api/v1/storage/image/ALBUM/2"));
        // 길이가 들쭉날쭉하면 파일 이름이 주소 길이를 흘린다.
        assert_eq!(digest("").len(), 16);
        assert_eq!(digest(one).len(), 16);
    }
}
