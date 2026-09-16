"""사용자가 제공한 초기 10곡의 무드 보완값 (2026-09-16).

생성 요청 라벨의 파싱 결과가 아니라 sound_raw를 사람이 해석한 근사치다.
GPU 원본 및 taxonomy에는 반영하지 않고, 원본 mood가 null인 경우에만 사용한다.
"""

SOURCE = "user_handoff_sound_raw_approx_2026-09-16"

# track_id: (원본 장르, primary, secondary, 사용자 제공 근거)
MOOD_APPROXIMATIONS = {
    "T000001": ("contemporary_pop", "bright", "warm",
                "bright uplifting melodic hook; warm youthful male tenor vocals"),
    "T000014": ("contemporary_pop", "bright", "warm",
                "T000001과 동일 프롬프트/사운드: bright sunny atmosphere; warm youthful..."),
    "T000003": ("rnb", "warm", "dreamy",
                "warm electric piano; intimate, relaxed, late-Sunday vibe"),
    "T000004": ("rnb", "warm", "dreamy",
                "warm mid-90s R&B atmospheric groove; hazy synth textures; smooth and seductive"),
    "T000005": ("poprock", "powerful", "hopeful",
                "powerful melodic chorus (프롬프트 원문); earnest, soaring, triumphant"),
    "T000006": ("poprock", "bright", "hopeful",
                "exhilarating summer atmosphere; urgent soaring chorus"),
    "T000007": ("lofi_hiphop", "warm", "calm",
                "mellow... late night vibe; dusty warm electric piano"),
    "T000008": ("synthwave", "dreamy", "powerful",
                "warm analog synth chords, atmospheric transitional textures; energetic and polished"),
    "T000009": ("solo_piano", "calm", "melancholic",
                "quiet emotional solo piano composition; gentle broken chords; natural breathing space"),
    "T000010": ("game_battle_bgm", "powerful", "dark",
                "tense and heroic; driving rhythm; heavy brass, distorted guitar"),
}
