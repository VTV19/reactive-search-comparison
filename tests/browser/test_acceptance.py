"""End-to-end checks against real packaged MVC and WebFlux applications."""
import uuid

import pytest
from playwright.sync_api import expect

from http_support import call, metadata_mode, remove_created


def loaded(page, target):
    errors = []
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.goto(target["url"])
    expect(page.locator("#connection-status")).to_contain_text("Соединение установлено")
    expect(page.locator("#result-summary")).to_contain_text("Найдено:")
    assert not errors
    return errors


def test_application_identity_and_real_catalogue(page, target):
    errors = loaded(page, target)
    expect(page.locator("#app-info")).to_contain_text(target["name"])
    status, info = call(target["url"], "/api/info")
    assert status == 200
    assert info["version"] == "3.1.0"
    assert info["implementation"] == target["key"]
    status, result = call(target["url"], "/api/search?enrich=false")
    assert status == 200 and result["total"] == 36
    assert len(result["items"]) == 10
    expect(page.locator(".card")).to_have_count(10)
    assert not errors


def test_search_filter_pagination_and_empty_results(page, target):
    loaded(page, target)
    page.locator("#query").fill("java")
    page.locator("#search-button").click()
    expect(page.locator(".card")).not_to_have_count(0)
    page.locator("#category").select_option("JAVA")
    expect(page.locator("#search-button")).to_be_enabled()
    expect(page.locator(".card .card-meta").first).to_contain_text("Java")
    page.locator("#reset-search").click()
    expect(page.locator("#page-label")).to_have_text("1–10 из 36")
    page.locator("#next").click()
    expect(page.locator("#page-label")).to_have_text("11–20 из 36")
    page.locator("#query").fill("zzzznonexistentword")
    page.locator("#search-button").click()
    expect(page.locator("#result-summary")).to_have_text("Найдено: 0")
    expect(page.locator("#notice")).to_be_hidden()


def test_create_edit_delete_through_the_browser(page, target):
    token = "ui" + uuid.uuid4().hex
    identifier = None
    loaded(page, target)
    try:
        page.locator("#add-document").click()
        page.locator("#edit-title").fill("Browser catalogue " + token)
        page.locator("#edit-text").fill("Text for browser verification " + token)
        page.locator("#edit-source").fill("https://example.org/catalogue")
        with page.expect_response(lambda response: response.request.method == "POST"
                                  and response.url.endswith("/api/documents")) as pending:
            page.locator("#save-document").click()
        response = pending.value
        assert response.status == 201
        value = response.json()
        identifier = value["id"]
        assert value["version"] == 0
        expect(page.locator("#edit-dialog")).not_to_be_visible()
        page.locator("#query").fill(token)
        page.locator("#search-button").click()
        expect(page.locator("#result-summary")).to_have_text("Найдено: 1")
        page.locator(".card h3 button").click()
        expect(page.locator("#view-details")).to_contain_text("Версия 0")
        page.locator("#edit-document").click()
        page.locator("#edit-text").fill("Updated browser text " + token)
        page.locator("#save-document").click()
        expect(page.locator("#edit-dialog")).not_to_be_visible()
        expect(page.locator("#search-button")).to_be_enabled()
        page.locator(".card h3 button").click()
        expect(page.locator("#view-details")).to_contain_text("Версия 1")
        expect(page.locator("#view-text")).to_contain_text("Updated browser text")
        page.on("dialog", lambda dialog: dialog.accept())
        page.locator("#delete-document").click()
        expect(page.locator("#view-dialog")).not_to_be_visible()
        expect(page.locator("#result-summary")).to_have_text("Найдено: 0")
        assert call(target["url"], "/api/documents/" + identifier)[0] == 404
    finally:
        if identifier:
            remove_created(target["url"], identifier)


def test_two_tabs_detect_a_real_write_conflict(page, target):
    token = "conflict" + uuid.uuid4().hex
    body = {"title": token, "text": token, "category": "JAVA", "sourceUrl": "https://example.org"}
    status, value = call(target["url"], "/api/documents", "POST", body)
    assert status == 201
    second = page.context.new_page()
    try:
        for tab in (page, second):
            loaded(tab, target)
            tab.locator("#query").fill(token)
            tab.locator("#search-button").click()
            expect(tab.locator("#result-summary")).to_have_text("Найдено: 1")
            tab.locator(".card h3 button").click()
            tab.locator("#edit-document").click()
        page.locator("#edit-text").fill("First saved value " + token)
        page.locator("#save-document").click()
        expect(page.locator("#edit-dialog")).not_to_be_visible()
        second.locator("#edit-text").fill("Unsaved text retained in second tab")
        second.locator("#save-document").click()
        expect(second.locator("#edit-error")).to_contain_text("уже изменён")
        expect(second.locator("#edit-text")).to_have_value("Unsaved text retained in second tab")
        assert call(target["url"], "/api/documents/" + value["id"])[1]["text"].startswith("First saved")
    finally:
        second.close()
        remove_created(target["url"], value["id"])


@pytest.mark.parametrize("mode", ["error", "slow", "invalid"])
def test_optional_dependency_failure_and_recovery(page, target, mode):
    try:
        metadata_mode(mode)
        loaded(page, target)
        expect(page.locator("#notice")).to_contain_text("справочник недоступен")
        expect(page.locator(".card")).to_have_count(10)
        status, result = call(target["url"], "/api/search?q=java&enrich=true")
        assert status == 200 and result["degraded"] is True and result["total"] > 0
        metadata_mode("normal")
        page.locator("#search-button").click()
        expect(page.locator("#notice")).to_be_hidden()
        expect(page.locator(".references").first).to_contain_text("Источник материала")
        assert call(target["url"], "/api/search?q=java&enrich=true")[1]["degraded"] is False
    finally:
        metadata_mode("normal")


def test_invalid_requests_do_not_break_the_application(target):
    for query in ["limit=0", "limit=51", "limit=word", "mode=UNKNOWN", "category=UNKNOWN", "q=%21"]:
        status, body = call(target["url"], "/api/search?" + query)
        assert status == 400, (query, status, body)
    status, _ = call(target["url"], "/api/documents", "POST",
                     {"title": "", "text": "text", "category": "JAVA", "sourceUrl": "https://example.org"})
    assert status == 400
    assert call(target["url"], "/api/search?q=java&enrich=false")[0] == 200


def test_health_and_application_metrics(page, target):
    loaded(page, target)
    page.locator("summary").click()
    page.locator("#refresh-status").click()
    expect(page.locator("#health")).to_have_text("Приложение и MongoDB готовы к работе")
    expect(page.locator("#metrics dd")).to_have_count(4)
    assert call(target["url"], "/actuator/health/readiness")[0] == 200
    assert call(target["url"], "/actuator/env")[0] == 404
