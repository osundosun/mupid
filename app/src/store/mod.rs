pub mod kv;
pub mod prefs;

#[cfg(test)]
mod tests {
    use super::{kv, prefs};
    use crate::db::Db;

    /// 시험마다 새 파일을 쓴다. 메모리 DB 를 쓰지 않는 이유는, 실제로 확인하고
    /// 싶은 것 중에 WAL 과 마이그레이션처럼 파일이 있어야 도는 것이 있어서다.
    struct TempDb {
        path: std::path::PathBuf,
        db: Db,
    }

    impl TempDb {
        fn new(name: &str) -> Self {
            let path = std::env::temp_dir().join(format!("mupid-test-{name}-{}.db", std::process::id()));
            for suffix in ["", "-wal", "-shm"] {
                let _ = std::fs::remove_file(format!("{}{suffix}", path.display()));
            }
            let db = Db::open(&path).expect("저장소를 열 수 있어야 한다");
            Self { path, db }
        }
    }

    impl Drop for TempDb {
        fn drop(&mut self) {
            for suffix in ["", "-wal", "-shm"] {
                let _ = std::fs::remove_file(format!("{}{suffix}", self.path.display()));
            }
        }
    }

    fn sample() -> prefs::StoredPreferences {
        prefs::StoredPreferences {
            onboarding_submitted_at: Some("2026-09-14T10:00:00Z".to_string()),
            nickname: "규원".to_string(),
            moods: vec!["차분한".to_string(), "몽환적인".to_string()],
            genres: vec!["발라드".to_string()],
            liked: vec![1003, 1001],
            ignored: vec![1007],
            history: vec![1001, 1003, 1005],
            last_played_id: Some(1005),
            moments: vec![prefs::StoredMoment {
                id: 1,
                track_id: 1003,
                start: 12.5,
                end: 40.0,
                section: "후렴".to_string(),
            }],
            playlists: vec![prefs::StoredPlaylist {
                id: 9,
                name: "새벽에".to_string(),
                track_ids: vec![1005, 1001],
            }],
        }
    }

    #[test]
    fn preferences_survive_a_round_trip_in_order() {
        let temp = TempDb::new("prefs");
        let value = sample();
        prefs::save(&temp.db, "42", &value).expect("저장");
        let loaded = prefs::load(&temp.db, "42").expect("읽기");

        // 순서까지 같아야 한다. 좋아요 목록과 재생 기록은 화면이 그대로 그린다.
        assert_eq!(loaded.liked, value.liked);
        assert_eq!(loaded.history, value.history);
        assert_eq!(loaded.ignored, value.ignored);
        assert_eq!(loaded.moods, value.moods);
        assert_eq!(loaded.nickname, value.nickname);
        assert_eq!(loaded.last_played_id, value.last_played_id);
        assert_eq!(loaded.onboarding_submitted_at, value.onboarding_submitted_at);
        assert_eq!(loaded.moments.len(), 1);
        assert_eq!(loaded.moments[0].track_id, 1003);
        assert_eq!(loaded.moments[0].section, "후렴");
        assert_eq!(loaded.playlists.len(), 1);
        assert_eq!(loaded.playlists[0].name, "새벽에");
        assert_eq!(loaded.playlists[0].track_ids, vec![1005, 1001]);
    }

    /// 두 번째 저장이 첫 번째를 지운다. 스냅샷 계약이라 남아 있으면 안 된다.
    #[test]
    fn saving_again_replaces_the_previous_snapshot() {
        let temp = TempDb::new("replace");
        prefs::save(&temp.db, "42", &sample()).expect("저장");

        let mut second = sample();
        second.liked = vec![1009];
        second.moments.clear();
        second.playlists.clear();
        prefs::save(&temp.db, "42", &second).expect("다시 저장");

        let loaded = prefs::load(&temp.db, "42").expect("읽기");
        assert_eq!(loaded.liked, vec![1009]);
        assert!(loaded.moments.is_empty());
        assert!(loaded.playlists.is_empty());
    }

    /// 계정이 다르면 서로의 저장 내용이 보이지 않는다. 한 기기를 두 사람이 쓸
    /// 때 뒷사람이 앞사람의 컬렉션을 보면 안 된다.
    #[test]
    fn accounts_do_not_see_each_other() {
        let temp = TempDb::new("accounts");
        prefs::save(&temp.db, "42", &sample()).expect("저장");

        let other = prefs::load(&temp.db, "7").expect("읽기");
        assert!(other.liked.is_empty());
        assert!(other.nickname.is_empty());
        assert!(other.playlists.is_empty());
    }

    #[test]
    fn photos_are_stored_per_account_and_removable() {
        let temp = TempDb::new("photo");
        assert_eq!(prefs::read_photo(&temp.db, "42").expect("읽기"), None);

        prefs::save_photo(&temp.db, "42", "data:image/jpeg;base64,AAAA").expect("저장");
        assert_eq!(
            prefs::read_photo(&temp.db, "42").expect("읽기").as_deref(),
            Some("data:image/jpeg;base64,AAAA"),
        );
        assert_eq!(prefs::read_photo(&temp.db, "7").expect("읽기"), None);

        prefs::remove_photo(&temp.db, "42").expect("지우기");
        assert_eq!(prefs::read_photo(&temp.db, "42").expect("읽기"), None);
    }

    #[test]
    fn the_account_key_defaults_to_local_and_can_be_switched() {
        let temp = TempDb::new("session");
        assert_eq!(
            kv::current_user_key(&temp.db).expect("읽기"),
            kv::LOCAL_USER_KEY,
        );

        kv::set_current_user_key(&temp.db, "42").expect("전환");
        assert_eq!(kv::current_user_key(&temp.db).expect("읽기"), "42");

        kv::put(&temp.db, kv::COOKIE_JAR_KEY, "[]").expect("저장");
        assert_eq!(
            kv::get(&temp.db, kv::COOKIE_JAR_KEY).expect("읽기").as_deref(),
            Some("[]"),
        );
        kv::remove(&temp.db, kv::COOKIE_JAR_KEY).expect("지우기");
        assert_eq!(kv::get(&temp.db, kv::COOKIE_JAR_KEY).expect("읽기"), None);
    }

    /// 같은 파일을 다시 열어도 마이그레이션이 두 번 돌지 않고 값도 남아 있다.
    #[test]
    fn reopening_keeps_what_was_saved() {
        let temp = TempDb::new("reopen");
        prefs::save(&temp.db, "42", &sample()).expect("저장");

        let again = Db::open(&temp.path).expect("다시 열기");
        let loaded = prefs::load(&again, "42").expect("읽기");
        assert_eq!(loaded.liked, vec![1003, 1001]);
    }
}
