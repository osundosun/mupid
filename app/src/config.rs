//! 빌드 시점에 정해지는 값들.

/// API 원본 주소.
///
/// 웹에서는 Vite 프록시가 `/api` 를 같은 출처로 보이게 해 주지만, 앱에는
/// 출처라는 것이 없다. 그래서 경로만 받은 요청(`/api/v1/...`)은 Rust 가 이
/// 주소를 붙여 완성한다. 프론트엔드 코드는 주소를 모른다.
///
/// 빌드할 때 `MUPID_API_ORIGIN` 으로 덮어쓸 수 있다.
pub fn api_origin() -> &'static str {
    option_env!("MUPID_API_ORIGIN").unwrap_or("https://j15a204.p.ssafy.io")
}

/// 앨범 아트 같은 원격 이미지를 웹뷰 대신 Rust 가 받아 오는 커스텀 스킴.
pub const IMAGE_SCHEME: &str = "mupidimg";
