//! 취향·컬렉션 저장.
//!
//! 프론트엔드의 `Preferences` 는 통짜 스냅샷 하나다(`lib/services/contracts.ts`).
//! 저장은 그 계약을 그대로 받지만, 안에서는 정규화한 표에 넣는다. 나중에
//! "구간 하나만 추가" 같은 부분 저장이 필요해질 때 스키마를 다시 갈아엎지
//! 않으려는 것이다.
//!
//! 곡은 번호만 남긴다. 곡 정보는 서버 카탈로그가 갖고 있고, 여기에 복사해 두면
//! 제목이 바뀐 곡이 앱에서만 옛 이름으로 남는다.

use rusqlite::{params, OptionalExtension};
use serde::{Deserialize, Serialize};

use crate::db::{now_ms, Db, DbResult};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StoredMoment {
    pub id: i64,
    pub track_id: i64,
    pub start: f64,
    pub end: f64,
    #[serde(default)]
    pub section: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StoredPlaylist {
    pub id: i64,
    pub name: String,
    #[serde(default)]
    pub track_ids: Vec<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StoredPreferences {
    #[serde(default)]
    pub onboarding_submitted_at: Option<String>,
    #[serde(default)]
    pub nickname: String,
    #[serde(default)]
    pub moods: Vec<String>,
    #[serde(default)]
    pub genres: Vec<String>,
    #[serde(default)]
    pub liked: Vec<i64>,
    #[serde(default)]
    pub ignored: Vec<i64>,
    #[serde(default)]
    pub history: Vec<i64>,
    #[serde(default)]
    pub last_played_id: Option<i64>,
    #[serde(default)]
    pub moments: Vec<StoredMoment>,
    #[serde(default)]
    pub playlists: Vec<StoredPlaylist>,
}

impl StoredPreferences {
    /// 저장된 것이 없을 때. 닉네임은 비워 둔다 — 로그인한 사용자 이름은
    /// 서버가 알려 주고, 화면이 기본값을 갖고 있다.
    pub fn empty() -> Self {
        Self {
            onboarding_submitted_at: None,
            nickname: String::new(),
            moods: Vec::new(),
            genres: Vec::new(),
            liked: Vec::new(),
            ignored: Vec::new(),
            history: Vec::new(),
            last_played_id: None,
            moments: Vec::new(),
            playlists: Vec::new(),
        }
    }
}

const FLAG_KINDS: [&str; 3] = ["liked", "ignored", "history"];

pub fn load(db: &Db, user_key: &str) -> DbResult<StoredPreferences> {
    let connection = db.lock();
    let mut value = StoredPreferences::empty();

    let row = connection
        .query_row(
            "SELECT onboarding_submitted_at, nickname, moods, genres, last_played_id
             FROM preferences WHERE user_key = ?1",
            params![user_key],
            |row| {
                Ok((
                    row.get::<_, Option<String>>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                    row.get::<_, String>(3)?,
                    row.get::<_, Option<i64>>(4)?,
                ))
            },
        )
        .optional()?;

    let Some((onboarding, nickname, moods, genres, last_played)) = row else {
        // 이 계정으로 저장한 적이 없다. 빈 값을 돌려준다.
        return Ok(value);
    };
    value.onboarding_submitted_at = onboarding;
    value.nickname = nickname;
    value.moods = serde_json::from_str(&moods).unwrap_or_default();
    value.genres = serde_json::from_str(&genres).unwrap_or_default();
    value.last_played_id = last_played;

    for kind in FLAG_KINDS {
        let mut statement = connection.prepare(
            "SELECT track_id FROM track_flags
             WHERE user_key = ?1 AND kind = ?2 ORDER BY position",
        )?;
        let ids = statement
            .query_map(params![user_key, kind], |row| row.get::<_, i64>(0))?
            .collect::<Result<Vec<_>, _>>()?;
        match kind {
            "liked" => value.liked = ids,
            "ignored" => value.ignored = ids,
            _ => value.history = ids,
        }
    }

    let mut statement = connection.prepare(
        "SELECT id, track_id, start_sec, end_sec, section FROM moments
         WHERE user_key = ?1 ORDER BY id",
    )?;
    value.moments = statement
        .query_map(params![user_key], |row| {
            Ok(StoredMoment {
                id: row.get(0)?,
                track_id: row.get(1)?,
                start: row.get(2)?,
                end: row.get(3)?,
                section: row.get(4)?,
            })
        })?
        .collect::<Result<Vec<_>, _>>()?;

    let mut statement = connection
        .prepare("SELECT id, name FROM playlists WHERE user_key = ?1 ORDER BY position")?;
    let heads = statement
        .query_map(params![user_key], |row| {
            Ok((row.get::<_, i64>(0)?, row.get::<_, String>(1)?))
        })?
        .collect::<Result<Vec<_>, _>>()?;

    let mut statement = connection.prepare(
        "SELECT track_id FROM playlist_tracks
         WHERE user_key = ?1 AND playlist_id = ?2 ORDER BY position",
    )?;
    for (id, name) in heads {
        let track_ids = statement
            .query_map(params![user_key, id], |row| row.get::<_, i64>(0))?
            .collect::<Result<Vec<_>, _>>()?;
        value.playlists.push(StoredPlaylist { id, name, track_ids });
    }

    Ok(value)
}

/// 스냅샷을 통째로 저장한다. 한 트랜잭션 안에서 지우고 다시 쓴다 — 중간에
/// 끊기면 예전 상태가 그대로 남아야지, 절반만 지워진 컬렉션이 남으면 안 된다.
pub fn save(db: &Db, user_key: &str, value: &StoredPreferences) -> DbResult<()> {
    let mut connection = db.lock();
    let tx = connection.transaction()?;

    tx.execute(
        "INSERT INTO preferences
           (user_key, onboarding_submitted_at, nickname, moods, genres, last_played_id, updated_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
         ON CONFLICT (user_key) DO UPDATE SET
           onboarding_submitted_at = excluded.onboarding_submitted_at,
           nickname                = excluded.nickname,
           moods                   = excluded.moods,
           genres                  = excluded.genres,
           last_played_id          = excluded.last_played_id,
           updated_at              = excluded.updated_at",
        params![
            user_key,
            value.onboarding_submitted_at,
            value.nickname,
            serde_json::to_string(&value.moods)?,
            serde_json::to_string(&value.genres)?,
            value.last_played_id,
            now_ms(),
        ],
    )?;

    tx.execute("DELETE FROM track_flags WHERE user_key = ?1", params![user_key])?;
    {
        let mut statement = tx.prepare(
            "INSERT INTO track_flags (user_key, kind, track_id, position)
             VALUES (?1, ?2, ?3, ?4)
             ON CONFLICT (user_key, kind, track_id) DO NOTHING",
        )?;
        for (kind, ids) in [
            ("liked", &value.liked),
            ("ignored", &value.ignored),
            ("history", &value.history),
        ] {
            for (position, track_id) in ids.iter().enumerate() {
                statement.execute(params![user_key, kind, track_id, position as i64])?;
            }
        }
    }

    tx.execute("DELETE FROM moments WHERE user_key = ?1", params![user_key])?;
    {
        let mut statement = tx.prepare(
            "INSERT INTO moments (user_key, id, track_id, start_sec, end_sec, section)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)
             ON CONFLICT (user_key, id) DO NOTHING",
        )?;
        for moment in &value.moments {
            statement.execute(params![
                user_key,
                moment.id,
                moment.track_id,
                moment.start,
                moment.end,
                moment.section,
            ])?;
        }
    }

    // playlist_tracks 는 외래키 CASCADE 로 함께 지워진다.
    tx.execute("DELETE FROM playlists WHERE user_key = ?1", params![user_key])?;
    {
        let mut head = tx.prepare(
            "INSERT INTO playlists (user_key, id, name, position) VALUES (?1, ?2, ?3, ?4)
             ON CONFLICT (user_key, id) DO NOTHING",
        )?;
        let mut item = tx.prepare(
            "INSERT INTO playlist_tracks (user_key, playlist_id, track_id, position)
             VALUES (?1, ?2, ?3, ?4)
             ON CONFLICT (user_key, playlist_id, position) DO NOTHING",
        )?;
        for (position, playlist) in value.playlists.iter().enumerate() {
            head.execute(params![user_key, playlist.id, playlist.name, position as i64])?;
            for (index, track_id) in playlist.track_ids.iter().enumerate() {
                item.execute(params![user_key, playlist.id, track_id, index as i64])?;
            }
        }
    }

    tx.commit()?;
    Ok(())
}

pub fn read_photo(db: &Db, user_key: &str) -> DbResult<Option<String>> {
    let connection = db.lock();
    let value = connection
        .query_row(
            "SELECT data FROM profile_photo WHERE user_key = ?1",
            params![user_key],
            |row| row.get::<_, String>(0),
        )
        .optional()?;
    Ok(value)
}

pub fn save_photo(db: &Db, user_key: &str, data: &str) -> DbResult<()> {
    let connection = db.lock();
    connection.execute(
        "INSERT INTO profile_photo (user_key, data) VALUES (?1, ?2)
         ON CONFLICT (user_key) DO UPDATE SET data = excluded.data",
        params![user_key, data],
    )?;
    Ok(())
}

pub fn remove_photo(db: &Db, user_key: &str) -> DbResult<()> {
    let connection = db.lock();
    connection.execute(
        "DELETE FROM profile_photo WHERE user_key = ?1",
        params![user_key],
    )?;
    Ok(())
}
