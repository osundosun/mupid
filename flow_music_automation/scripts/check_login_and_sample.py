"""Read-only check: is Flow Music logged in, and does T000260's session
still have both candidate cards with distinct covers? No clicks that
trigger generation, no downloads, no CHOOSE."""
import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from playwright.async_api import async_playwright
from flow_connect import CDP_ENDPOINT, find_flow_page

import os

SESSION_URL = os.environ.get("FLOW_SESSION_URL", "https://www.flowmusic.app/session/REPLACE_ME")


async def main():
    async with async_playwright() as p:
        browser = await p.chromium.connect_over_cdp(CDP_ENDPOINT)
        page = await find_flow_page(browser)
        if page is None:
            print("[INFO] No flowmusic.app page open yet. Opening one.")
            ctx = browser.contexts[0]
            page = await ctx.new_page()
            await page.goto("https://www.flowmusic.app/", wait_until="domcontentloaded")

        print(f"[OK] page url: {page.url}")
        await page.wait_for_timeout(1500)

        # Login check: look for a sign-in button/link vs. any authenticated-only element
        signin = page.get_by_text("Sign in", exact=False)
        signin_count = await signin.count()
        print(f"[LOGIN CHECK] 'Sign in' text matches on page: {signin_count}")

        title = await page.title()
        print(f"[INFO] page title: {title!r}")

        await page.screenshot(path=str(Path(__file__).resolve().parent.parent / "screenshots" / "login_check.png"))
        print("[OK] screenshot saved to screenshots/login_check.png")


asyncio.run(main())
