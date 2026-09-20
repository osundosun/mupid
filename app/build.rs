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
    // 예외 보고의 키·엔드포인트도 `option_env!` 로 읽힌다(`src/crash.rs`).
    // 런타임 환경변수를 먼저 보므로 보통은 이 줄이 필요 없지만, **빌드 시점에
    // 주입하는 경우**(CI 가 `MUPID_APP_ENV` 에서 꺼내 넣는 방식, `infra/ci/build-apk.sh`)
    // 값이 바뀌었을 때 다시 컴파일되게 하려면 여기 있어야 한다. 없으면 예전
    // 값이 박힌 결과물이 그대로 나오고, 증상은 "왜 예외가 안 올라오지" 뿐이다.
    println!("cargo:rerun-if-env-changed=MUPID_CRASHLYTICS_ENDPOINT");
    println!("cargo:rerun-if-env-changed=MUPID_CRASHLYTICS_API_KEY");
    tauri_build::build()
}
