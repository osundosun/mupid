import json, glob, collections

mapping = {
'T000137':'Retro Pursuit','T000322':'High Voltage Thrust','T000329':'Heavy Charge',
'T000411':'Moonless Voyage','T000414':'After-Dark Passage','T000451':'Graveyard Trek',
'T000457':'Curfew Descent','T000471':'Dream Trap Surge','T000474':'3AM Rush',
'T000519':'Four-on-the-Floor Momentum','T000520':'Starlit Coast','T000553':'Backstreet Runway',
'T000556':'Vapor Transit','T000584':'Glow Shuffled Ride','T000587':'Starless Ride (UKG Breakbeat)',
'T000691':'After Dark Descent','T000697':'Evening Course','T000766':'Aether Ascent','T000820':'Frontline Charge',
'T000007':'Owl Studio','T000188':'야시장 길 (Moonlit Market Walk)','T000416':'Velvet Dusk Reverie',
'T000440':'Late Eve Reverie','T000452':'Graveyard Shift','T000477':'Nocturnal Vision','T000511':'Moonless Cruise',
'T000578':'Blackout Charge 166','T000611':'Starless Bistro Lo-Fi','T000625':'Obsidian Electric',
'T000666':'Shadowbound Circuit','T000671':'Eve Study','T000695':'Moonlit Reflection',
'T000438':'Late Walk Swagger','T000512':'Cafe Motion','T000604':'Wispy Zone Cadence','T000633':'Lo-Fi Chill Bounce',
'T000637':'Toasty Zone Vibe','T000661':'Calm Half-Time Pocket','T000732':'Slap & Motion','T000733':'Pocket Brew',
'T000736':'Slap & Synth Swagger','T000742':'Corner Funk Cadence','T000745':'Kitchen Funk Beat',
'T000746':'Gleam Funk Pocket','T000750':'Power Swagger','T000752':'Velvet Diner Vibe',
'T000008':'Vibration & Analog','T000323':'Steel Vibration','T000513':'Gleaming Impulse','T000536':'Misty Throb',
'T000539':'Hopeful Heartbeat','T000560':'Cybernetic Signal','T000567':'Reese Rhythm','T000589':'Urban Tempo',
'T000677':'Insomnia Heartbeat','T000731':'Electric Funk Impulse','T000747':'Workout Tempo',
'T000764':'Rhythm & Crossover','T000868':'Mystery Signal',
'T000366':'Gauzy Murmurs','T000367':'Misty Reverb','T000412':'Wistful Melancholy','T000533':'Hazy Distance',
'T000585':'Gossamer Breaks','T000627':'Wispy Sway','T000639':'Dreamlike Coast','T000673':'Hazy Wander',
'T000676':'Misty Current','T000686':'Gauzy Float','T000698':'Wistful Glide','T000758':'Ethereal Bistro Sway',
}

print('총 매핑:', len(mapping))

rows = []
for fp in glob.glob('downloads/metadata/*.json'):
    d = json.load(open(fp, encoding='utf-8'))
    rows.append({'track_id': d['track_id'], 'title': d.get('flow_title')})

existing_ids = {r['track_id'] for r in rows}
missing = [tid for tid in mapping if tid not in existing_ids]
print('존재하지 않는 track_id:', missing)

existing = collections.Counter(r['title'] for r in rows if r['track_id'] not in mapping)
final = collections.Counter(existing)
for tid, new_t in mapping.items():
    final[new_t] += 1
dups = {t: c for t, c in final.items() if c > 1}
print('예상 완전중복:', len(dups), dups)

internal_dup = {t: c for t, c in collections.Counter(mapping.values()).items() if c > 1}
print('새 제목끼리 내부중복:', internal_dup)

json.dump(mapping, open('title_rework_plan_wave5.json', 'w', encoding='utf-8'), indent=2, ensure_ascii=False)
