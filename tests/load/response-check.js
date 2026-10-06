// Сравнение полей ответа не зависит от порядка свойств JSON-объектов.
// Порядок материалов в массиве, значения баллов и ссылки остаются значимыми.

export function primarySignature(body) {
    if (!body || typeof body !== 'object' || !Array.isArray(body.items)) {
        throw new Error('Invalid search response');
    }
    return JSON.stringify({
        query: body.query, total: body.total, limit: body.limit, offset: body.offset,
        items: body.items.map(item => ({
            id: item.id, title: item.title, snippet: item.snippet,
            category: item.category, sourceUrl: item.sourceUrl, score: item.score,
        })),
    });
}

export function referenceSignature(body) {
    return JSON.stringify(body.items.map(item => {
        const reference = item.reference;
        if (reference === null) return null;
        if (!reference || typeof reference !== 'object'
                || typeof reference.title !== 'string' || !reference.title.trim()
                || typeof reference.url !== 'string' || !/^https?:\/\//.test(reference.url)) {
            throw new Error('Invalid reference');
        }
        // Объект создаётся заново в известном порядке; исходный объект не изменяется.
        return { title: reference.title, url: reference.url };
    }));
}

export function makeBaseline(body, enrich) {
    if (!body || body.degraded !== false || !Array.isArray(body.items) || !body.items.length) {
        throw new Error('Preflight requires a nonempty complete response');
    }
    const signature = primarySignature(body);
    const references = referenceSignature(body);
    if (body.items.some(item => enrich ? item.reference === null : item.reference !== null)) {
        throw new Error('Preflight enrichment does not match the requested profile');
    }
    return { signature, references };
}

function result(kind, reason, primary = false) {
    return { kind, reason, primary, full: kind === 'full', degraded: kind === 'degraded' };
}

export function evaluateResponse(response, baseline, enrich) {
    if (response.status === 0) return result('transport', 'transport');
    if (response.status !== 200) {
        if (response.status === 503) {
            try {
                if (response.json().code === 'OVERLOADED') {
                    return result('overloaded', 'overloaded');
                }
            } catch (_) { /* Неуспешный HTTP-ответ не обязан содержать JSON. */ }
        }
        return result('http', `http_${response.status}`);
    }

    let body;
    try { body = response.json(); }
    catch (_) { return result('contract', 'invalid_json'); }

    try {
        if (!body || typeof body.degraded !== 'boolean') {
            return result('contract', 'invalid_shape');
        }
        if (primarySignature(body) !== baseline.signature) {
            return result('contract', 'primary_mismatch');
        }
        if (body.degraded) {
            if (!enrich || body.items.some(item => item.reference !== null)) {
                return result('contract', 'invalid_degradation', true);
            }
            return result('degraded', 'metadata_unavailable', true);
        }
        let references;
        try { references = referenceSignature(body); }
        catch (_) { return result('contract', 'invalid_reference', true); }
        if (references !== baseline.references) {
            return result('contract', 'reference_mismatch', true);
        }
        return result('full', 'ok', true);
    } catch (_) {
        return result('contract', 'invalid_shape');
    }
}
