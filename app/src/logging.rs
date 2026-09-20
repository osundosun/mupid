//! 진단 로그를 `adb logcat` 으로 내보내는 최소 로거.
//!
//! ## 왜 필요한가 — 이게 없으면 진단이 전부 사라진다
//!
//! `log` 크레이트는 **파사드**다. 매크로(`log::info!`)는 등록된 로거에게
//! 넘기는데, 로거가 없으면 **조용히 버린다**(오류도 경고도 없다). 이 앱에는
//! `set_logger` 를 부르는 곳이 없어서 **20곳의 진단이 전부 죽어 있었다**:
//!
//! ```text
//! http.rs       쿠키를 읽지/쓰지 못했습니다            6곳
//! media.rs      알림·앨범 아트 실패                    6곳
//! update_net.rs 웹 업데이트 건너뜀 · APK 관련          4곳
//! lib.rs        이미지·RSC 페이로드 실패               3곳
//! commands.rs   웹 업데이트 건너뜀                     1곳
//! ```
//!
//! 실제로 이 때문에 한 번 헤맸다 — 업데이트 활성화 계측을 `log::info!` 로
//! 넣었는데 기기에 아무것도 안 찍혔다. 반면 `assets.rs` 의 진단은 보였는데,
//! 그건 `log` 가 아니라 `println!` 을 쓰고 있었기 때문이다.
//!
//! ## 왜 `tauri-plugin-log` 를 안 쓰나
//!
//! 그 플러그인은 파일 로테이션·웹뷰 전달·원격 전송까지 딸려 온다. 필요한 것은
//! **안드로이드 로그캣에 한 줄 나오게 하는 것**뿐이고, 그건 stdout 에 쓰면 된다
//! (안드로이드에서 앱의 stdout 은 logcat 으로 간다). 의존성도 늘리지 않는다.
//!
//! ## 형식
//!
//! `[mupid-level] 메시지` 로 고정한다. `adb logcat` 에서 `grep mupid-` 로
//! 한 번에 걸러지게 하려는 것이다. 레벨을 붙이는 이유는 `log::warn!` 이
//! logcat 에서도 경고로 보이게 하기 위해서다(기본 `W` 태그로 나간다).
//!
//! 줄바꿈은 **한 줄로 눌러 담는다.** 메시지에 개행이 있으면 logcat 에서 여러
//! 줄로 쪼개져 grep·추적이 어려워진다.

use log::{Level, LevelFilter, Log, Metadata, Record};

/// 이 앱의 로그임을 알아볼 표식. `adb logcat | grep mupid-` 로 걸러낸다.
const PREFIX: &str = "[mupid-";

/// `println!` 로 내보내는 로거. 상태를 갖지 않는다.
struct StdoutLogger;

impl Log for StdoutLogger {
    /// 이 로거는 모든 레벨을 받는다 — 거르는 일은 `max_level` 이 한다.
    fn enabled(&self, _metadata: &Metadata) -> bool {
        true
    }

    fn log(&self, record: &Record) {
        if !self.enabled(record.metadata()) {
            return;
        }
        // 개행을 눌러 담는다. 메시지 한 건이 logcat 에서 한 줄이어야 한다.
        let message = record.args().to_string().replace('\n', " ⏎ ");
        println!("{PREFIX}{}] {message}", record.level());
    }

    fn flush(&self) {}
}

/// 로거를 등록한다. **`run()` 의 가장 앞에서 부른다.**
///
/// 한 번만 등록된다 — 두 번째 호출은 조용히 무시된다(테스트가 여러 번
/// `run()` 을 부르거나 플러그인 초기화가 겹쳐도 안전하다).
pub fn init() {
    // `Info` 까지 남긴다. `Debug` 는 프레임워크 내부 로그가 너무 많이 섞여
    // 정작 필요한 줄을 찾기 어려워진다.
    let logger: &'static StdoutLogger = Box::leak(Box::new(StdoutLogger));
    // 이미 등록돼 있으면 `Err` 가 나는데, 그건 오류가 아니다.
    if log::set_logger(logger).is_ok() {
        log::set_max_level(LevelFilter::Info);
    }
}

/// 로그 레벨 이름. `logcat` 의 태그와 눈으로 맞추기 쉽게 짧게 쓴다.
#[allow(dead_code)]
fn level_name(level: Level) -> &'static str {
    match level {
        Level::Error => "ERROR",
        Level::Warn => "WARN",
        Level::Info => "INFO",
        Level::Debug => "DEBUG",
        Level::Trace => "TRACE",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_prefix_is_what_logcat_filters_on() {
        // `adb logcat | grep mupid-` 로 걸러내는 계약이다. 바꾸면 문서의
        // 명령이 조용히 안 먹는다.
        assert_eq!(PREFIX, "[mupid-");
    }

    #[test]
    fn level_names_are_short_and_uppercase() {
        assert_eq!(level_name(Level::Info), "INFO");
        assert_eq!(level_name(Level::Warn), "WARN");
    }

    #[test]
    fn init_is_idempotent() {
        // `run()` 이 여러 번 불려도(테스트·재초기화) 죽지 않아야 한다.
        init();
        init();
        init();
    }

    #[test]
    fn the_configured_level_lets_info_through() {
        // 계측이 `log::info!` 다. 최대 레벨이 그보다 낮으면 조용히 사라진다 —
        // 그게 이 로거를 만든 이유였으므로 여기서 고정한다.
        init();
        assert!(
            log::max_level() >= log::LevelFilter::Info,
            "Info 가 걸러지면 계측이 안 나온다"
        );
        assert!(log::log_enabled!(log::Level::Info), "Info 가 비활성이다");
    }

    #[test]
    fn a_logged_line_actually_reaches_stdout() {
        // **이게 이 모듈의 존재 이유를 증명한다.** 로거가 없으면 `log::info!`
        // 는 조용히 버려지는데, 그건 시험으로만 잡을 수 있다(사람 눈에는
        // "로그가 안 보인다" 로만 나타나고 오류가 없다).
        //
        // `--nocapture` 로 돌려야 이 줄이 보인다. 평소 실행에서는 안 보이지만
        // **버려졌는지 아닌지는 여기서 판정하지 않는다** — 그건 `log_enabled!`
        // 와 `max_level` 이 본다. 여기서는 매크로를 실제로 지나가게 해
        // 패닉하지 않는 것과 개행이 눌리는 것을 확인한다.
        init();
        log::info!(
            "로거 확인용 한 줄\n둘째 줄은 눌려야 한다: {}",
            "값"
        );
        log::warn!("경고도 나가야 한다");
    }
}
