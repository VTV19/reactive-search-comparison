import os
from pathlib import Path

import pytest
from playwright.sync_api import sync_playwright

# В контейнере тесты смонтированы в /work, а исходники — в PROJECT_DIR.
# Резервный путь вычисляется только при запуске из дерева исходного проекта.
project_dir = os.environ.get("PROJECT_DIR")
PROJECT = Path(project_dir) if project_dir else Path(__file__).resolve().parents[2]


@pytest.fixture(scope="session")
def browser():
    with sync_playwright() as playwright:
        executable = os.environ.get("BROWSER_EXECUTABLE")
        kwargs = {"headless": True, "args": ["--no-sandbox"]}
        if executable:
            kwargs["executable_path"] = executable
        instance = playwright.chromium.launch(**kwargs)
        yield instance
        instance.close()


@pytest.fixture(params=["mvc", "webflux"])
def target(request):
    key = request.param
    default = "http://127.0.0.1:18081" if key == "mvc" else "http://127.0.0.1:18082"
    return {"key": key, "url": os.environ.get(key.upper() + "_BASE_URL", default),
            "name": "Spring MVC" if key == "mvc" else "Spring WebFlux"}


@pytest.fixture
def page(browser):
    context = browser.new_context(viewport={"width": 1280, "height": 900})
    page = context.new_page()
    page.set_default_timeout(12000)
    yield page
    context.close()
