import json, glob, collections

mapping = {
'T000803':'Apex Showdown','T000804':'Vanguard Clash','T000807':'Siege Frontier','T000808':'High-Octane Skirmish',
'T000809':'Reckoning Strike','T000811':'Eclipse Action Warfront BGM','T000813':'Duel Stance','T000814':'Onset Siege',
'T000815':'Conflict Farline','T000817':'Epic Reckoning BGM','T000820':'Frontline Drive',
'T000821':"Legend's Voyage",'T000822':'Epic Odyssey','T000823':'Valiant Crusade','T000824':"Legend's Path",
'T000825':'Voyage of the Sky Kingdom','T000826':'Valiant Ascent','T000827':'Odyssey to the Uncharted',
'T000828':"Legend's Skyline",'T000829':'Kingdom of Valor','T000830':"Champion's Trial",'T000831':'Saga of the Hero',
'T000835':'Boundless Horizons','T000836':"The Wayfarer's Trail",'T000837':'Valiant Expedition','T000838':'Kingdom of Courage',
'T000840':'Odyssey of the Sunbound Hero',
'T000862':'Secrets in the Dusk','T000865':'Riddle in the Onyx','T000867':'Unsolved Enigma','T000866':'Umbra of Inquiry',
'T000871':'Reverberation of Pursuit','T000878':'Shadow Cipher','T000880':'Silent Inquiry',
'T000761':'Electric Weave','T000763':'Dream & Power Synthesis','T000764':'Pulse & Crossover','T000767':'Syncopated Convergence',
'T000768':'Blend Vista','T000769':'Hazy Alloy','T000770':'Alloy Weave',
'T000758':'Dreamy Bistro Sway','T000759':'Misty Jazz Vibe','T000760':'Ember Focus Jazz Swing',
'T000681':'Drifting Currents','T000683':'Melancholic Tides','T000692':'Wistful Swells',
'T000321':'Shadow Blitz','T000323':'Steel Pulse','T000325':'Thunder Horizon','T000326':'Forge Rhythm','T000327':'Molten Fury','T000328':'Blazing Road',
'T000782':'Afterglow of Honor','T000783':'Remnants of Faith','T000784':'Ascending Faith','T000785':'Traces of Glory',
'T000786':'Reverberation of Promise','T000787':'Beacon of Aspiration','T000790':'Beam of Faith','T000792':'Obscura and Glow',
'T000794':'Resonance of Triumph','T000795':'Fading Afterglow, Soaring Glow','T000796':'Ascending Edge',
'T000798':'Climbing Shadow, Hopeful Beam',
'T000132':'Prism Stride','T000133':'Vapor Melancholy','T000134':'Strobe Horizon','T000135':'Laser Pavement','T000137':'Retro Drive',
'T000541':'Prism Edge','T000542':'Vapor Frontier','T000543':'Electric Sunset Cruise','T000545':'Starlight Cyberdrive',
'T000546':'Retrograde Glide','T000549':'Twilight Sway','T000550':'Strobe Farline 115','T000552':'Insomnia Ride',
'T000555':'3AM Roam','T000556':'Vapor Drive','T000557':'Prism Sway','T000558':'Laser Distance','T000559':'Starlight Cruise',
'T000601':'Mellow Study','T000602':'Late Afternoon Brew','T000604':'Dreamy Zone Groove','T000605':'Lo-Fi Tea Break',
'T000607':'Quiet Presence','T000608':'Balmy Cafe Dreams','T000609':'Vigil Latte','T000612':'Starlit Kettle',
'T000613':'Toasty Electric Drift','T000614':'Rainy Day Mug','T000616':'Gauzy Mindset','T000618':'3AM Java',
'T000619':'Reverie Zone','T000623':'Roast & Nostalgia','T000628':'Snug Drift','T000629':'Glowing Roast Session',
'T000632':'Mellow Brew & Rain','T000634':'Melancholic Clarity','T000636':'Balmy Reverberation','T000637':'Toasty Zone Groove',
'T000640':'Hazy Mindset',
'T000901':'Quiet Musings','T000906':'Fading Silhouettes, Gentle Glow','T000907':'Hopeful Contemplation','T000910':'Musings in A Minor',
'T000911':'Amber Beam','T000912':'Ember Introspection','T000913':'Stillness at Dusk','T000915':'Golden Morning Glow',
'T000917':'Solace in Blue','T000918':'Introspection in Radiance','T000920':'Hearth Meditations','T000922':'Contemplation at Dawn',
'T000925':'Calm Piano Reveries','T000926':'Quietude & Hope','T000927':'Cozy Impressions','T000928':'Seclusion in Warmth',
'T000929':'Still Recollections','T000930':'Solace & Grace',
'T000961':'Soft Glow in the Brew','T000962':'Golden Beam','T000964':'Morning Shine at the Corner','T000967':'Morning Radiance','T000969':'Distant Gleam',
'T000472':'Grim Dreamscape','T000473':'Sinister Power','T000475':'Shadowed Mirage','T000476':'Obsidian Bell Trap',
'T000702':'Bistro Sunrise','T000703':'Acoustic Ease','T000705':'Gentle Brew Dawn','T000707':'Ember Acoustic Unwind',
'T000708':'Gentle Daybreak Corner','T000709':'Sunlit Dawn','T000710':'Golden Acoustic Repose',
'T000351':'Boundary Line','T000352':'Expanse Lines','T000353':'Ripples in the Fade','T000354':'Murmurs in the Shadows',
'T000355':'Drifting Expanse','T000356':'Afterimages in the Half-Light','T000358':'Atmospheric Ripples','T000359':'Shimmer & Expanse',
'T000360':'Fading Reaches','T000361':'Faded Periphery','T000362':'Silent Boundary','T000365':'Vanishing Periphery',
'T000366':'Dreamy Murmurs','T000368':'Ripples of the Expanse',
'T000413':'Nocturnal Sway','T000414':'After-Dark Drive','T000416':'Velvet Night Reverie','T000417':'Melancholy Glide','T000418':'Nocturne Float',
'T000453':'Boom-Bap Strut','T000456':'Boom-Bap Soul Stride','T000459':'Urban Roam',
'T000734':'Sunlit Funk Strut','T000740':'Vivid Street Stride','T000743':'Luminous Funk Sashay',
'T000513':'Gleaming Pulse','T000516':'Radiant Circuit','T000518':'Vivid Cafe House',
'T000591':'Hazy Throb','T000593':'Shadowed Signal','T000597':'Soft Beat Frontier',
'T000881':'Cheerful Stroll','T000882':'Homely Skip','T000883':'Breezy Courtyard','T000884':'Golden Meadows BGM',
'T000885':'Comfy Horizons','T000886':'Sunlit Path','T000887':'Quaint Meadow Journey','T000890':'Snug Quest (Casual Game BGM)',
'T000891':'Ember Glow BGM','T000892':'Gleam Pace','T000893':'Radiant Breeze BGM','T000894':'Homely Route BGM',
'T000895':'Beam & Calm Game BGM','T000896':'Cheerful Lane','T000899':'Gentle Stride',
'T000564':'Impact Momentum','T000566':'Acceleration (172 BPM)','T000568':'Digital Rush','T000571':'Synthetic Drift',
'T000572':'Surge 169','T000574':'Kinetic Sway','T000577':'Android Current','T000578':'Night Charge 166','T000580':'Neuro-Rush',
'T000641':'Radiant Undertone Chill','T000644':'Vivid Undertone','T000645':'Luminous Brew Pocket','T000647':'Cozy Zone',
'T000648':'Sunny Corner Bounce','T000650':'Golden Hour Clarity','T000656':'Zone Flow','T000659':'Tender Mindset Pocket','T000660':'Morning Bounce',
'T000841':'Reverb of Trust','T000846':'Tenderness in the Shadow','T000847':'Whispers of Belief','T000849':'Resonance of Embrace',
'T000852':'Fading Radiance','T000853':'Murmurs of Comfort','T000854':'Ray of Solace','T000859':'Traces of Optimism',
'T000933':'Waning Reflections','T000935':'Faithful Farline','T000936':'Dimming Luminance','T000939':'Ebbing Remnants',
'T000941':'Trusting Skyline','T000944':'Promising Sway',
'T000975':'Hushed Distance','T000978':'Floating Veil','T000979':'Muted Reverberation','T000980':'Wandering Reverb',
'T000983':'Meandering Reflections','T000987':'Soundless Float',
'T000711':'Smoky Kitchen Swing','T000712':'Pocket Study','T000714':'Tender Espresso Vibe','T000716':'Pocket Reflection',
'T000717':'Sultry Diner Swing','T000723':'Honeyed Nook Swing',
}

print('총 매핑 개수:', len(mapping))

rows = []
for fp in glob.glob('downloads/metadata/*.json'):
    d = json.load(open(fp, encoding='utf-8'))
    rows.append({'track_id': d['track_id'], 'title': d.get('flow_title')})

missing = [tid for tid in mapping if tid not in {r['track_id'] for r in rows}]
print('존재하지 않는 track_id:', missing)

existing = collections.Counter(r['title'] for r in rows if r['track_id'] not in mapping)
final = collections.Counter(existing)
for tid, new_t in mapping.items():
    final[new_t] += 1
dups = {t: c for t, c in final.items() if c > 1}
print('예상 완전중복:', len(dups), dups)

# internal collision (new values duplicated among themselves against pre-existing count 0)
internal_dup = {t: c for t, c in collections.Counter(mapping.values()).items() if c > 1}
print('새 제목끼리 내부 중복:', internal_dup)

json.dump(mapping, open('title_rework_plan_wave4.json', 'w', encoding='utf-8'), indent=2, ensure_ascii=False)
