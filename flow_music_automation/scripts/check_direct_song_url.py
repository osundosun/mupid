"""Read-only check: does navigating directly to a track's own song_url
(from metadata source.song_url) show the correct distinct cover, without
needing to click through the chat session? Tests on T000257 (known
corrupted: currently saved cover is byte-identical to T000256's)."""
import asyncio
import hashlib
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from playwright.async_api import async_playwright
from flow_connect import CDP_ENDPOINT, find_flow_page
from download_latest import find_cover_image_url

PROJECT_ROOT = Path(__file__).resolve().parent.parent
TRACK_ID = "T000257"


async def main():
    meta = json.loads((PROJECT_ROOT / "downloads" / "metadata" / f"{TRACK_ID}.json").read_text(encoding="utf-8"))
    song_url = meta["source"]["song_url"]
    saved_cover = PROJECT_ROOT / "downloads" / "covers" / f"{TRACK_ID}.jpg"
    saved_hash = hashlib.sha256(saved_cover.read_bytes()).hexdigest()
    print(f"[INFO] {TRACK_ID} song_url: {song_url}")
    print(f"[INFO] currently saved cover hash (known corrupted / copy of T000256): {saved_hash}")

    async with async_playwright() as p:
        browser = await p.chromium.connect_over_cdp(CDP_ENDPOINT)
        page = await find_flow_page(browser)
        if page is None:
            print("[ERROR] no flowmusic.app page found")
            return

        print(f"[NAV] going directly to {song_url}")
        await page.goto(song_url, wait_until="domcontentloaded")
        await page.wait_for_timeout(2500)
        print(f"[INFO] page url after nav: {page.url}")

        cover_url = await find_cover_image_url(page)
        print(f"[INFO] cover_url found: {cover_url}")
        if cover_url:
            resp = await page.context.request.get(cover_url)
            body = await resp.body()
            h = hashlib.sha256(body).hexdigest()
            print(f"[INFO] fetched cover hash: {h}")
            print(f"[RESULT] matches currently-saved (corrupted) file: {h == saved_hash}")
            print(f"[RESULT] this would be the FIX (different from corrupted file): {h != saved_hash}")


asyncio.run(main())
