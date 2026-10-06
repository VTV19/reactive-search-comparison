"""Isolated DOM tests. fetch is substituted; no Spring or MongoDB is used here."""
import json
import re

import pytest
from playwright.sync_api import expect

from conftest import PROJECT


MOCK = r"""
(config) => {
  const doc = {id: 'd001', title: 'Материал Java', text: 'Текст материала',
    category: 'JAVA', sourceUrl: 'https://example.org/java', version: 0};
  window.mockModes = {};
  window.mockRequests = [];
  window.mockResult = {query: '', total: 1, limit: 10, offset: 0, degraded: false,
    items: [{...doc, snippet: doc.text, score: 1.5, reference: null}]};
  window.fetch = (path, options = {}) => {
    const url = new URL(path, 'http://catalog.test');
    const method = options.method || 'GET';
    const payload = options.body ? JSON.parse(options.body) : null;
    window.mockRequests.push({path: url.pathname, query: url.search, method, body: payload});
    const action = url.pathname === '/api/info' ? 'info'
      : url.pathname === '/api/search' ? 'search'
      : url.pathname.startsWith('/actuator') ? 'health'
      : method === 'POST' ? 'create' : method === 'PUT' ? 'update'
      : method === 'DELETE' ? 'delete' : 'document';
    const mode = window.mockModes[action] || 'normal';
    if (mode === 'hang') return new Promise((resolve, reject) => {
      const cancel = () => reject(new DOMException('Aborted', 'AbortError'));
      if (options.signal.aborted) cancel();
      else options.signal.addEventListener('abort', cancel, {once: true});
    });
    if (mode === 'network') return Promise.reject(new TypeError('Network unavailable'));
    const reply = (body, status = 200) => Promise.resolve(new Response(
      status === 204 ? null : JSON.stringify(body), {status, headers: {'Content-Type': 'application/json'}}));
    if (mode === '503') return reply({message: 'База данных недоступна'}, 503);
    if (mode === '409') return reply({message: 'Материал уже изменён'}, 409);
    if (mode === 'malformed') return Promise.resolve(new Response('{bad', {status: 200}));
    if (mode === 'empty') return reply({});
    if (action === 'info') return reply({implementation: config.key,
      version: mode === 'old' ? '2.0.0' : '3.1.0', application: config.name});
    if (action === 'search') return reply(window.mockResult);
    if (action === 'health') return reply({status: 'UP', measurements: [{value: 1}]});
    if (action === 'create') return reply({...doc, ...payload, id: 'd002'}, 201);
    if (action === 'update') return reply({...doc, ...payload, version: payload.version + 1});
    if (action === 'delete') return reply(null, 204);
    return reply(doc);
  };
}
"""


class UserInterface:
    def __init__(self, page, key):
        self.page = page
        self.key = key

    def mode(self, action, value):
        self.page.evaluate("([action, value]) => window.mockModes[action] = value", [action, value])

    def start(self):
        self.page.add_script_tag(content=(PROJECT / "ui/assets/app-v3.js").read_text(encoding="utf-8"))
        return self.page

    def ready(self):
        self.start()
        expect(self.page.locator("#result-summary")).to_have_text("Найдено: 1")
        return self.page


@pytest.fixture(params=["mvc", "webflux"])
def ui(request, page):
    key = request.param
    name = "Spring MVC" if key == "mvc" else "Spring WebFlux"
    html = (PROJECT / "ui/index.html").read_text(encoding="utf-8")
    html = html.replace("@applicationName@", name).replace("@applicationKey@", key).replace("@project.version@", "3.1.0")
    # All assets are injected from local source files. These tests require no network permission.
    html = re.sub(r'<link\b[^>]*>', '', html)
    html = re.sub(r'<script\s+src="[^"]+"\s+defer></script>', '', html)
    page.clock.install()
    page.set_content(html)
    page.add_style_tag(content=(PROJECT / "ui/assets/style-v3.css").read_text(encoding="utf-8"))
    page.evaluate(MOCK, {"key": key, "name": name})
    return UserInterface(page, key)


def open_form(page):
    page.locator("#add-document").click()
    page.locator("#edit-title").fill("Новая запись")
    page.locator("#edit-text").fill("Текст новой записи")
    page.locator("#edit-source").fill("https://example.org/new")


def test_boot_renders_both_variants_without_javascript_errors(ui):
    errors = []
    ui.page.on("pageerror", lambda error: errors.append(str(error)))
    page = ui.ready()
    expect(page.locator("#connection-status")).to_contain_text("Соединение установлено")
    expect(page.locator("#app-info")).to_contain_text("Spring MVC" if ui.key == "mvc" else "Spring WebFlux")
    assert not errors


@pytest.mark.parametrize("kind", ["503", "malformed", "network"])
def test_failed_search_restores_controls_and_displays_error(ui, kind):
    ui.mode("search", kind)
    page = ui.start()
    expect(page.locator("#notice")).to_be_visible()
    expect(page.locator("#result-summary")).to_have_text("Поиск не выполнен")
    expect(page.locator("#search-button")).to_be_enabled()


def test_info_timeout_does_not_prevent_catalogue_loading(ui):
    ui.mode("info", "hang")
    page = ui.ready()
    page.clock.fast_forward(6000)
    expect(page.locator("#connection-status")).to_contain_text("Время ожидания")
    expect(page.locator("#retry-connect")).to_be_enabled()


def test_search_has_a_browser_deadline(ui):
    ui.mode("search", "hang")
    page = ui.start()
    expect(page.locator("#result-summary")).to_have_text("Поиск…")
    page.clock.fast_forward(11000)
    expect(page.locator("#notice")).to_contain_text("Время ожидания")
    expect(page.locator("#search-button")).to_be_enabled()


def test_missing_main_script_is_reported_without_infinite_loading(ui):
    ui.page.clock.fast_forward(6000)
    expect(ui.page.locator("#startup-error")).to_contain_text("Интерфейс не загрузился")


def test_script_error_event_is_reported(ui):
    ui.page.evaluate("""() => {
      const script = document.createElement('script');
      document.body.append(script);
      script.dispatchEvent(new Event('error'));
    }""")
    expect(ui.page.locator("#startup-error")).to_contain_text("Ошибка загрузки интерфейса")


def test_wrong_server_release_is_reported(ui):
    ui.mode("info", "old")
    page = ui.ready()
    expect(page.locator("#connection-status")).to_contain_text("Версии страницы и сервера различаются")


def test_write_error_preserves_form_text(ui):
    page = ui.ready()
    open_form(page)
    ui.mode("create", "503")
    page.locator("#save-document").click()
    expect(page.locator("#edit-error")).to_contain_text("База данных")
    expect(page.locator("#edit-text")).to_have_value("Текст новой записи")
    expect(page.locator("#save-document")).to_be_enabled()
    expect(page.locator("#close-edit")).to_be_enabled()


@pytest.mark.parametrize("failure", ["hang", "network"])
def test_uncertain_write_does_not_report_success(ui, failure):
    page = ui.ready()
    open_form(page)
    ui.mode("create", failure)
    page.locator("#save-document").click()
    if failure == "hang":
        expect(page.locator("#save-document")).to_be_disabled()
        page.clock.fast_forward(11000)
    expect(page.locator("#edit-error")).to_contain_text("Изменение могло сохраниться")
    expect(page.locator("#edit-text")).to_have_value("Текст новой записи")
    expect(page.locator("#save-document")).to_be_enabled()


def test_conflict_is_visible_and_current_version_is_submitted(ui):
    page = ui.ready()
    page.locator(".card h3 button").first.click()
    expect(page.locator("#view-details")).to_contain_text("Версия 0")
    page.locator("#edit-document").click()
    page.locator("#edit-text").fill("Не потерять этот текст")
    ui.mode("update", "409")
    page.locator("#save-document").click()
    expect(page.locator("#edit-error")).to_contain_text("уже изменён")
    expect(page.locator("#edit-text")).to_have_value("Не потерять этот текст")
    requests = page.evaluate("window.mockRequests")
    assert [value for value in requests if value["method"] == "PUT"][0]["body"]["version"] == 0


def test_delete_error_is_shown_inside_the_open_dialog(ui):
    page = ui.ready()
    page.locator(".card h3 button").first.click()
    page.on("dialog", lambda dialog: dialog.accept())
    ui.mode("delete", "409")
    page.locator("#delete-document").click()
    expect(page.locator("#view-error")).to_contain_text("уже изменён")
    expect(page.locator("#delete-document")).to_be_enabled()


def test_result_text_is_not_interpreted_as_html(ui):
    ui.page.evaluate("""() => {
      mockResult.items[0].title = "<img src=x onerror='window.injected=true'>";
      mockResult.items[0].snippet = "<script>window.injected=true</script>";
      mockResult.items[0].sourceUrl = "javascript:alert(1)";
    }""")
    page = ui.ready()
    assert page.locator("#results img, #results script").count() == 0
    assert page.evaluate("window.injected === undefined")
    assert page.locator("#results a").first.get_attribute("href") is None


def test_failed_search_can_be_retried(ui):
    ui.mode("search", "503")
    page = ui.start()
    expect(page.locator("#notice")).to_be_visible()
    ui.mode("search", "normal")
    page.locator("#search-button").click()
    expect(page.locator("#result-summary")).to_have_text("Найдено: 1")
    expect(page.locator("#notice")).to_be_hidden()


def test_form_sends_the_expected_contract(ui):
    page = ui.ready()
    open_form(page)
    page.locator("#save-document").click()
    expect(page.locator("#edit-dialog")).not_to_be_visible()
    submitted = [request["body"] for request in page.evaluate("window.mockRequests")
                 if request["method"] == "POST" and request["path"] == "/api/documents"]
    assert submitted == [{"title": "Новая запись", "text": "Текст новой записи",
                          "category": "JAVA", "sourceUrl": "https://example.org/new"}]


def test_empty_response_schema_is_not_treated_as_success(ui):
    ui.mode("search", "empty")
    page = ui.start()
    expect(page.locator("#notice")).to_contain_text("не соответствует формату")
    expect(page.locator("#search-button")).to_be_enabled()


def test_degraded_response_keeps_results_visible(ui):
    ui.page.evaluate("mockResult.degraded = true")
    page = ui.ready()
    expect(page.locator("#notice")).to_contain_text("справочник недоступен")
    assert page.locator(".card").count() == 1


def defer_documents(page):
    page.evaluate("""() => {
      const original = window.fetch;
      window.pendingDocuments = [];
      window.fetch = (path, options) => path.startsWith('/api/documents/')
        ? new Promise(resolve => pendingDocuments.push(resolve)) : original(path, options);
      window.resolveDocument = (index, title) => pendingDocuments[index](new Response(JSON.stringify({
        id: 'd' + index, title, text: 'Body', category: 'JAVA',
        sourceUrl: 'https://example.org', version: 0
      }), {status: 200}));
    }""")


def test_late_document_response_does_not_replace_the_last_selected_card(ui):
    page = ui.ready()
    defer_documents(page)
    # Synthetic clicks keep both reads pending; the older response arrives last.
    page.locator('.card h3 button').evaluate('(button) => { button.click(); button.click(); }')
    page.evaluate("resolveDocument(1, 'Последний выбор')")
    expect(page.locator('#view-title')).to_have_text('Последний выбор')
    page.evaluate("resolveDocument(0, 'Устаревший ответ')")
    expect(page.locator('#view-title')).to_have_text('Последний выбор')


def test_pending_card_does_not_open_over_the_new_document_form(ui):
    page = ui.ready()
    defer_documents(page)
    page.locator('.card h3 button').click()
    page.locator('#add-document').click()
    page.evaluate("resolveDocument(0, 'Старый запрос')")
    expect(page.locator('#edit-dialog')).to_be_visible()
    expect(page.locator('#view-dialog')).not_to_be_visible()
