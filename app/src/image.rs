//! 앨범 아트 주소를 푸는 규칙.
//!
//! 웹뷰는 인터넷에 직접 나가지 않는다. 그래서 화면은 원격 이미지 주소를
//! `mupidimg` 스킴으로 감싸 들고 다니고(`frontend/lib/platform/image.ts`),
//! 실제로 받아 오는 일은 Rust 가 한다.
//!
//! 그 스킴을 푸는 자리가 두 군데다 — 웹뷰에 그림을 돌려주는 `serve_image` 와,
//! 알림에 넣을 표지를 받는 `media.rs`. 규칙이 갈라지면 한쪽만 조용히 깨지므로
//! 여기 한 벌만 둔다. 실제로 그렇게 깨졌었다: 알림 쪽이 `mupidimg.localhost`
//! 를 진짜 호스트로 알고 받으러 나갔다가 매번 실패했고, 표지 자리가 비었다.

use base64::Engine;

use crate::config::{api_origin, IMAGE_SCHEME};

const IMAGE_BASE64: base64::engine::general_purpose::GeneralPurpose =
    base64::engine::general_purpose::URL_SAFE_NO_PAD;

/// `mupidimg` 주소의 경로에 실린 원래 주소를 꺼낸다.
pub fn decode(encoded: &str) -> Option<String> {
    let encoded = encoded
        .trim_start_matches('/')
        .split('?')
        .next()
        .unwrap_or_default();
    let bytes = IMAGE_BASE64.decode(encoded.as_bytes()).ok()?;
    String::from_utf8(bytes).ok()
}

/// 서버가 준 이미지 주소를 받아 올 수 있는 주소로 만든다.
///
/// 서버는 `/api/v1/storage/image/...` 처럼 **경로로** 준다. 절대 주소로 바뀌어도
/// 받을 수 있게 둘 다 받는다 — `http_send` 와 같은 규칙이라 API 주소는 여전히
/// 한 곳에만 있다.
pub fn server_url(target: &str) -> Option<String> {
    if target.starts_with("http://") || target.starts_with("https://") {
        Some(target.to_string())
    } else if target.starts_with('/') {
        Some(format!("{}{}", api_origin().trim_end_matches('/'), target))
    } else {
        None
    }
}

/// 화면이 들고 다니는 주소를 **받아 올 수 있는 주소**로 바꾼다.
///
/// 화면의 `track.image` 는 이미 `toImageSource` 를 지난 값이라 앱에서는
/// `http://mupidimg.localhost/<base64url>` 모양이다. 그것을 풀어 원래 주소로
/// 되돌린다.
///
/// 감싸지 않은 경로(`/sky-background.webp`)는 **앱 안의 파일**이라는 뜻이다.
/// 화면이 감싸지 않았다는 것 자체가 "서버에서 받아 올 것이 아니다"라는 표시이니
/// 여기서도 받으러 나가지 않는다.
pub fn remote_url(address: &str) -> Option<String> {
    if let Some(encoded) = encoded_part(address) {
        return server_url(&decode(encoded)?);
    }
    if address.starts_with("http://") || address.starts_with("https://") {
        return Some(address.to_string());
    }
    None
}

/// `mupidimg` 주소면 경로 부분을, 아니면 `None`.
///
/// 스킴의 실제 모양은 플랫폼마다 다르다 — 안드로이드·윈도우는
/// `http://mupidimg.localhost/…`, macOS·리눅스는 `mupidimg://localhost/…` 다.
/// 둘 다 받는다.
fn encoded_part(address: &str) -> Option<&str> {
    let (scheme, rest) = address.split_once("://")?;
    let (host, path) = rest.split_once('/').unwrap_or((rest, ""));
    let wrapped =
        host == format!("{IMAGE_SCHEME}.localhost") || (scheme == IMAGE_SCHEME && host == "localhost");
    wrapped.then_some(path)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 화면이 만드는 것과 같은 모양으로 감싼다.
    fn wrap(url: &str) -> String {
        format!(
            "http://{IMAGE_SCHEME}.localhost/{}",
            IMAGE_BASE64.encode(url.as_bytes())
        )
    }

    #[test]
    fn wrapped_addresses_come_back_as_the_original() {
        let path = "/api/v1/storage/image/ALBUM/7";
        assert_eq!(
            remote_url(&wrap(path)).as_deref(),
            Some(format!("{}{path}", api_origin().trim_end_matches('/')).as_str()),
        );
        // macOS·리눅스 모양도 같은 값을 준다.
        let mac = format!(
            "{IMAGE_SCHEME}://localhost/{}",
            IMAGE_BASE64.encode(path.as_bytes())
        );
        assert_eq!(remote_url(&mac), remote_url(&wrap(path)));
    }

    #[test]
    fn absolute_addresses_pass_through_either_way() {
        let url = "https://cdn.example.com/cover.jpg";
        assert_eq!(remote_url(&wrap(url)).as_deref(), Some(url));
        assert_eq!(remote_url(url).as_deref(), Some(url));
    }

    /// 앱 안의 파일은 받으러 나가지 않는다. 나갔다가 404 만 받아 온다.
    #[test]
    fn app_owned_files_are_left_alone() {
        assert_eq!(remote_url("/sky-background.webp"), None);
        assert_eq!(remote_url("data:image/png;base64,iVBORw0KGgo="), None);
        assert_eq!(remote_url(""), None);
    }

    #[test]
    fn a_broken_payload_is_not_fetched() {
        let broken = format!("http://{IMAGE_SCHEME}.localhost/not-base64!!");
        assert_eq!(remote_url(&broken), None);
    }

    /// 쿼리가 붙어 와도 경로만 본다. 웹뷰가 캐시 무효화로 붙이는 일이 있다.
    #[test]
    fn a_query_string_does_not_confuse_the_decoder() {
        let path = "/api/v1/storage/image/ALBUM/7";
        let with_query = format!("{}?v=3", wrap(path));
        assert_eq!(remote_url(&with_query), remote_url(&wrap(path)));
    }
}
