fn main() {
    // `option_env!` 는 컴파일할 때 값을 박는다. 이 줄이 없으면 cargo 는 환경
    // 변수가 바뀐 것을 모르고 예전에 박아 둔 주소가 든 결과물을 그대로 쓴다 —
    // 로컬 서버로 바꿨는데 계속 운영 서버로 나가는 일이 여기서 생긴다.
    println!("cargo:rerun-if-env-changed=MUPID_API_ORIGIN");
    tauri_build::build()
}
