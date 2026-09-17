import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from playwright.async_api import async_playwright
from flow_connect import CDP_ENDPOINT, find_flow_page

import os

SESSION_URL = os.environ.get("FLOW_SESSION_URL", "https://www.flowmusic.app/session/REPLACE_ME")  # was T000440's session, found 0


async def main():
    async with async_playwright() as p:
        browser = await p.chromium.connect_over_cdp(CDP_ENDPOINT)
        page = await find_flow_page(browser)
        await page.goto(SESSION_URL, wait_until="domcontentloaded")
        await page.wait_for_timeout(3000)
        await page.screenshot(path=str(Path(__file__).resolve().parent.parent / "screenshots" / "debug_session.png"), full_page=False)
        body_text = await page.locator("body").inner_text()
        print("---BODY TEXT (first 2000 chars)---")
        print(body_text[:2000])


asyncio.run(main())
