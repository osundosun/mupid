//! 웹 번들을 **디스크에서 먼저** 찾는 자산 프로바이더.
//!
//! 앱 셸은 `tauri://` 로 자기 자산을 서빙한다. 그 해석은
//! `AppManager::get_asset()` 한 곳을 지나는데, 여기서 그 프로바이더를 갈아 끼운다.
//! 이 자리를 바꾸면 **HTML 폴백**(`/explore` → `explore.html` → `index.html`),
//! MIME 판별, CSP 해시 주입이 전부 함께 해결된다 — Tauri 의 로직을 우리가
//! 베껴 쓰지 않아도 된다는 뜻이다.
//!
//! 규칙은 하나다.
//!
//! 1. 앱 데이터 디렉터리에 **활성화된 번들**이 있으면 그 안에서 준다.
//! 2. 없거나 그 안에 파일이 없으면 **임베드 번들**에서 준다.
//!
//! 임베드는 지우지 않는다. 마지막 안전망이다 — 업데이트가 깨져도 앱은 벽돌이
//! 되지 않는다. 매니페스트가 어긋나거나, 디렉터리가 사라지거나, 심볼릭 링크가
//! 섞여 있으면 조용히 임베드로 내려앉는다.
//!
//! ## 왜 CSP 해시를 여기서 다루나
//!
//! 빌드된 HTML 에는 inline `<script>` 가 **18개(약 9.6KB)** 들어 있다(실측).
//! Tauri 는 그 해시를 **컴파일 시점에** 계산해 코드에 박는다
//! (`tauri-codegen` 의 `embedded_assets.rs`). 그래서 디스크의 다른 번들은
//! **박힌 해시와 맞지 않아 전부 차단되고 화면이 하얗게 뜬다.** CSP 위반은
//! 조용하다 — 콘솔에도 안 뜬다.
//!
//! 해결은 `Assets::csp_hashes` 를 활성 번들 기준으로 돌려주는 것이다. Tauri 의
//! CSP 조립 경로는 그대로 두고 값만 바꿔 넣는다. 계산은 활성화 시점에 한 번만
//! 하고(요청마다 하지 않는다) 들고 있는다.
//!
//! 정규화는 반드시 Tauri 와 같은 것을 쓴다 — `tauri_utils::html2` 의
//! `normalize_script_for_csp`(CRLF/CR → LF)와 같은 DOM 선택
//! (`script:not(:empty)`). 직접 흉내 내면 한 글자만 달라도 차단된다.

use std::borrow::Cow;
use std::collections::HashMap;
use std::path::{Component, Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::OnceLock;

use base64::Engine;
use sha2::{Digest, Sha256};
use tauri::utils::assets::{AssetKey, AssetsIter, CspHash};
use tauri::{App, Assets, Manager, Runtime};

/// 활성 번들이 사는 자리. 앱 데이터 디렉터리 아래다.
///
/// **캐시 디렉터리를 쓰지 않는다.** 안드로이드는 캐시를 임의로 비울 수 있고,
/// 그러면 업데이트가 사라진다. 앱 데이터는 SQLite(`mupid.db`)가 사는 자리와
/// 같아서 수명 정책이 하나로 묶인다.
const WEB_DIR: &str = "web";

/// 활성 번들의 이름을 담은 파일. **이 한 줄이 스위치다.**
///
/// 아티팩트를 먼저 다 놓고 이 파일을 마지막에 바꾼다 — `receive-k8s.py`·
/// `receive-apk.py` 가 이미 쓰는 순서다.
///
/// `update.rs` 가 같은 값을 쓴다(활성화·진단). 여기저기 문자열로 흩어 두면
/// 한쪽만 바뀌었을 때 조용히 어긋난다.
pub const POINTER: &str = "active";

/// 활성화된 번들 하나.
pub struct Active {
    /// 번들이 펼쳐진 디렉터리.
    pub root: PathBuf,
    /// HTML 자산 키(`/index.html` 모양) → inline 스크립트 해시 목록.
    ///
    /// **페이지마다 따로 둔다.** 실측에서 15개 HTML 이 모두 18개였지만 값이
    /// 같다는 보장은 없다(Tauri 의 `html_hashes` 도 HTML 키별 맵이다).
    pub hashes: HashMap<String, Vec<String>>,
}

impl Active {
    /// 번들 디렉터리에서 HTML 을 찾아 inline 스크립트 해시를 계산한다.
    ///
    /// 여기서 실패하면 그 번들은 **거부**된다. 조용히 하얀 화면이 뜨는 것보다
    /// 임베드로 내려앉는 편이 낫다.
    pub fn load(root: PathBuf) -> Option<Self> {
        let mut hashes = HashMap::new();
        let mut html_count = 0usize;
        let mut script_count = 0usize;

        for path in html_files(&root) {
            let Ok(text) = std::fs::read_to_string(&path) else {
                // 읽지 못하는 HTML 이 하나라도 있으면 그 화면만 깨진다. 거부한다.
                println!("[mupid-assets] HTML 을 읽지 못했습니다: {}", path.display());
                return None;
            };
            // 자산 키는 번들 루트 기준 절대 모양이다 — `/index.html`.
            let relative = path.strip_prefix(&root).ok()?;
            let key = format!("/{}", relative.to_string_lossy().replace('\\', "/"));

            let list = inline_script_hashes(&text);
            script_count += list.len();
            if !list.is_empty() {
                hashes.insert(key, list);
            }
            html_count += 1;
        }

        if html_count == 0 {
            println!(
                "[mupid-assets] HTML 이 없는 번들은 쓰지 않습니다: {}",
                root.display()
            );
            return None;
        }

        println!(
            "[mupid-assets] 활성 번들 {} — HTML {html_count}개, inline 스크립트 해시 {script_count}개",
            root.display()
        );
        Some(Self { root, hashes })
    }

    /// 이 번들이 가진 inline 스크립트 해시의 총 개수. 진단용.
    pub fn hash_count(&self) -> usize {
        self.hashes.values().map(Vec::len).sum()
    }

    /// 번들 안의 파일 하나를 읽는다. 경로 안전 검사를 통과한 것만.
    pub fn read(&self, key: &str) -> Option<Vec<u8>> {
        let path = resolve(&self.root, key)?;
        // **심볼릭 링크를 따라가지 않는다.** 해제할 때 막았더라도 여기서 한 번 더
        // 막는다 — 번들 디렉터리에 나중에 무엇이 생길지 우리는 모른다.
        let meta = std::fs::symlink_metadata(&path).ok()?;
        if !meta.file_type().is_file() {
            return None;
        }
        std::fs::read(&path).ok()
    }
}

/// 지금 프로세스가 쓰는 활성 번들.
///
/// 모듈 전역인 이유는 두 곳에서 같은 답을 봐야 하기 때문이다 — 자산 서빙
/// (`LayeredAssets`)과 진단 커맨드(`assets_status`). 창이 하나인 앱이라
/// 프로세스마다 한 번 정하면 충분하고, 그 편이 두 곳이 어긋날 여지를 없앤다.
static ACTIVE: OnceLock<Option<Active>> = OnceLock::new();

/// 활성 번들. 없으면 임베드를 쓰고 있다는 뜻이다.
pub fn active() -> Option<&'static Active> {
    ACTIVE.get().and_then(|value| value.as_ref())
}

/// 서빙 출처를 **한 번씩만** 로그로 남긴다.
///
/// 교체는 조용히 일어나므로 밖에서 확인할 통로가 필요하다. 자산 요청마다
/// 찍으면 수백 줄이 되니, 디스크에서 처음 준 것과 임베드에서 처음 준 것을
/// 각각 한 번만 남긴다 — 실기기에서 `adb logcat` 으로 어느 쪽이 쓰이는지
/// 바로 알 수 있다.
static LOGGED_DISK: AtomicBool = AtomicBool::new(false);
static LOGGED_EMBEDDED: AtomicBool = AtomicBool::new(false);

/// 번들 루트 아래의 모든 `.html` 을 찾는다. 하위 디렉터리까지 본다
/// (`email/verify.html` 처럼 깊은 화면이 있다).
fn html_files(root: &Path) -> Vec<PathBuf> {
    let mut found = Vec::new();
    let mut stack = vec![root.to_path_buf()];
    while let Some(directory) = stack.pop() {
        let Ok(entries) = std::fs::read_dir(&directory) else {
            continue;
        };
        for entry in entries.flatten() {
            // 심볼릭 링크는 탐색조차 하지 않는다.
            let Ok(meta) = entry.metadata() else { continue };
            let path = entry.path();
            if meta.is_dir() {
                stack.push(path);
            } else if meta.is_file()
                && path.extension().and_then(|value| value.to_str()) == Some("html")
            {
                found.push(path);
            }
        }
    }
    found.sort();
    found
}

/// HTML 안의 **비어 있지 않은** `<script>` 텍스트를 Tauri 와 같은 규칙으로 해싱한다.
///
/// 선택과 텍스트 추출을 `tauri_utils::html2` 에 맡기는 것이 핵심이다. Tauri 는
/// `document.select("script:not(:empty)")` 로 고르는데, 주석·CDATA·중첩에서
/// 원시 문자열 처리는 브라우저의 HTML 해석과 어긋난다. 한 글자만 달라도 해시가
/// 틀려 그 스크립트가 차단된다.
fn inline_script_hashes(html: &str) -> Vec<String> {
    let document = tauri_utils::html2::parse_doc(html.to_string());
    let mut hashes = Vec::new();
    for element in document.select("script:not(:empty)").iter() {
        let script = tauri_utils::html2::normalize_script_for_csp(element.text().as_bytes());
        let digest = Sha256::digest(&script);
        hashes.push(format!(
            "'sha256-{}'",
            base64::engine::general_purpose::STANDARD.encode(digest)
        ));
    }
    hashes
}

/// 자산 키를 번들 디렉터리 안의 경로로 바꾼다. **여기가 유일한 신뢰 경계다.**
///
/// `receive-front.py` 가 이미 정한 규칙을 그대로 가져온다. 새 규칙을 만들지
/// 않는다 — 절대 경로 금지, `..` 금지. `Normal` 이 아닌 컴포넌트는 전부 거부한다.
///
/// `update.rs` 도 이 함수를 쓴다. 아카이브를 풀 때와 서빙할 때 **같은 판단**을
/// 해야 하고, 두 벌로 두면 언젠가 한쪽만 고쳐진다.
pub(crate) fn resolve(root: &Path, key: &str) -> Option<PathBuf> {
    // 슬래시를 **하나만** 벗긴다. `trim_start_matches` 로 전부 벗기면
    // `//etc/passwd` 가 상대 경로 `etc/passwd` 로 조용히 바뀌어, 절대 경로를
    // 거부한다는 규칙이 무력해진다. 하나만 벗기면 남은 `/` 가 RootDir 컴포넌트로
    // 남아 아래에서 걸린다.
    let relative = key.strip_prefix('/').unwrap_or(key);
    if relative.is_empty() {
        return None;
    }
    let mut out = root.to_path_buf();
    for component in Path::new(relative).components() {
        match component {
            Component::Normal(part) => {
                // 윈도우에서 역슬래시는 구분자다. 유닉스에서는 이름의 일부일 뿐이라
                // 그대로 두면 두 해석이 갈린다 — 그 자리를 없앤다.
                if part.to_string_lossy().contains('\\') {
                    return None;
                }
                out.push(part);
            }
            // RootDir(절대 경로) · ParentDir(`..`) · CurDir · Prefix(`C:`) — 전부 거부.
            _ => return None,
        }
    }
    Some(out)
}

/// 활성 포인터를 읽어 번들을 연다. 없거나 어긋나면 `None` 을 주고 임베드로 내려앉는다.
fn load_bundle(directory: &Path) -> Option<Active> {
    let pointer = std::fs::read_to_string(directory.join(POINTER)).ok()?;
    let name = pointer.trim();
    // 포인터는 빌드 타임스탬프(숫자)만 담는다. 이름에 무엇이든 넣을 수 있게 두면
    // 여기가 경로 조작 통로가 된다.
    if name.is_empty() || !name.bytes().all(|byte| byte.is_ascii_digit()) {
        println!("[mupid-assets] 활성 포인터가 이상합니다: {name:?}");
        return None;
    }
    let root = directory.join(name);
    if !root.is_dir() {
        println!(
            "[mupid-assets] 활성 번들이 사라졌습니다: {}",
            root.display()
        );
        return None;
    }
    Active::load(root)
}

/// 디스크 우선 · 임베드 폴백.
pub struct LayeredAssets<R: Runtime> {
    /// 임베드 번들. 마지막 안전망이라 절대 비우지 않는다.
    embedded: Box<dyn Assets<R>>,
}

impl<R: Runtime> LayeredAssets<R> {
    pub fn new(embedded: Box<dyn Assets<R>>) -> Self {
        Self { embedded }
    }
}

impl<R: Runtime> Assets<R> for LayeredAssets<R> {
    /// 창이 만들어지기 전에 돈다. 여기서 활성 번들을 정해 두면 첫 화면부터
    /// 디스크 번들로 뜬다 (호출 순서: `tauri` 의 `setup()` 안,
    /// `app.manager.assets.setup(app)` → `app.setup` 클로저).
    ///
    /// **디스크를 쓰기로 한 번 정하면 이 프로세스 동안 바뀌지 않는다.**
    /// 업데이트는 다음 콜드 스타트에 적용한다 — 그래야 듣던 곡이 끊기지 않는다
    /// (`frontend/vite.config.ts` 가 기록한 전체 새로고침 사고와 같은 이유).
    fn setup(&self, app: &App<R>) {
        let resolved = app
            .path()
            .app_data_dir()
            .ok()
            .map(|dir| dir.join(WEB_DIR))
            .and_then(|dir| load_bundle(dir.as_path()));

        match &resolved {
            Some(active) => println!(
                "[mupid-assets] 디스크 번들을 씁니다: {}",
                active.root.display()
            ),
            None => println!("[mupid-assets] 임베드 번들을 씁니다 (디스크에 활성 번들 없음)"),
        }
        // 이미 정해졌다면 첫 값을 지킨다.
        let _ = ACTIVE.set(resolved);
    }

    fn get(&self, key: &AssetKey) -> Option<Cow<'_, [u8]>> {
        if let Some(active) = active() {
            if let Some(bytes) = active.read(key.as_ref()) {
                if !LOGGED_DISK.swap(true, Ordering::Relaxed) {
                    println!("[mupid-assets] 디스크에서 서빙: {}", key.as_ref());
                }
                return Some(Cow::Owned(bytes));
            }
        }
        let from_embedded = self.embedded.get(key);
        if from_embedded.is_some() && !LOGGED_EMBEDDED.swap(true, Ordering::Relaxed) {
            println!("[mupid-assets] 임베드에서 서빙: {}", key.as_ref());
        }
        from_embedded
    }

    /// HTML 자산의 inline 스크립트 해시.
    ///
    /// 활성 번들의 HTML 이면 **활성화 시점에 계산해 둔 해시**를, 아니면 임베드
    /// 번들이 컴파일 시점에 박아 둔 해시를 돌려준다. 이걸 빠뜨리면 교체된
    /// 번들의 스크립트가 전부 차단되어 화면이 하얗게 뜬다.
    fn csp_hashes<'a>(&'a self, html_path: &AssetKey) -> Box<dyn Iterator<Item = CspHash<'a>> + 'a> {
        if let Some(active) = active() {
            if let Some(list) = active.hashes.get(html_path.as_ref()) {
                return Box::new(list.iter().map(|hash| CspHash::Script(hash.as_str())));
            }
        }
        self.embedded.csp_hashes(html_path)
    }

    /// 임베드 쪽에 위임한다. 디스크 파일을 열거할 일이 없다.
    fn iter(&self) -> Box<AssetsIter<'_>> {
        self.embedded.iter()
    }
}

/// `Context.assets` 를 꺼내는 동안 잠깐 끼워 두는 빈 프로바이더.
///
/// `Box<dyn Assets>` 를 `mem::replace` 로 빼내려면 대신 넣을 값이 필요하다.
/// 이 값은 실제로 서빙에 쓰이지 않는다 — 곧바로 `LayeredAssets` 로 덮인다.
pub struct EmptyAssets;

impl<R: Runtime> Assets<R> for EmptyAssets {
    fn get(&self, _key: &AssetKey) -> Option<Cow<'_, [u8]>> {
        None
    }
    fn iter(&self) -> Box<AssetsIter<'_>> {
        Box::new(std::iter::empty())
    }
    fn csp_hashes<'a>(&'a self, _html_path: &AssetKey) -> Box<dyn Iterator<Item = CspHash<'a>> + 'a> {
        Box::new(std::iter::empty())
    }
}

#[cfg(test)]
mod tests {
    use super::{inline_script_hashes, resolve, Active};
    use std::path::Path;

    /// `..` 나 절대 경로가 번들 밖으로 나가면 안 된다. 여기가 유일한 신뢰 경계다.
    #[test]
    fn asset_keys_cannot_escape_the_bundle() {
        let root = Path::new("/bundle");
        assert_eq!(
            resolve(root, "/index.html"),
            Some(root.join("index.html")),
            "정상 키는 통과해야 한다"
        );
        assert_eq!(
            resolve(root, "/email/verify.html"),
            Some(root.join("email").join("verify.html")),
            "깊은 경로도 통과해야 한다"
        );

        for bad in [
            "/../etc/passwd",
            "/email/../../etc/passwd",
            "//etc/passwd",
            "/..",
            "/",
            "",
            "/index\\..\\evil.html",
        ] {
            assert_eq!(resolve(root, bad), None, "거부해야 한다: {bad:?}");
        }
    }

    /// 번들 밖을 가리키는 심볼릭 링크는 읽지 않는다.
    #[test]
    fn symlinks_are_not_followed() {
        let directory = std::env::temp_dir().join(format!("mupid-assets-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&directory);
        let outside = directory.join("outside.txt");
        std::fs::create_dir_all(&directory).expect("디렉터리");
        std::fs::write(&outside, b"secret").expect("쓰기");

        let bundle = directory.join("bundle");
        std::fs::create_dir_all(&bundle).expect("디렉터리");
        let link = bundle.join("leak.txt");
        #[cfg(unix)]
        std::os::unix::fs::symlink(&outside, &link).expect("심볼릭 링크");
        #[cfg(windows)]
        {
            // 윈도우에서 심볼릭 링크는 권한이 필요하다. 만들지 못하면 시험을
            // 건너뛴다 — 확인하려는 것은 "따라가지 않는다"이지 링크 생성이 아니다.
            if std::os::windows::fs::symlink_file(&outside, &link).is_err() {
                let _ = std::fs::remove_dir_all(&directory);
                return;
            }
        }

        let active = Active {
            root: bundle.clone(),
            hashes: Default::default(),
        };
        assert_eq!(active.read("/leak.txt"), None, "심볼릭 링크를 따라갔다");

        std::fs::write(bundle.join("real.txt"), b"ok").expect("쓰기");
        assert_eq!(active.read("/real.txt").as_deref(), Some(&b"ok"[..]));

        let _ = std::fs::remove_dir_all(&directory);
    }

    /// inline 스크립트 해시가 Tauri 와 **같은 규칙**으로 나오는지.
    ///
    /// 이 값이 어긋나면 교체된 번들의 스크립트가 전부 차단되어 화면이 하얗게
    /// 뜨고, CSP 위반은 조용해서 원인이 보이지 않는다. 그래서 여기서 못 박는다.
    #[test]
    fn inline_script_hashes_follow_the_tauri_rule() {
        let html = r#"<html><head>
<script src="/app.js"></script>
<script>var a = 1;</script>
<script>   </script>
<script>var b = 2;</script>
</head></html>"#;
        let hashes = inline_script_hashes(html);

        // Tauri 는 `script:not(:empty)` 를 고르므로 내용이 있는 둘만 대상이다.
        // `src` 만 있고 내용이 없는 첫 태그와 공백뿐인 셋째는 빠진다.
        assert_eq!(hashes.len(), 2, "비어 있는 스크립트는 대상이 아니다: {hashes:?}");

        // 값이 고정된 sha256 인지 (규칙이 Tauri 와 같은지) 직접 계산해 맞춘다.
        use base64::Engine;
        use sha2::{Digest, Sha256};
        for (index, body) in ["var a = 1;", "var b = 2;"].iter().enumerate() {
            let expected = format!(
                "'sha256-{}'",
                base64::engine::general_purpose::STANDARD.encode(Sha256::digest(body.as_bytes()))
            );
            assert_eq!(hashes[index], expected, "해시 규칙이 어긋났다");
        }
    }

    /// CRLF 와 CR 이 LF 로 정규화되어야 한다 — Tauri 의
    /// `normalize_script_for_csp` 와 같은 규칙이다. 윈도우에서 체크아웃한
    /// HTML 이 이 때문에 다른 해시를 내면 전부 차단된다.
    #[test]
    fn crlf_and_cr_normalize_like_tauri() {
        let lf = inline_script_hashes("<script>a\nb</script>");
        let crlf = inline_script_hashes("<script>a\r\nb</script>");
        let cr = inline_script_hashes("<script>a\rb</script>");
        assert_eq!(lf, crlf, "CRLF 는 LF 와 같은 해시여야 한다");
        assert_eq!(lf, cr, "홑 CR 도 LF 와 같은 해시여야 한다");
    }
}
