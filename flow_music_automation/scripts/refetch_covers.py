"""
Batch re-fetch corrected/alternate album cover art for known-bad tracks.
Audio, song_id, lyrics, sound_raw, titles -- nothing but assets.cover and
the on-disk covers/*.jpg file is ever touched.

Two independent fix categories:

1. DUP_TRACK_IDS: this track's currently-saved cover file is a verified
   byte-for-byte duplicate of a DIFFERENT track's cover (confirmed via a
   full sha256 scan of downloads/covers -- a stale-DOM race in the
   original download_latest.py's find_cover_image_url() call, which
   sometimes grabbed the still-visible previous track's image before the
   Details panel finished updating). The fix navigates directly to this
   track's OWN metadata source.song_url and re-extracts whatever cover is
   actually shown there -- no chat session needed, no "different version"
   choice involved. This is just getting the CORRECT file for this track.

2. QUALITY_TRACK_IDS: this track's saved cover IS correctly its own image,
   it's just visually low quality. Each of these has
   generated_card_count == 2, and do_download() never clicks CHOOSE, so
   both candidates are still present in the chat session. The fix opens
   the track's session_url and pulls the OTHER (never-downloaded)
   candidate's cover art instead. That candidate has its own distinct
   song_id -- only its cover image is used here, audio/lyrics are left
   completely alone.

Dry-run by default: connects, navigates, fetches + hashes each candidate
cover into memory, and reports what WOULD change. Nothing is written to
disk. Pass --confirm-apply to actually:
  1. back up downloads/covers (full timestamped copy),
  2. overwrite downloads/covers/{track_id}.jpg,
  3. append one record per track to logs/cover_refetch_log.jsonl.

By explicit user decision, downloads/metadata/*.json is NOT touched --
only the on-disk cover files change. This means assets.cover.sha256 /
size_bytes in metadata will no longer match the new file on disk for
these 31 tracks (a known, accepted tradeoff, not an oversight).
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from playwright.async_api import async_playwright

from flow_connect import CDP_ENDPOINT, find_flow_page
from download_latest import (
    detect_real_cards,
    find_cover_image_url,
    first_visible_locator,
    wait_for_details_panel,
    producer_part_locator,
    count_producer_parts,
)

PROJECT_ROOT = Path(__file__).resolve().parent.parent
COVERS_DIR = PROJECT_ROOT / "downloads" / "covers"
METADATA_DIR = PROJECT_ROOT / "downloads" / "metadata"
LOGS_DIR = PROJECT_ROOT / "logs"
LOG_PATH = LOGS_DIR / "cover_refetch_log.jsonl"

# Byte-identical to another track's cover (a different track_id's cover
# file, confirmed by a full sha256 scan). Fix: re-fetch this track's own
# song_url directly.
DUP_TRACK_IDS = [
    "T000257", "T000259", "T000261", "T000265",
    "T000650", "T000651", "T000653", "T000654",
    "T000658", "T000667", "T000669", "T000948",
]

# Correctly this track's own cover, but visually low quality. Fix: pull the
# unchosen sibling candidate's cover from the same session (verified live
# that generated_card_count == 2 and both candidates still exist).
QUALITY_TRACK_IDS = [
    "T000260", "T000431", "T000440", "T000579", "T000601", "T000615",
    "T000625", "T000633", "T000912", "T000913", "T000932", "T000942",
    "T000974", "T000980", "T000981", "T000983", "T000986", "T000989",
    "T001027",
]


def log(msg: str) -> None:
    try:
        print(msg)
    except UnicodeEncodeError:
        enc = sys.stdout.encoding or "utf-8"
        print(msg.encode(enc, errors="replace").decode(enc, errors="replace"))


def load_metadata(track_id: str) -> dict:
    return json.loads((METADATA_DIR / f"{track_id}.json").read_text(encoding="utf-8"))


def append_log(record: dict) -> None:
    LOGS_DIR.mkdir(parents=True, exist_ok=True)
    with LOG_PATH.open("a", encoding="utf-8") as f:
        f.write(json.dumps(record, ensure_ascii=False) + "\n")


def backup_covers_dir() -> Path:
    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    covers_bak = COVERS_DIR.parent / f"covers.bak_before_cover_refetch_{ts}"
    log(f"[BACKUP] copying {COVERS_DIR} -> {covers_bak}")
    shutil.copytree(COVERS_DIR, covers_bak)
    return covers_bak


async def fetch_bytes(page, url: str) -> bytes:
    resp = await page.context.request.get(url)
    if not resp.ok:
        raise RuntimeError(f"request failed: status={resp.status} url={url}")
    return await resp.body()


def sha256_bytes(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


async def resolve_dup_cover(page, track_id: str, meta: dict) -> dict:
    song_url = meta["source"]["song_url"]
    log(f"[DUP] {track_id}: navigating directly to own song_url {song_url}")
    await page.goto(song_url, wait_until="domcontentloaded")
    await page.wait_for_timeout(2000)
    cover_url = await find_cover_image_url(page)
    if not cover_url:
        raise RuntimeError(f"{track_id}: no cover image found at {song_url}")
    body = await fetch_bytes(page, cover_url)
    return {"cover_url": cover_url, "body": body, "alt_song_id": None}


async def resolve_quality_cover(page, track_id: str, meta: dict, old_hash: str) -> dict:
    """Titles are NOT a reliable match key here: many flow_title values were
    directly overwritten by the past title-dedup waves, so they no longer
    match the literal title still shown on the live Flow page (e.g. this
    project's flow_title 'Late Eve Reverie' vs the live page's actual
    'Late Night Reverie' / 'Late Night Reverie (Take 2)'). Instead: open the
    session, scope to its (newest) producer-part, expect exactly 2 real
    cards, click each in turn, and identify "ours" as whichever candidate's
    cover hash matches the file already saved on disk (old_hash) -- the
    OTHER one is the genuinely different sibling we want."""
    session_url = meta["source"]["session_url"]
    log(f"[QUALITY] {track_id}: navigating to session {session_url}")
    await page.goto(session_url, wait_until="domcontentloaded")
    await page.wait_for_timeout(2000)

    n_parts = await count_producer_parts(page)
    if n_parts < 1:
        raise RuntimeError(f"{track_id}: no producer-part found on session page")
    scope = producer_part_locator(page, n_parts - 1)

    cards = await detect_real_cards(page, scope=scope)
    if len(cards) != 2:
        raise RuntimeError(f"{track_id}: expected exactly 2 candidate cards in this session, found {len(cards)}")

    results = []
    for i in range(2):
        if i > 0:
            await page.goto(session_url, wait_until="domcontentloaded")
            await page.wait_for_timeout(2000)
            cards = await detect_real_cards(page, scope=producer_part_locator(page, n_parts - 1))
            if len(cards) != 2:
                raise RuntimeError(f"{track_id}: card count changed on re-detect ({len(cards)})")

        card = cards[i]
        title_el = await first_visible_locator(card.title_locator)
        if title_el is None:
            raise RuntimeError(f"{track_id}: candidate {i} title element not visible/clickable")
        await title_el.click()
        ok = await wait_for_details_panel(page, card.title)
        if not ok:
            raise RuntimeError(f"{track_id}: Details panel did not confirm for candidate {i}")

        import re as _re
        m = _re.search(r"/song/([^/?#]+)", page.url)
        song_id = m.group(1) if m else None

        cover_url = await find_cover_image_url(page)
        if not cover_url:
            raise RuntimeError(f"{track_id}: no cover image found for candidate {i}")
        body = await fetch_bytes(page, cover_url)
        h = sha256_bytes(body)
        results.append({"song_id": song_id, "cover_url": cover_url, "body": body, "hash": h})

    ours = [r for r in results if r["hash"] == old_hash]
    others = [r for r in results if r["hash"] != old_hash]

    if len(ours) != 1 or len(others) != 1:
        raise RuntimeError(
            f"{track_id}: could not unambiguously identify sibling by hash "
            f"(hashes: {[r['hash'][:12] for r in results]}, old={old_hash[:12] if old_hash else None})"
        )

    sibling = others[0]
    if sibling["song_id"] == meta["source"]["song_id"]:
        raise RuntimeError(f"{track_id}: sibling candidate resolved to the SAME song_id, aborting")

    return {"cover_url": sibling["cover_url"], "body": sibling["body"], "alt_song_id": sibling["song_id"]}


async def process_one(page, track_id: str, category: str, confirm: bool) -> dict:
    meta = load_metadata(track_id)
    old_path = COVERS_DIR / f"{track_id}.jpg"
    old_hash = sha256_bytes(old_path.read_bytes()) if old_path.exists() else None

    record = {
        "track_id": track_id,
        "category": category,
        "old_cover_sha256": old_hash,
        "status": "ERROR",
        "error": None,
        "new_cover_sha256": None,
        "new_cover_url": None,
        "alt_song_id": None,
        "changed": None,
        "applied": False,
    }

    try:
        if category == "dup":
            result = await resolve_dup_cover(page, track_id, meta)
        else:
            result = await resolve_quality_cover(page, track_id, meta, old_hash)

        body = result["body"]
        if body[:3] != b"\xff\xd8\xff":
            raise RuntimeError(f"{track_id}: fetched bytes do not look like a JPEG")

        new_hash = sha256_bytes(body)
        record["new_cover_sha256"] = new_hash
        record["new_cover_url"] = result["cover_url"]
        record["alt_song_id"] = result["alt_song_id"]
        record["changed"] = new_hash != old_hash
        record["status"] = "OK"

        log(
            f"[{category.upper()}] {track_id}: old={old_hash} new={new_hash} "
            f"changed={record['changed']}"
        )

        if confirm and record["changed"]:
            old_path.write_bytes(body)
            record["applied"] = True
            log(f"[APPLY] {track_id}: cover file overwritten (metadata left untouched, by design)")
        elif confirm and not record["changed"]:
            log(f"[APPLY] {track_id}: new cover identical to old, nothing written")

    except Exception as e:
        record["status"] = "ERROR"
        record["error"] = str(e)
        log(f"[ERROR] {track_id}: {e}")

    return record


async def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--confirm-apply", action="store_true", help="Actually back up + overwrite files.")
    parser.add_argument("--only", nargs="*", default=None, help="Restrict to these track_ids (for testing).")
    args = parser.parse_args()

    dup_ids = [t for t in DUP_TRACK_IDS if not args.only or t in args.only]
    quality_ids = [t for t in QUALITY_TRACK_IDS if not args.only or t in args.only]

    if args.confirm_apply:
        backup_covers_dir()

    records = []
    async with async_playwright() as p:
        browser = await p.chromium.connect_over_cdp(CDP_ENDPOINT)
        page = await find_flow_page(browser)
        if page is None:
            log("[ERROR] no flowmusic.app page found")
            return 1

        for tid in dup_ids:
            rec = await process_one(page, tid, "dup", args.confirm_apply)
            records.append(rec)
            if args.confirm_apply:
                append_log(rec)

        for tid in quality_ids:
            rec = await process_one(page, tid, "quality", args.confirm_apply)
            records.append(rec)
            if args.confirm_apply:
                append_log(rec)

    ok = [r for r in records if r["status"] == "OK"]
    errors = [r for r in records if r["status"] == "ERROR"]
    changed = [r for r in records if r.get("changed")]
    applied = [r for r in records if r.get("applied")]

    log("\n[SUMMARY]")
    log(f"  total: {len(records)}  ok: {len(ok)}  errors: {len(errors)}")
    log(f"  would-change / changed: {len(changed)}")
    if args.confirm_apply:
        log(f"  applied (written to disk): {len(applied)}")
    else:
        log("  DRY-RUN: nothing written. Re-run with --confirm-apply to apply.")

    if errors:
        log("\n[ERRORS]")
        for r in errors:
            log(f"  {r['track_id']} ({r['category']}): {r['error']}")

    return 0 if not errors else 1


if __name__ == "__main__":
    exit_code = asyncio.run(main())
    sys.exit(exit_code)
