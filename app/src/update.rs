//! 자동 업데이트 — 버전 조회, 검증, 전개, 활성화.
//!
//! 두 갈래가 있다 (설계 6장).
//!
//! | 갈래 | 무엇을 바꾸나 | 판단 기준 |
//! | --- | --- | --- |
//! | 웹 | 번들 파일만 (APK 재설치 없음) | `builtAt` 이 **다르면** 교체 |
//! | 네이티브 | APK 자체 (재설치 필요) | `versionCode` 가 **클 때만** 안내 |
//!
//! 빌드 타임스탬프가 버전을 대신한다. `builtAt` 은 epoch ms UTC 다.
//!
//! ## 이 파일이 지키는 규칙
//!
//! **아티팩트를 먼저 다 놓고 스위치를 마지막에 젖힌다.** `receive-k8s.py` 와
//! `receive-apk.py` 가 이미 쓰는 순서다. 중간에 죽어도 활성 번들은 옛것 그대로라
//! 앱이 벽돌이 되지 않는다.
//!
//! **경로 안전은 `assets::resolve` 한 곳에만 있다.** 여기서 복제하지 않는다 —
//! 두 벌이 되면 언젠가 한쪽만 고쳐지고, 그때 뚫리는 쪽은 조용하다.
//!
//! **내려받은 바이트를 인자로 받는다.** 네트워크를 안에 두면 실기기 없이는
//! 아무것도 검증할 수 없다. 검증·전개·활성화는 순수 함수라 단위 시험으로
//! 전부 덮인다(`#[cfg(test)]` 참고).

use std::collections::BTreeSet;
use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::assets;
use crate::db::Db;
use crate::store::kv;

/// 활성 번들의 이름이 든 포인터 파일. **이 한 줄이 스위치다.**
/// `assets.rs` 의 같은 상수를 쓰므로 한쪽만 바뀌면 안 된다.
pub use crate::assets::POINTER;

// ─────────────────────────────────────────────────────────────
// 상한 — 압축 폭탄과 무한 수신을 막는다
// ─────────────────────────────────────────────────────────────

/// 내려받는 아카이브 자체의 크기 상한. 코드 전용 아카이브는 약 319KB 다(실측).
/// 여유를 크게 두되, 통짜 번들(36MB)을 실수로 올려 둔 경우는 걸러낸다.
///
/// `update_net.rs` 가 내려받을 때 이 값을 그대로 쓴다 — **매니페스트가 말한
/// `bytes` 를 상한으로 쓰지 않는다.** 서버가 그 값을 크게 적어 두면 상한이
/// 무의미해진다.
pub const MAX_ARCHIVE_BYTES: u64 = 4 * 1024 * 1024;

/// 내려받는 APK 의 크기 상한.
///
/// **디버그 빌드가 릴리스보다 훨씬 크다.** 실측: 배포 APK 51MB, 디버그 APK
/// 218MB(네 ABI + 디버그 심볼). 개발자가 디버그 빌드로 업데이트를 시험하다
/// 상한에 걸리면 원인을 찾기 어렵다 — 그래서 넉넉히 둔다.
///
/// 이 값이 지키는 것은 **메모리가 아니라 디스크**다. 실제 내려받기는
/// `update_net.rs` 가 조각마다 파일에 쓰며 해시를 누적하므로 램 사용량은
/// 조각 크기만큼이다(예전에는 본문을 통째로 `Vec<u8>` 에 담아 51MB 를 두 벌
/// 들고 있었다).
pub const MAX_APK_BYTES: u64 = 512 * 1024 * 1024;

/// 펼친 뒤 총량의 절대 상한. 정적 자산을 빼면 약 1.4MB 다(실측).
/// 서버가 알려준 `expandedBytes` 와 **둘 다** 검사한다.
const MAX_EXPANDED_BYTES: u64 = 24 * 1024 * 1024;

/// 항목 하나의 상한. 가장 큰 js 청크가 약 392KB 다(실측).
const MAX_MEMBER_BYTES: u64 = 8 * 1024 * 1024;

/// 파일 개수 상한. 실측 49개다.
const MAX_FILES: usize = 2048;

/// 디스크에 남겨 두는 번들 수(현재 + 직전 하나). 롤백 여지를 남긴다.
const KEEP_BUNDLES: usize = 2;

/// 거부 목록을 담는 kv 키. 같은 `builtAt` 이 두 번 실패하면 다시 시도하지 않는다.
pub const REJECTED_KEY: &str = "update.rejected";

// ─────────────────────────────────────────────────────────────
// 빌드 시점에 박히는 값
// ─────────────────────────────────────────────────────────────

/// 이 APK 에 **임베드된** 웹 번들의 빌드 타임스탬프 (epoch ms, UTC).
///
/// 임베드는 디스크 번들이 없을 때 쓰는 안전망이다. 그 나이를 앱이 알아야
/// 하는 이유: 새 APK 를 설치한 직후 임베드 번들은 이미 최신인데, 이 값을
/// 모르면 "서버 웹 빌드 != 내 빌드" 가 되어 **방금 설치한 앱이 자기 번들을
/// 다시 내려받는다.**
///
/// CI 가 프론트 빌드와 같은 값으로 넣는다. 없으면 0 — 로컬 빌드가 죽지 않는다.
pub fn embedded_web_build() -> u64 {
    option_env!("MUPID_WEB_BUILD")
        .and_then(|value| value.trim().parse::<u64>().ok())
        .unwrap_or(0)
}

/// 이 APK 자체의 빌드 타임스탬프 (epoch **ms**, UTC).
///
/// **웹과 같은 단위를 쓴다.** 단위가 섞이면 어느 쪽이 더 새로운지 비교하는
/// 자리에서 조용히 어긋난다 — 초와 밀리초를 맞대면 항상 한쪽이 크다.
pub fn native_build_ms() -> u64 {
    option_env!("MUPID_NATIVE_BUILD")
        .and_then(|value| value.trim().parse::<u64>().ok())
        .unwrap_or(0)
}

/// 안드로이드가 보는 `versionCode`.
///
/// `gen/android/app/build.gradle.kts` 가 `MUPID_NATIVE_BUILD`(ms) 를 1000 으로
/// 나눠 같은 값을 만든다. 한 값을 두 곳이 **같은 식으로** 접근해야 "gradle 이
/// 아는 버전" 과 "앱이 아는 버전" 이 어긋나지 않는다.
///
/// 초로 내리는 이유: 안드로이드 `versionCode` 상한이 2,100,000,000 이고,
/// epoch 초는 2036년에 거기 닿는다(밀리초를 그대로 쓰면 즉시 넘는다).
pub fn local_version_code() -> u64 {
    native_build_ms() / 1000
}

/// 지금 자산으로 **쓰이고 있는** 웹 번들의 나이.
///
/// 디스크 번들이 활성이면 그 번들이 스스로 밝힌 값(`app/build.json`),
/// 아니면 임베드 시점에 박힌 값. 번들 안에 `build.json` 을 두는 이유가
/// 이것이다 — 앱이 자기 번들의 나이를 스스로 안다.
pub fn local_web_build() -> u64 {
    match assets::active() {
        Some(active) => read_bundle_build(&active.root).unwrap_or(0),
        None => embedded_web_build(),
    }
}

/// 번들 안 `app/build.json` 의 `builtAt`. 없거나 깨져 있으면 `None`.
pub fn read_bundle_build(root: &Path) -> Option<u64> {
    let text = fs::read_to_string(root.join("app").join("build.json")).ok()?;
    let value: serde_json::Value = serde_json::from_str(&text).ok()?;
    value.get("builtAt")?.as_u64()
}

// ─────────────────────────────────────────────────────────────
// 서버가 내려주는 매니페스트 (설계 6.1)
// ─────────────────────────────────────────────────────────────

/// 웹 갈래 매니페스트. `GET /download/web.json`
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WebManifest {
    pub schema: u32,
    /// 빌드 타임스탬프 (epoch ms). 이것이 버전이다.
    pub built_at: u64,
    /// 아카이브 경로 (사이트 기준 절대 경로).
    pub bundle: String,
    pub sha256: String,
    pub bytes: u64,
    /// 아카이브에 든 파일 수. 전개 결과와 대조한다.
    pub files: usize,
    /// 펼친 뒤 총 바이트. 압축 폭탄을 거르는 데 쓴다.
    pub expanded_bytes: u64,
    /// 진입점. `index.html` 이어야 앱이 뜬다.
    pub entry: String,
}

/// 네이티브 갈래 매니페스트. `GET /download/native.json`
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NativeManifest {
    pub schema: u32,
    pub built_at: u64,
    /// 안드로이드 `versionCode`. **이 값이 단조 증가해야 업그레이드가 성립한다.**
    pub version_code: u64,
    pub commit: String,
    pub apk: String,
    pub sha256: String,
    pub bytes: u64,
    pub min_sdk: u32,
}

/// 우리가 아는 스키마 버전. 모르는 값이면 손대지 않는다 — 새 필드의 의미를
/// 모르는 채로 적용하면 조용히 어긋난다.
pub const SCHEMA: u32 = 1;

// ─────────────────────────────────────────────────────────────
// 오류
// ─────────────────────────────────────────────────────────────

#[derive(Debug, thiserror::Error)]
pub enum UpdateError {
    #[error("아카이브 체크섬이 맞지 않습니다")]
    Checksum,
    #[error("아카이브 크기가 맞지 않습니다 (기대 {expected}, 실제 {actual})")]
    Size { expected: u64, actual: u64 },
    #[error("압축을 풀지 못했습니다: {0}")]
    Archive(String),
    #[error("안전하지 않은 경로가 들어 있습니다: {0}")]
    UnsafePath(String),
    #[error("일반 파일도 디렉터리도 아닌 항목이 있습니다: {0}")]
    NonRegular(String),
    #[error("상한을 넘었습니다: {0}")]
    Limit(String),
    #[error("전개 결과가 매니페스트와 다릅니다: {0}")]
    Mismatch(String),
    #[error("알 수 없는 스키마입니다: {0}")]
    Schema(u32),
    #[error("파일 입출력 실패: {0}")]
    Io(#[from] std::io::Error),
}

// ─────────────────────────────────────────────────────────────
// 검증
// ─────────────────────────────────────────────────────────────

/// 바이트의 sha256 을 소문자 16진수로.
pub fn sha256_hex(bytes: &[u8]) -> String {
    let digest = Sha256::digest(bytes);
    let mut out = String::with_capacity(digest.len() * 2);
    for byte in digest {
        out.push(char::from_digit((byte >> 4) as u32, 16).unwrap_or('0'));
        out.push(char::from_digit((byte & 0x0f) as u32, 16).unwrap_or('0'));
    }
    out
}

/// 내려받은 아카이브가 매니페스트가 말한 그것인지 확인한다.
///
/// **순서가 중요하다.** 크기를 먼저 보고(값싼 검사) 체크섬을 나중에 본다.
pub fn verify_archive(bytes: &[u8], manifest: &WebManifest) -> Result<(), UpdateError> {
    if manifest.schema != SCHEMA {
        return Err(UpdateError::Schema(manifest.schema));
    }
    let actual = bytes.len() as u64;
    if actual != manifest.bytes {
        return Err(UpdateError::Size {
            expected: manifest.bytes,
            actual,
        });
    }
    if actual > MAX_ARCHIVE_BYTES {
        return Err(UpdateError::Limit(format!(
            "아카이브가 {actual} 바이트입니다 (상한 {MAX_ARCHIVE_BYTES})"
        )));
    }
    // 대소문자 차이로 거부하지 않는다 — 16진수 표기가 관례마다 다르다.
    if !sha256_hex(bytes).eq_ignore_ascii_case(&manifest.sha256) {
        return Err(UpdateError::Checksum);
    }
    Ok(())
}

/// 전개한 결과의 요약. 매니페스트와 대조한다.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Expanded {
    pub files: usize,
    pub bytes: u64,
}

// ─────────────────────────────────────────────────────────────
// 전개 — 항목을 하나씩 검사하며 쓴다
// ─────────────────────────────────────────────────────────────

/// tar.gz 를 `target` 아래에 푼다. **`extractall` 을 쓰지 않는다.**
///
/// `receive-front.py` 가 정한 규칙을 그대로 따른다.
///
/// - 절대 경로·`..` 금지 (`assets::resolve`)
/// - 일반 파일과 디렉터리만 — **심볼릭 링크·하드 링크 거부**
/// - 파일 수·항목 크기·총량 상한
/// - 같은 이름이 두 번 나오면 거부 (덮어쓰기로 검사를 우회할 수 있다)
pub fn expand(archive: &[u8], target: &Path) -> Result<Expanded, UpdateError> {
    let decoder = flate2::read::GzDecoder::new(archive);
    let mut tar = tar::Archive::new(decoder);
    // GNU long name·pax 헤더를 허용한다. 우리 아카이브는 ustar 지만,
    // 다른 도구로 만든 것을 거부할 이유는 없다 — 아래 검사가 실질을 본다.
    tar.set_overwrite(false);

    let mut files = 0usize;
    let mut bytes = 0u64;
    let mut seen: BTreeSet<String> = BTreeSet::new();

    // 디렉터리 항목이 먼저 오지 않아도 되도록, 파일을 쓸 때 부모를 만든다.
    fs::create_dir_all(target)?;

    for entry in tar.entries().map_err(|error| UpdateError::Archive(error.to_string()))? {
        let entry = entry.map_err(|error| UpdateError::Archive(error.to_string()))?;
        let kind = entry.header().entry_type();
        let raw = entry
            .path()
            .map_err(|error| UpdateError::Archive(error.to_string()))?
            .to_string_lossy()
            .replace('\\', "/");
        // `./` 접두사는 도구마다 붙였다 뗐다 한다. 뜻이 같으므로 벗긴다.
        let name = raw.trim_start_matches("./").trim_end_matches('/').to_string();

        if name.is_empty() {
            continue;
        }
        if !seen.insert(name.clone()) {
            return Err(UpdateError::UnsafePath(format!("같은 이름이 두 번: {name}")));
        }

        // 경로 안전은 `assets.rs` 한 곳에서만 판단한다.
        let destination =
            assets::resolve(target, &format!("/{name}")).ok_or_else(|| UpdateError::UnsafePath(name.clone()))?;

        if kind.is_dir() {
            fs::create_dir_all(&destination)?;
            continue;
        }
        if !kind.is_file() {
            // 심볼릭 링크·하드 링크·문자 장치·FIFO — 전부 거부.
            // 링크 하나로 번들 밖 파일을 읽게 만들 수 있다.
            return Err(UpdateError::NonRegular(name));
        }

        let size = entry.header().size().unwrap_or(0);
        if size > MAX_MEMBER_BYTES {
            return Err(UpdateError::Limit(format!(
                "{name} 이 {size} 바이트입니다 (항목 상한 {MAX_MEMBER_BYTES})"
            )));
        }
        bytes = bytes.saturating_add(size);
        if bytes > MAX_EXPANDED_BYTES {
            return Err(UpdateError::Limit(format!(
                "펼친 총량이 {bytes} 바이트를 넘습니다 (상한 {MAX_EXPANDED_BYTES})"
            )));
        }

        files += 1;
        if files > MAX_FILES {
            return Err(UpdateError::Limit(format!(
                "파일이 {files}개를 넘습니다 (상한 {MAX_FILES})"
            )));
        }

        if let Some(parent) = destination.parent() {
            fs::create_dir_all(parent)?;
        }
        // 선언한 크기보다 많이 흘러나오는 아카이브를 막는다. `size` 를 믿고
        // 그대로 받으면 헤더가 거짓말할 때 상한이 무의미해진다.
        let mut body = Vec::with_capacity(size as usize);
        entry
            .take(MAX_MEMBER_BYTES + 1)
            .read_to_end(&mut body)
            .map_err(|error| UpdateError::Archive(error.to_string()))?;
        if body.len() as u64 > MAX_MEMBER_BYTES {
            return Err(UpdateError::Limit(format!("{name} 의 실제 내용이 상한을 넘습니다")));
        }
        fs::write(&destination, &body)?;
    }

    Ok(Expanded { files, bytes })
}

// ─────────────────────────────────────────────────────────────
// 활성화 — 아티팩트 먼저, 스위치 마지막
// ─────────────────────────────────────────────────────────────

/// 활성화 결과.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Applied {
    pub built_at: u64,
    pub files: usize,
    pub bytes: u64,
}

/// 이미 내려받은 아카이브를 검증하고 활성화한다.
///
/// `root` 는 `app_data_dir()/web` 이다.
///
/// 순서 (설계 7.2.2). 하나라도 어긋나면 **활성 포인터를 건드리지 않고** 끝낸다.
///
/// 1. 매니페스트 검증 (스키마·크기·체크섬)
/// 2. `web/<builtAt>.new/` 에 전개
/// 3. 매니페스트와 전개 결과 대조 (`files`·`expandedBytes`·진입점)
/// 4. 번들 안 `app/build.json` 의 `builtAt` 이 요청한 값과 같은지
/// 5. `web/<builtAt>` 로 rename (같은 파일시스템이라 원자적)
/// 6. **`active` 를 마지막에** 바꾼다
/// 7. 오래된 번들 정리
pub fn activate(root: &Path, archive: &[u8], manifest: &WebManifest) -> Result<Applied, UpdateError> {
    verify_archive(archive, manifest)?;

    if manifest.entry != "index.html" {
        // 진입점이 다르면 화면이 뜨지 않는다. 서버가 무엇을 올렸든 거부한다.
        return Err(UpdateError::Mismatch(format!(
            "진입점이 index.html 이 아닙니다: {}",
            manifest.entry
        )));
    }

    let staging = root.join(format!("{}.new", manifest.built_at));
    let final_path = root.join(manifest.built_at.to_string());

    // 지난번에 중간에 죽었을 수 있다. 찌꺼기를 먼저 치운다.
    if staging.exists() {
        fs::remove_dir_all(&staging)?;
    }
    fs::create_dir_all(root)?;

    let expanded = match expand(archive, &staging) {
        Ok(value) => value,
        Err(error) => {
            // 실패한 찌꺼기가 다음 기동의 탐색에 걸리지 않게 치운다.
            let _ = fs::remove_dir_all(&staging);
            return Err(error);
        }
    };

    // 매니페스트가 말한 것과 실제가 같은가. 여기가 "남의 번들을 우리 것으로
    // 믿지 않는" 자리다.
    if expanded.files != manifest.files {
        let _ = fs::remove_dir_all(&staging);
        return Err(UpdateError::Mismatch(format!(
            "파일 수가 다릅니다 (기대 {}, 실제 {})",
            manifest.files, expanded.files
        )));
    }
    if expanded.bytes != manifest.expanded_bytes {
        let _ = fs::remove_dir_all(&staging);
        return Err(UpdateError::Mismatch(format!(
            "펼친 크기가 다릅니다 (기대 {}, 실제 {})",
            manifest.expanded_bytes, expanded.bytes
        )));
    }

    // 번들이 스스로 밝힌 나이가 요청한 값과 같은가. 다르면 다른 빌드다.
    match read_bundle_build(&staging) {
        Some(value) if value == manifest.built_at => {}
        Some(value) => {
            let _ = fs::remove_dir_all(&staging);
            return Err(UpdateError::Mismatch(format!(
                "번들이 밝힌 빌드 시각({value})이 요청한 값({})과 다릅니다",
                manifest.built_at
            )));
        }
        None => {
            let _ = fs::remove_dir_all(&staging);
            return Err(UpdateError::Mismatch(
                "번들 안에 app/build.json 이 없습니다".to_string(),
            ));
        }
    }

    // 같은 이름이 이미 있으면(예: 거부 목록을 비우고 재시도) 지우고 바꾼다.
    if final_path.exists() {
        fs::remove_dir_all(&final_path)?;
    }
    fs::rename(&staging, &final_path)?;

    // **여기서부터가 스위치다.** 이 줄 전까지 죽으면 옛 번들이 그대로 산다.
    write_pointer(root, manifest.built_at)?;

    prune(root, manifest.built_at)?;

    Ok(Applied {
        built_at: manifest.built_at,
        files: expanded.files,
        bytes: expanded.bytes,
    })
}

/// 활성 포인터를 원자적으로 바꾼다.
///
/// 임시 파일에 쓰고 rename 한다. `fs::write` 로 직접 쓰면 그 사이에 죽었을 때
/// 포인터가 반쪽이 되어(빈 파일·잘린 숫자) 활성 번들이 통째로 사라진다.
fn write_pointer(root: &Path, built_at: u64) -> Result<(), UpdateError> {
    let temporary = root.join(".active.tmp");
    fs::write(&temporary, built_at.to_string())?;
    let target = root.join(POINTER);
    // 윈도우에서 rename 은 대상이 있으면 실패한다. 유닉스(안드로이드)에서는
    // 덮어쓴다. 두 곳에서 같은 결과가 되도록 먼저 지운다.
    if target.exists() {
        let _ = fs::remove_file(&target);
    }
    fs::rename(&temporary, &target)?;
    Ok(())
}

/// 오래된 번들을 지운다. **활성 번들과 직전 하나는 남긴다.**
///
/// 롤백 여지를 남기는 것이 목적이다. 실패한 번들은 여기 오기 전에 지워지므로
/// `.new` 찌꺼기도 함께 치운다.
pub fn prune(root: &Path, active: u64) -> Result<Vec<u64>, UpdateError> {
    let mut bundles: Vec<u64> = Vec::new();
    let Ok(entries) = fs::read_dir(root) else {
        return Ok(bundles);
    };
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        let path = entry.path();

        // `.new` 찌꺼기 — 이번 기동에서 쓰이지 않는다. 치운다.
        if let Some(stem) = name.strip_suffix(".new") {
            if stem.bytes().all(|byte| byte.is_ascii_digit()) && path.is_dir() {
                let _ = fs::remove_dir_all(&path);
            }
            continue;
        }
        if name.bytes().all(|byte| byte.is_ascii_digit()) && path.is_dir() {
            bundles.push(name.parse().unwrap_or(0));
        }
    }

    // 최신부터. 활성 번들이 목록에 없을 수도 있다(직접 지워진 경우).
    bundles.sort_unstable_by(|a, b| b.cmp(a));
    let mut keep: Vec<u64> = Vec::with_capacity(KEEP_BUNDLES);
    if !bundles.contains(&active) {
        keep.push(active);
    }
    for value in &bundles {
        if keep.len() >= KEEP_BUNDLES {
            break;
        }
        keep.push(*value);
    }

    let mut removed = Vec::new();
    for value in bundles {
        if !keep.contains(&value) {
            let _ = fs::remove_dir_all(root.join(value.to_string()));
            removed.push(value);
        }
    }
    Ok(removed)
}

// ─────────────────────────────────────────────────────────────
// 거부 목록 — 무한 재시도를 막는다
// ─────────────────────────────────────────────────────────────

/// 같은 `builtAt` 을 두 번 받지 않도록 기록한다.
///
/// 서버가 잘못된 번들을 올려 두면 앱이 켤 때마다 받고 버리기를 반복한다.
/// 그 낭비를 막는다. 사용자가 "업데이트 확인" 을 직접 누르면 비운다.
pub fn rejected(db: &Db) -> Vec<u64> {
    kv::get(db, REJECTED_KEY)
        .ok()
        .flatten()
        .and_then(|text| serde_json::from_str::<Vec<u64>>(&text).ok())
        .unwrap_or_default()
}

/// 거부 목록에 넣는다. 이미 있으면 그대로 둔다.
pub fn reject(db: &Db, built_at: u64) -> Result<(), UpdateError> {
    let mut list = rejected(db);
    if list.contains(&built_at) {
        return Ok(());
    }
    list.push(built_at);
    // 무한히 자라지 않게 최근 것만 남긴다.
    if list.len() > 16 {
        let cut = list.len() - 16;
        list.drain(..cut);
    }
    let text = serde_json::to_string(&list).map_err(|error| UpdateError::Archive(error.to_string()))?;
    kv::put(db, REJECTED_KEY, &text).map_err(|error| UpdateError::Archive(error.to_string()))?;
    Ok(())
}

/// 거부 목록을 비운다. 사용자가 직접 확인할 때 부른다.
pub fn clear_rejected(db: &Db) -> Result<(), UpdateError> {
    kv::remove(db, REJECTED_KEY).map_err(|error| UpdateError::Archive(error.to_string()))?;
    Ok(())
}

/// 디스크에 지금 있는 번들 목록. 진단용.
#[allow(dead_code)]
pub fn installed(root: &Path) -> Vec<u64> {
    let mut list: Vec<u64> = Vec::new();
    let Ok(entries) = fs::read_dir(root) else {
        return list;
    };
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        if name.bytes().all(|byte| byte.is_ascii_digit()) && entry.path().is_dir() {
            list.push(name.parse().unwrap_or(0));
        }
    }
    list.sort_unstable();
    list
}

/// 활성 번들의 경로. 없으면 `None`.
pub fn active_path(root: &Path) -> Option<PathBuf> {
    let text = fs::read_to_string(root.join(POINTER)).ok()?;
    let name = text.trim();
    if name.is_empty() || !name.bytes().all(|byte| byte.is_ascii_digit()) {
        return None;
    }
    let path = root.join(name);
    path.is_dir().then_some(path)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    /// 시험용 아카이브를 만든다. 우리 생성기(`web-archive.mjs`)와 같은 모양이다.
    fn build_archive(entries: &[(&str, &[u8])]) -> Vec<u8> {
        let mut builder = tar::Builder::new(Vec::new());
        for (name, data) in entries {
            let mut header = tar::Header::new_gnu();
            header.set_size(data.len() as u64);
            header.set_mode(0o644);
            header.set_cksum();
            builder
                .append_data(&mut header, *name, *data)
                .expect("항목 추가");
        }
        let tar_bytes = builder.into_inner().expect("tar 마감");
        gzip(tar_bytes)
    }

    fn gzip(tar_bytes: Vec<u8>) -> Vec<u8> {
        let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::best());
        encoder.write_all(&tar_bytes).expect("gzip");
        encoder.finish().expect("gzip 마감")
    }

    /// **악의적인 이름을 가진 tar 를 손으로 만든다.**
    ///
    /// `tar::Builder` 는 `..` 나 절대 경로 항목을 **만들지 못하게** 막는다
    /// (`paths in archives must not have ..`). 그건 옳은 기본값이지만, 우리가
    /// 검사하려는 것은 **남이 만든 그런 아카이브를 받았을 때** 우리 코드가
    /// 거부하느냐다. 그래서 헤더를 직접 쓴다 — 경로 안전 검사가 진짜로
    /// 동작하는지 확인할 유일한 방법이다.
    fn raw_tar(name: &str, data: &[u8], kind: u8) -> Vec<u8> {
        const BLOCK: usize = 512;
        let mut header = [0u8; BLOCK];
        let name_bytes = name.as_bytes();
        assert!(name_bytes.len() <= 100, "시험용 이름이 너무 깁니다");
        header[..name_bytes.len()].copy_from_slice(name_bytes);
        // mode · uid · gid · size · mtime — 전부 8진수 ASCII, 자릿수를 정확히
        // 맞춘다. tar 헤더의 각 필드는 고정 폭이라 한 바이트라도 어긋나면
        // 파서가 읽지 못한다.
        let octal = |value: u64, digits: usize| {
            let text = format!("{value:0width$o}", width = digits);
            let bytes = text.into_bytes();
            // 마지막 자리는 NUL 이다 (관례).
            let mut out = bytes[..digits - 1].to_vec();
            out.push(0);
            out
        };
        header[100..108].copy_from_slice(&octal(0o644, 8));
        header[108..116].copy_from_slice(&octal(0, 8));
        header[116..124].copy_from_slice(&octal(0, 8));
        header[124..136].copy_from_slice(&octal(data.len() as u64, 12));
        header[136..148].copy_from_slice(&octal(0, 12));
        header[148..156].copy_from_slice(b"        "); // 체크섬 자리
        header[156] = kind;
        header[257..263].copy_from_slice(b"ustar\0");
        header[263..265].copy_from_slice(b"00");

        let checksum: u32 = header.iter().map(|byte| u32::from(*byte)).sum();
        let text = format!("{checksum:06o}\0 ");
        header[148..156].copy_from_slice(text.as_bytes());

        let mut out = Vec::with_capacity(BLOCK * 3);
        out.extend_from_slice(&header);
        out.extend_from_slice(data);
        let padding = (BLOCK - (data.len() % BLOCK)) % BLOCK;
        out.extend(std::iter::repeat_n(0u8, padding));
        out.extend(std::iter::repeat_n(0u8, BLOCK * 2)); // 아카이브 끝
        out
    }

    /// 심볼릭 링크 항목이 든 아카이브. 링크 대상은 헤더의 `linkname` 자리에 쓴다.
    fn build_archive_with_symlink(name: &str, link: &str) -> Vec<u8> {
        let mut builder = tar::Builder::new(Vec::new());
        let mut header = tar::Header::new_gnu();
        header.set_entry_type(tar::EntryType::Symlink);
        header.set_size(0);
        header.set_mode(0o777);
        header.set_link_name(link).expect("링크 이름");
        header.set_cksum();
        builder
            .append_data(&mut header, name, std::io::empty())
            .expect("링크 추가");
        let tar_bytes = builder.into_inner().expect("tar 마감");
        gzip(tar_bytes)
    }

    fn bundle_body(built_at: u64) -> Vec<(&'static str, Vec<u8>)> {
        vec![
            ("index.html", b"<html><body>hi</body></html>".to_vec()),
            (
                "app/build.json",
                format!("{{\"builtAt\":{built_at}}}").into_bytes(),
            ),
        ]
    }

    fn archive_for(built_at: u64) -> Vec<u8> {
        let owned = bundle_body(built_at);
        let refs: Vec<(&str, &[u8])> = owned.iter().map(|(n, d)| (*n, d.as_slice())).collect();
        build_archive(&refs)
    }

    /// 매니페스트의 수치를 아카이브 실측에 맞춘다.
    fn manifest_for(built_at: u64, archive: &[u8]) -> WebManifest {
        let owned = bundle_body(built_at);
        let bytes: u64 = owned.iter().map(|(_, d)| d.len() as u64).sum();
        WebManifest {
            schema: SCHEMA,
            built_at,
            bundle: "/download/web.tar.gz".to_string(),
            sha256: sha256_hex(archive),
            bytes: archive.len() as u64,
            files: owned.len(),
            expanded_bytes: bytes,
            entry: "index.html".to_string(),
        }
    }

    fn scratch(name: &str) -> PathBuf {
        let directory = std::env::temp_dir().join(format!("mupid-update-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&directory);
        fs::create_dir_all(&directory).expect("스크래치");
        directory
    }

    /// 정상 경로: 검증 → 전개 → 활성화 → 포인터.
    #[test]
    fn activation_switches_the_pointer_last() {
        let root = scratch("happy");
        let archive = archive_for(1_700_000_000_000);
        let manifest = manifest_for(1_700_000_000_000, &archive);

        let applied = activate(&root, &archive, &manifest).expect("활성화");
        assert_eq!(applied.built_at, 1_700_000_000_000);
        assert_eq!(applied.files, 2);

        // 포인터가 새 값을 가리킨다.
        assert_eq!(
            fs::read_to_string(root.join(POINTER)).expect("포인터").trim(),
            "1700000000000"
        );
        // 내용이 실제로 있다.
        assert!(root.join("1700000000000").join("index.html").is_file());
        assert_eq!(
            read_bundle_build(&root.join("1700000000000")),
            Some(1_700_000_000_000)
        );
        // `.new` 찌꺼기가 없다.
        assert!(!root.join(format!("{}.new", 1_700_000_000_000u64)).exists());

        let _ = fs::remove_dir_all(&root);
    }

    /// 체크섬이 다르면 **포인터를 건드리지 않는다.**
    #[test]
    fn a_bad_checksum_leaves_the_old_bundle_alone() {
        let root = scratch("checksum");
        let good = archive_for(1_000);
        activate(&root, &good, &manifest_for(1_000, &good)).expect("첫 활성화");

        let other = archive_for(2_000);
        let mut manifest = manifest_for(2_000, &other);
        manifest.sha256 = "0".repeat(64); // 체크섬만 어긋나게

        let error = activate(&root, &other, &manifest).expect_err("거부해야 한다");
        assert!(matches!(error, UpdateError::Checksum), "{error:?}");

        // 옛 번들이 그대로 살아 있다 — 이게 이 설계의 안전 성질이다.
        assert_eq!(
            fs::read_to_string(root.join(POINTER)).expect("포인터").trim(),
            "1000"
        );
        assert!(root.join("1000").join("index.html").is_file());
        assert!(!root.join("2000").exists());

        let _ = fs::remove_dir_all(&root);
    }

    /// 크기가 다르면 거부한다.
    #[test]
    fn a_size_mismatch_is_refused() {
        let root = scratch("size");
        let archive = archive_for(1_000);
        let mut manifest = manifest_for(1_000, &archive);
        manifest.bytes += 1;
        let error = activate(&root, &archive, &manifest).expect_err("거부해야 한다");
        assert!(matches!(error, UpdateError::Size { .. }), "{error:?}");
        let _ = fs::remove_dir_all(&root);
    }

    /// `..` 로 나가는 항목이 있으면 전개하지 않는다.
    ///
    /// 아카이브를 **손으로** 만드는 이유: `tar::Builder` 는 이런 이름을 아예
    /// 만들지 못하게 막는다. 우리가 확인할 것은 남이 만든 것을 받았을 때
    /// 우리 코드가 거부하느냐다.
    #[test]
    fn traversal_members_are_refused() {
        let root = scratch("traversal");
        let archive = gzip(raw_tar("../escape.txt", b"nope", b'0'));
        let error = expand(&archive, &root.join("out")).expect_err("거부해야 한다");
        assert!(matches!(error, UpdateError::UnsafePath(_)), "{error:?}");
        // 밖으로 나간 파일이 만들어지지 않았다.
        assert!(!root.join("escape.txt").exists());
        assert!(!root.join("out").join("escape.txt").exists());
        let _ = fs::remove_dir_all(&root);
    }

    /// 깊이 파고드는 `..` 도 거부한다.
    #[test]
    fn deep_traversal_members_are_refused() {
        let root = scratch("deeptraversal");
        let archive = gzip(raw_tar("a/b/../../../escape.txt", b"nope", b'0'));
        let error = expand(&archive, &root.join("out")).expect_err("거부해야 한다");
        assert!(matches!(error, UpdateError::UnsafePath(_)), "{error:?}");
        assert!(!root.join("escape.txt").exists());
        let _ = fs::remove_dir_all(&root);
    }

    /// 절대 경로 항목도 거부한다.
    #[test]
    fn absolute_members_are_refused() {
        let root = scratch("absolute");
        let archive = gzip(raw_tar("/etc/evil", b"nope", b'0'));
        let error = expand(&archive, &root.join("out")).expect_err("거부해야 한다");
        assert!(matches!(error, UpdateError::UnsafePath(_)), "{error:?}");
        let _ = fs::remove_dir_all(&root);
    }

    /// 심볼릭 링크는 따라가지 않고 거부한다 — 번들 밖을 읽게 만들 수 있다.
    #[test]
    fn symlink_members_are_refused() {
        let root = scratch("symlink");
        let archive = build_archive_with_symlink("leak", "/etc/passwd");
        let error = expand(&archive, &root.join("out")).expect_err("거부해야 한다");
        assert!(matches!(error, UpdateError::NonRegular(_)), "{error:?}");
        let _ = fs::remove_dir_all(&root);
    }

    /// 같은 이름이 두 번 나오면 거부한다 (검사 우회 방지).
    #[test]
    fn duplicate_members_are_refused() {
        let root = scratch("duplicate");
        let archive = build_archive(&[("a.txt", b"one"), ("a.txt", b"two")]);
        let error = expand(&archive, &root.join("out")).expect_err("거부해야 한다");
        assert!(matches!(error, UpdateError::UnsafePath(_)), "{error:?}");
        let _ = fs::remove_dir_all(&root);
    }

    /// 파일 수가 매니페스트와 다르면 활성화하지 않는다.
    #[test]
    fn a_file_count_mismatch_is_refused() {
        let root = scratch("count");
        let archive = archive_for(1_000);
        let mut manifest = manifest_for(1_000, &archive);
        manifest.files += 3;
        let error = activate(&root, &archive, &manifest).expect_err("거부해야 한다");
        assert!(matches!(error, UpdateError::Mismatch(_)), "{error:?}");
        assert!(!root.join(POINTER).exists(), "포인터가 생기면 안 된다");
        let _ = fs::remove_dir_all(&root);
    }

    /// 번들이 밝힌 빌드 시각이 요청과 다르면 거부한다 — "남의 번들" 을 막는다.
    #[test]
    fn a_bundle_declaring_another_build_is_refused() {
        let root = scratch("otherbuild");
        let archive = archive_for(1_000); // 안에는 1000 이라고 적혀 있다
        let mut manifest = manifest_for(1_000, &archive);
        manifest.built_at = 2_000; // 다른 빌드를 요청한 것처럼
        let error = activate(&root, &archive, &manifest).expect_err("거부해야 한다");
        assert!(matches!(error, UpdateError::Mismatch(_)), "{error:?}");
        assert!(!root.join(2_000u64.to_string()).exists());
        let _ = fs::remove_dir_all(&root);
    }

    /// `app/build.json` 이 없으면 거부한다 (나이를 모르는 번들은 쓰지 않는다).
    #[test]
    fn a_bundle_without_build_json_is_refused() {
        let root = scratch("nobuildjson");
        let archive = build_archive(&[("index.html", b"<html>")]);
        let mut manifest = manifest_for(0, &archive);
        manifest.files = 1;
        manifest.expanded_bytes = b"<html>".len() as u64;
        let error = activate(&root, &archive, &manifest).expect_err("거부해야 한다");
        assert!(matches!(error, UpdateError::Mismatch(_)), "{error:?}");
        let _ = fs::remove_dir_all(&root);
    }

    /// 진입점이 index.html 이 아니면 거부한다.
    #[test]
    fn a_bad_entry_point_is_refused() {
        let root = scratch("entry");
        let archive = archive_for(1_000);
        let mut manifest = manifest_for(1_000, &archive);
        manifest.entry = "main.html".to_string();
        let error = activate(&root, &archive, &manifest).expect_err("거부해야 한다");
        assert!(matches!(error, UpdateError::Mismatch(_)), "{error:?}");
        let _ = fs::remove_dir_all(&root);
    }

    /// 모르는 스키마는 손대지 않는다.
    #[test]
    fn an_unknown_schema_is_refused() {
        let root = scratch("schema");
        let archive = archive_for(1_000);
        let mut manifest = manifest_for(1_000, &archive);
        manifest.schema = 99;
        let error = activate(&root, &archive, &manifest).expect_err("거부해야 한다");
        assert!(matches!(error, UpdateError::Schema(99)), "{error:?}");
        let _ = fs::remove_dir_all(&root);
    }

    /// 정리는 활성 번들과 직전 하나를 남긴다.
    #[test]
    fn prune_keeps_the_active_bundle_and_one_before_it() {
        let root = scratch("prune");
        for value in [1_000u64, 2_000, 3_000] {
            let archive = archive_for(value);
            activate(&root, &archive, &manifest_for(value, &archive)).expect("활성화");
        }
        // 3_000 이 활성, 2_000 이 직전. 1_000 은 지워진다.
        assert_eq!(installed(&root), vec![2_000, 3_000]);
        assert_eq!(
            fs::read_to_string(root.join(POINTER)).expect("포인터").trim(),
            "3000"
        );
        let _ = fs::remove_dir_all(&root);
    }

    /// `.new` 찌꺼기를 정리한다.
    #[test]
    fn prune_removes_staging_leftovers() {
        let root = scratch("staging");
        fs::create_dir_all(root.join("999.new")).expect("찌꺼기");
        let removed = prune(&root, 1_000).expect("정리");
        assert!(removed.is_empty());
        assert!(!root.join("999.new").exists());
        let _ = fs::remove_dir_all(&root);
    }

    /// 활성 포인터를 읽는다. 이상한 값은 `None`.
    #[test]
    fn active_path_is_none_for_a_bad_pointer() {
        let root = scratch("pointer");
        assert_eq!(active_path(&root), None, "포인터가 없으면 None");
        fs::write(root.join(POINTER), "../../etc").expect("쓰기");
        assert_eq!(active_path(&root), None, "경로가 섞이면 None");
        fs::write(root.join(POINTER), "12345").expect("쓰기");
        assert_eq!(active_path(&root), None, "디렉터리가 없으면 None");
        fs::create_dir_all(root.join("12345")).expect("디렉터리");
        assert_eq!(active_path(&root), Some(root.join("12345")));
        let _ = fs::remove_dir_all(&root);
    }

    /// sha256 이 알려진 값과 맞는지 (규칙이 어긋나면 검증이 통째로 무의미해진다).
    #[test]
    fn sha256_matches_the_known_vector() {
        assert_eq!(
            sha256_hex(b"abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
        assert_eq!(sha256_hex(b"").len(), 64);
    }

    /// 상한을 넘는 아카이브는 받지 않는다.
    #[test]
    fn an_oversized_archive_is_refused() {
        let archive = archive_for(1_000);
        let mut manifest = manifest_for(1_000, &archive);
        manifest.bytes = MAX_ARCHIVE_BYTES + 1;
        // 크기 대조에서 먼저 걸린다 — 상한 검사가 그 뒤에 있어도 결과는 거부다.
        let error = verify_archive(&archive, &manifest).expect_err("거부해야 한다");
        assert!(matches!(error, UpdateError::Size { .. }), "{error:?}");
    }

    /// **실제 산출물로 왕복한다.** `frontend/scripts/web-archive.mjs` 가 만든
    /// 아카이브와 `web.json` 을 우리 `activate()` 가 그대로 받는지 본다.
    ///
    /// 단위 시험은 우리가 만든 시험용 아카이브를 쓴다. 그래서 **생성기와 앱이
    /// 어긋나도** 두 쪽 시험이 각각 통과한다 — 실제로 그런 어긋남을 잡으려면
    /// 진짜 산출물을 통과시켜야 한다. 이 시험의 목적이 그것이다
    /// (실제로 `expandedBytes` 를 tar 길이로 계산하던 어긋남을 이 방식으로 잡았다).
    ///
    /// 산출물이 없으면 **건너뛴다.** `cargo test` 가 프론트 빌드를 요구하면
    /// 안 되기 때문이다. CI 에서는 `build:static` 뒤에 돌므로 실행된다.
    #[test]
    fn the_real_generated_archive_activates() {
        // `app/` 에서 두 단계 위가 저장소 루트다.
        let repository = Path::new(env!("CARGO_MANIFEST_DIR"))
            .parent()
            .expect("저장소 루트");
        let update_dir = repository.join("frontend").join("dist").join("update");
        let manifest_path = update_dir.join("web.json");
        let Ok(text) = fs::read_to_string(&manifest_path) else {
            println!(
                "[건너뜀] 생성된 매니페스트가 없습니다: {} — `npm run build:archive` 를 먼저 돌리세요",
                manifest_path.display()
            );
            return;
        };

        let manifest: WebManifest = serde_json::from_str(&text).expect("web.json 파싱");
        // 생성기가 내는 필드 이름이 우리가 읽는 이름과 같은지 (camelCase).
        assert_eq!(manifest.schema, SCHEMA, "스키마가 어긋났다");
        assert_eq!(manifest.entry, "index.html");
        assert!(manifest.built_at > 0, "builtAt 이 0 이다");
        assert_eq!(manifest.sha256.len(), 64, "sha256 이 16진수 64자가 아니다");

        // 아카이브 파일 이름은 매니페스트의 `bundle` 에서 가져온다.
        let file_name = manifest
            .bundle
            .rsplit('/')
            .next()
            .expect("bundle 경로");
        let archive = fs::read(update_dir.join(file_name)).expect("아카이브 읽기");

        let root = scratch("real");
        let applied = activate(&root, &archive, &manifest)
            .unwrap_or_else(|error| panic!("실제 산출물을 활성화하지 못했습니다: {error}"));

        assert_eq!(applied.built_at, manifest.built_at);
        assert_eq!(applied.files, manifest.files);
        // 진입점이 실제로 펼쳐졌다 — 화면이 뜨는 최소 조건이다.
        assert!(
            root.join(manifest.built_at.to_string()).join("index.html").is_file(),
            "index.html 이 펼쳐지지 않았다"
        );
        // 번들이 자기 나이를 밝힌다. 이게 없으면 앱은 다음 기동에 거부한다.
        assert_eq!(
            read_bundle_build(&root.join(manifest.built_at.to_string())),
            Some(manifest.built_at),
            "번들의 build.json 이 매니페스트와 어긋난다"
        );

        let _ = fs::remove_dir_all(&root);
    }
}
