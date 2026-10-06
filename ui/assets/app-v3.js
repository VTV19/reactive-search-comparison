(() => {
  'use strict';

  const $ = id => {
    const element = document.getElementById(id);
    if (!element) throw new Error(`Missing interface element: ${id}`);
    return element;
  };
  const categoryNames = {JAVA: 'Java', SPRING: 'Spring', REACTOR: 'Reactor',
    MONGODB: 'MongoDB', DOCKER: 'Docker', TESTING: 'Тестирование'};
  const expected = document.documentElement.dataset;
  let offset = 0;
  let total = 0;
  let pageSize = 10;
  let previousCriteria = "";
  let searchController = null;
  let documentController = null;
  let opened = null;
  let editing = null;
  let saving = false;
  let deleting = false;

  class ApiError extends Error {
    constructor(message, status = 0) { super(message); this.status = status; }
  }

  async function api(path, options = {}) {
    const {signal, timeout = 10000, ...init} = options;
    const changed = init.method && init.method !== 'GET';
    const controller = new AbortController();
    const forwardAbort = () => controller.abort();
    let timedOut = false;
    if (signal && signal.aborted) throw new DOMException('Cancelled', 'AbortError');
    if (signal) signal.addEventListener('abort', forwardAbort, {once: true});
    const timer = setTimeout(() => { timedOut = true; controller.abort(); }, timeout);
    try {
      const response = await fetch(path, {...init, cache: 'no-store', signal: controller.signal});
      if (response.status === 204) return null;
      const text = await response.text();
      let body;
      try { body = JSON.parse(text); }
      catch (_) { throw new ApiError(`Сервер вернул некорректный ответ (HTTP ${response.status}).`, response.status); }
      if (!response.ok) {
        const fields = body && body.fields ? Object.values(body.fields).join('. ') : '';
        throw new ApiError(fields || (body && body.message) || `Ошибка HTTP ${response.status}`, response.status);
      }
      return body;
    } catch (error) {
      if (timedOut) {
        throw new ApiError(changed
          ? 'Время ожидания истекло. Изменение могло сохраниться: обновите каталог перед повторной отправкой.'
          : 'Время ожидания ответа истекло. Проверьте состояние сервера и повторите запрос.');
      }
      if (signal && signal.aborted) throw new DOMException('Cancelled', 'AbortError');
      if (error instanceof ApiError) throw error;
      throw new ApiError(changed
        ? 'Соединение прервано. Изменение могло сохраниться: обновите каталог перед повторной отправкой.'
        : 'Нет соединения с приложением. Проверьте, что сервер запущен.');
    } finally {
      clearTimeout(timer);
      if (signal) signal.removeEventListener('abort', forwardAbort);
    }
  }

  function notice(message, error = false) {
    $('notice').textContent = message;
    $('notice').className = error ? 'notice error' : 'notice';
    $('notice').hidden = !message;
  }

  function safeUrl(value) {
    try {
      const url = new URL(value);
      return url.protocol === 'https:' && !url.username && !url.password ? url.href : null;
    } catch (_) { return null; }
  }

  function link(title, value) {
    const anchor = document.createElement('a');
    anchor.textContent = title;
    const url = safeUrl(value);
    if (url) { anchor.href = url; anchor.target = '_blank'; anchor.rel = 'noopener noreferrer'; }
    return anchor;
  }

  function render(result) {
    if (!result || !Array.isArray(result.items) || !Number.isFinite(result.total))
      throw new ApiError('Ответ сервера не соответствует формату поисковой выдачи.');
    total = result.total;
    pageSize = result.limit;
    offset = result.offset;
    const cards = [];
    for (const hit of result.items) {
      const card = document.createElement('article');
      card.className = 'card';
      const heading = document.createElement('h3');
      const button = document.createElement('button');
      button.type = 'button';
      button.textContent = hit.title;
      button.addEventListener('click', () => openDocument(hit.id));
      heading.append(button);
      const meta = document.createElement('p');
      meta.className = 'card-meta';
      meta.textContent = `${categoryNames[hit.category] || hit.category} · ${hit.id}` +
        (Number.isFinite(hit.score) ? ` · Релевантность: ${hit.score.toFixed(2)}` : '');
      const text = document.createElement('p');
      text.textContent = hit.snippet;
      const references = document.createElement('div');
      references.className = 'references';
      references.append(link('Источник материала', hit.sourceUrl));
      if (hit.reference) references.append(document.createTextNode(' · '), link(hit.reference.title, hit.reference.url));
      card.append(heading, meta, text, references);
      cards.push(card);
    }
    $('results').replaceChildren(...cards);
    if (!total) {
      const empty = document.createElement('p');
      empty.textContent = 'Совпадений нет. Измените запрос или категорию.';
      $('results').append(empty);
    }
    $('result-summary').textContent = `Найдено: ${total}`;
    $('page-label').textContent = result.items.length
      ? `${offset + 1}–${offset + result.items.length} из ${total}` : 'Нет результатов на этой странице';
    $('previous').disabled = offset === 0;
    $('next').disabled = offset + pageSize >= total || offset + pageSize > 10000;
    notice(result.degraded
      ? 'Дополнительный справочник недоступен. Найденные материалы и их источники доступны.' : '');
  }

  async function search(reset = false) {
    cancelDocumentLoad();
    const criteria = JSON.stringify(['query', 'category', 'mode', 'sort', 'limit']
      .map(id => $(id).value).concat($('enrich').checked));
    if (reset || criteria !== previousCriteria) offset = 0;
    previousCriteria = criteria;
    if (searchController) searchController.abort();
    const current = new AbortController();
    searchController = current;
    const params = new URLSearchParams({
      q: $('query').value, mode: $('mode').value, sort: $('sort').value,
      limit: $('limit').value, offset: String(offset), enrich: String($('enrich').checked)
    });
    if ($('category').value) params.set('category', $('category').value);
    $('search-button').disabled = true;
    $('previous').disabled = true;
    $('next').disabled = true;
    $('result-summary').textContent = 'Поиск…';
    notice('');
    try {
      const result = await api('/api/search?' + params, {signal: current.signal});
      if (searchController === current) render(result);
    } catch (error) {
      if (error.name !== 'AbortError' && searchController === current) {
        $('results').replaceChildren();
        $('result-summary').textContent = 'Поиск не выполнен';
        $('page-label').textContent = '';
        notice(error.message, true);
      }
    } finally {
      if (searchController === current) $('search-button').disabled = false;
    }
  }

  async function connect() {
    $('retry-connect').disabled = true;
    $('connection-status').textContent = 'Проверка соединения…';
    try {
      const info = await api('/api/info', {timeout: 5000});
      if (!info || info.version !== expected.version || info.implementation !== expected.implementation)
        throw new ApiError('Версии страницы и сервера различаются. Перезапустите приложение и обновите страницу.');
      $('startup-error').hidden = true;
      $('connection-status').textContent = `Соединение установлено · ${info.application}`;
    } catch (error) {
      $('connection-status').textContent = error.message;
    } finally {
      $('retry-connect').disabled = false;
    }
  }

  async function openDocument(id) {
    cancelDocumentLoad();
    const current = new AbortController();
    documentController = current;
    try {
      const value = await api('/api/documents/' + encodeURIComponent(id), {signal: current.signal});
      if (documentController !== current) return;
      opened = value;
      $('view-title').textContent = value.title;
      $('view-details').textContent = `${categoryNames[value.category]} · ${value.id} · Версия ${value.version}`;
      $('view-text').textContent = value.text;
      $('view-source').removeAttribute('href');
      const url = safeUrl(value.sourceUrl);
      if (url) $('view-source').href = url;
      $('view-error').hidden = true;
      if (!$('view-dialog').open) $('view-dialog').showModal();
    } catch (error) {
      if (error.name !== 'AbortError' && documentController === current) notice(error.message, true);
    } finally {
      if (documentController === current) documentController = null;
    }
  }

  function cancelDocumentLoad() {
    if (documentController) documentController.abort();
    documentController = null;
  }

  function openEditor(value) {
    cancelDocumentLoad();
    editing = value;
    $('edit-heading').textContent = value ? 'Редактирование материала' : 'Новый материал';
    $('edit-title').value = value ? value.title : '';
    $('edit-text').value = value ? value.text : '';
    $('edit-category').value = value ? value.category : 'JAVA';
    $('edit-source').value = value ? value.sourceUrl : '';
    $('edit-error').hidden = true;
    $('edit-dialog').showModal();
  }

  async function saveDocument(event) {
    event.preventDefault();
    if (saving) return;
    saving = true;
    $('save-document').disabled = true;
    $('close-edit').disabled = true;
    $('edit-error').hidden = true;
    const body = {title: $('edit-title').value, text: $('edit-text').value,
      category: $('edit-category').value, sourceUrl: $('edit-source').value};
    if (editing) body.version = editing.version;
    try {
      await api(editing ? '/api/documents/' + encodeURIComponent(editing.id) : '/api/documents', {
        method: editing ? 'PUT' : 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)
      });
      $('edit-dialog').close();
      await search(true);
    } catch (error) {
      $('edit-error').textContent = error.message;
      $('edit-error').hidden = false;
    } finally {
      saving = false;
      $('save-document').disabled = false;
      $('close-edit').disabled = false;
    }
  }

  async function deleteDocument() {
    const value = opened;
    if (deleting || !value || !confirm(`Удалить материал «${value.title}»?`)) return;
    deleting = true;
    $('delete-document').disabled = true;
    $('edit-document').disabled = true;
    $('close-view').disabled = true;
    $('view-error').hidden = true;
    try {
      await api(`/api/documents/${encodeURIComponent(value.id)}?version=${value.version}`, {method: 'DELETE'});
      $('view-dialog').close();
      await search(true);
    } catch (error) {
      $('view-error').textContent = error.message;
      $('view-error').hidden = false;
    } finally {
      deleting = false;
      $('delete-document').disabled = false;
      $('edit-document').disabled = false;
      $('close-view').disabled = false;
    }
  }

  async function refreshStatus() {
    $('refresh-status').disabled = true;
    $('health').textContent = 'Обновление состояния…';
    $('metrics').replaceChildren();
    try {
      await api('/actuator/health/readiness', {timeout: 5000});
      $('health').textContent = 'Приложение и MongoDB готовы к работе';
      const meters = [['search.requests', 'Запросов'], ['search.degraded', 'Неполных ответов'],
        ['search.rejected', 'Отказов перегрузки'], ['search.active', 'Активных поисков']];
      const values = await Promise.all(meters.map(async ([name, title]) => {
        const result = await api('/actuator/metrics/' + name, {timeout: 5000});
        return [title, result.measurements[0].value];
      }));
      for (const [title, value] of values) {
        const group = document.createElement('div');
        const label = document.createElement('dt');
        const number = document.createElement('dd');
        label.textContent = title;
        number.textContent = String(value);
        group.append(label, number);
        $('metrics').append(group);
      }
    } catch (error) { $('health').textContent = 'Состояние недоступно: ' + error.message; }
    finally { $('refresh-status').disabled = false; }
  }

  for (const [value, title] of Object.entries(categoryNames)) {
    for (const id of ['category', 'edit-category']) {
      const option = document.createElement('option');
      option.value = value;
      option.textContent = title;
      $(id).append(option);
    }
  }
  $('search-form').addEventListener('submit', event => { event.preventDefault(); search(true); });
  $('reset-search').addEventListener('click', () => {
    $('query').value = ''; $('category').value = ''; $('mode').value = 'ANY';
    $('sort').value = 'RELEVANCE'; $('limit').value = '10'; search(true);
  });
  for (const id of ['category', 'mode', 'sort', 'limit', 'enrich'])
    $(id).addEventListener('change', () => search(true));
  $('previous').addEventListener('click', () => { offset = Math.max(0, offset - pageSize); search(); });
  $('next').addEventListener('click', () => { offset += pageSize; search(); });
  $('add-document').addEventListener('click', () => openEditor(null));
  $('close-view').addEventListener('click', () => $('view-dialog').close());
  $('close-edit').addEventListener('click', () => $('edit-dialog').close());
  $('view-dialog').addEventListener('cancel', event => { if (deleting) event.preventDefault(); });
  $('edit-dialog').addEventListener('cancel', event => { if (saving) event.preventDefault(); });
  $('edit-document').addEventListener('click', () => { $('view-dialog').close(); openEditor(opened); });
  $('edit-form').addEventListener('submit', saveDocument);
  $('delete-document').addEventListener('click', deleteDocument);
  $('refresh-status').addEventListener('click', refreshStatus);
  $('retry-connect').addEventListener('click', () => { connect(); search(true); });

  for (const id of ['add-document', 'reset-search', 'search-button', 'retry-connect']) $(id).disabled = false;
  window.catalogUiStarted = true;
  clearTimeout(window.catalogStartupTimer);
  // Catalogue loading is independent of the diagnostic /api/info request.
  connect();
  search(true);
})();
