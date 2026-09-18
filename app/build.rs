fn main() {
    // `option_env!` 는 컴파일할 때 값을 박는다. 이 줄이 없으면 cargo 는 환경
    // 변수가 바뀐 것을 모르고 예전에 박아 둔 주소가 든 결과물을 그대로 쓴다 —
    // 로컬 서버로 바꿨는데 계속 운영 서버로 나가는 일이 여기서 생긴다.
    println!("cargo:rerun-if-env-changed=MUPID_API_ORIGIN");
    // 같은 이유다. 이 값은 `update.rs` 가 `option_env!` 로 읽어 **앱이 자기
    // 번들의 나이와 자기 APK 의 버전을 아는 근거**가 된다. 이 줄이 없으면
    // 버전을 올려도 옛 값이 박힌 결과물이 나오고, 증상은 "업데이트가 안
    // 된다" 로만 보인다.
    println!("cargo:rerun-if-env-changed=MUPID_NATIVE_BUILD");
    println!("cargo:rerun-if-env-changed=MUPID_WEB_BUILD");
    tauri_build::build()
}
