"""Read-only check for T000260: navigate to its session_url, find the
producer-part with 2 candidate cards, and compare each card's cover image
URL against the currently-saved downloads/covers/T000260.jpg. No clicks
that trigger generation, no downloads, no CHOOSE, no Like."""
import asyncio
import hashlib
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from playwright.async_api import async_playwright
from flow_connect import CDP_ENDPOINT, find_flow_page
from download_latest import (
    detect_real_cards,
    PRODUCER_PART_SELECTOR,
    find_cover_image_url,
    first_visible_locator,
    wait_for_details_panel,
)

import os

SESSION_URL = os.environ.get("FLOW_SESSION_URL", "https://www.flowmusic.app/session/REPLACE_ME")  # was T000260's session
EXPECTED_TITLE = "頂上声明"
PROJECT_ROOT = Path(__file__).resolve().parent.parent
SAVED_COVER = PROJECT_ROOT / "downloads" / "covers" / "T000260.jpg"


async def main():
    async with async_playwright() as p:
        browser = await p.chromium.connect_over_cdp(CDP_ENDPOINT)
        page = await find_flow_page(browser)
        if page is None:
            print("[ERROR] no flowmusic.app page found")
            return

        print(f"[NAV] going to {SESSION_URL}")
        await page.goto(SESSION_URL, wait_until="domcontentloaded")
        await page.wait_for_timeout(2500)

        n_parts = await page.locator(PRODUCER_PART_SELECTOR).count()
        print(f"[INFO] producer-part count on this session page: {n_parts}")

        cards_all = await detect_real_cards(page)
        matching = [c for c in cards_all if c.title == EXPECTED_TITLE]
        print(f"[INFO] total real cards on page: {len(cards_all)}, matching title {EXPECTED_TITLE!r}: {len(matching)}")
        for c in cards_all:
            print(f"  dom_index={c.dom_index} title={c.title!r}")

        saved_hash = hashlib.sha256(SAVED_COVER.read_bytes()).hexdigest() if SAVED_COVER.exists() else None
        print(f"[INFO] currently saved cover hash: {saved_hash}")

        for i in range(len(matching)):
            # Re-navigate + re-detect each time: after clicking a title once,
            # the page has moved to /song/{id} so earlier locators for the
            # other sibling card go stale.
            if i > 0:
                await page.goto(SESSION_URL, wait_until="domcontentloaded")
                await page.wait_for_timeout(2000)
                cards_all = await detect_real_cards(page)
                matching = [c for c in cards_all if c.title == EXPECTED_TITLE]

            card = matching[i]
            print(f"\n--- candidate {i+1}/{len(matching)} (dom_index={card.dom_index}) ---")
            title_el = await first_visible_locator(card.title_locator)
            if title_el is None:
                print("  [SKIP] no visible title element")
                continue
            await title_el.click()
            ok = await wait_for_details_panel(page, card.title)
            print(f"  details panel confirmed: {ok}, url={page.url}")
            if not ok:
                continue
            cover_url = await find_cover_image_url(page)
            print(f"  cover_url: {cover_url}")
            if cover_url:
                resp = await page.context.request.get(cover_url)
                body = await resp.body()
                h = hashlib.sha256(body).hexdigest()
                print(f"  cover hash: {h}  (matches saved: {h == saved_hash})")

        print("\n[DONE] read-only check complete, no downloads/clicks-that-generate performed.")


asyncio.run(main())
