import { check } from 'k6';
import { primarySignature, referenceSignature, makeBaseline, evaluateResponse } from './response-check.js';

export const options = {
    vus: 1, iterations: 1,
    thresholds: { checks: ['rate==1'] },
};

function body(enrich = true) {
    return {
        query: 'java', total: 2, limit: 10, offset: 0, degraded: false,
        items: [
            { id: 'd001', title: 'Java', snippet: 'First', category: 'JAVA',
                sourceUrl: 'https://example.org/java', score: 3,
                reference: enrich ? { title: 'Java docs', url: 'https://example.org/docs/java' } : null },
            { id: 'd002', title: 'Spring', snippet: 'Second', category: 'SPRING',
                sourceUrl: 'https://example.org/spring', score: 1.5,
                reference: enrich ? { title: 'Spring docs', url: 'https://example.org/docs/spring' } : null },
        ],
    };
}

function response(value, status = 200) {
    return { status, json: () => value };
}
function reorder(value) {
    if (Array.isArray(value)) return value.map(reorder);
    if (!value || typeof value !== 'object') return value;
    const copy = {};
    Object.keys(value).reverse().forEach(key => { copy[key] = reorder(value[key]); });
    return copy;
}
function mustThrow(callback) {
    try { callback(); return false; } catch (_) { return true; }
}
function changed(edit) {
    const value = body();
    edit(value);
    return value;
}

export default function () {
    const base = makeBaseline(body(), true);
    const plain = makeBaseline(body(false), false);
    const classify = value => evaluateResponse(response(value), base, true);
    const cases = [
        ['complete enriched response', () => classify(body()).full],
        ['complete un-enriched response', () => evaluateResponse(response(body(false)), plain, false).full],
        ['object property order is irrelevant', () => classify(reorder(body())).full],
        ['primary key order is irrelevant', () => primarySignature(body()) === primarySignature(reorder(body()))],
        ['reference key order is irrelevant', () => referenceSignature(body()) === referenceSignature(reorder(body()))],
        ['literal string comparison reproduces the old defect', () =>
            JSON.stringify(body().items.map(i => i.reference)) !==
            JSON.stringify(reorder(body()).items.map(i => i.reference))],
        ['changed reference URL is rejected', () => classify(changed(b => b.items[0].reference.url += '/bad')).kind === 'contract'],
        ['changed reference title is rejected', () => classify(changed(b => b.items[0].reference.title += 'bad')).kind === 'contract'],
        ['changed enrichment preserves primary classification', () => classify(changed(b => b.items[0].reference.url += '/bad')).primary],
        ['missing reference is rejected', () => classify(changed(b => delete b.items[0].reference)).reason === 'invalid_reference'],
        ['null reference in complete result is rejected', () => classify(changed(b => b.items[0].reference = null)).kind === 'contract'],
        ['missing reference title is rejected', () => classify(changed(b => delete b.items[0].reference.title)).kind === 'contract'],
        ['empty reference title is rejected', () => classify(changed(b => b.items[0].reference.title = ' ')).kind === 'contract'],
        ['nonstring URL is rejected', () => classify(changed(b => b.items[0].reference.url = 5)).kind === 'contract'],
        ['relative reference URL is rejected', () => classify(changed(b => b.items[0].reference.url = '/docs')).kind === 'contract'],
        ['changed result order is rejected', () => classify(changed(b => b.items.reverse())).reason === 'primary_mismatch'],
        ['changed document id is rejected', () => classify(changed(b => b.items[0].id = 'wrong')).reason === 'primary_mismatch'],
        ['changed document title is rejected', () => classify(changed(b => b.items[0].title = 'wrong')).reason === 'primary_mismatch'],
        ['changed snippet is rejected', () => classify(changed(b => b.items[0].snippet = 'wrong')).reason === 'primary_mismatch'],
        ['changed category is rejected', () => classify(changed(b => b.items[0].category = 'OTHER')).reason === 'primary_mismatch'],
        ['changed source URL is rejected', () => classify(changed(b => b.items[0].sourceUrl += '/bad')).reason === 'primary_mismatch'],
        ['changed score is rejected', () => classify(changed(b => b.items[0].score += 0.1)).reason === 'primary_mismatch'],
        ['changed total is rejected', () => classify(changed(b => b.total += 1)).reason === 'primary_mismatch'],
        ['changed query is rejected', () => classify(changed(b => b.query = 'spring')).reason === 'primary_mismatch'],
        ['changed limit is rejected', () => classify(changed(b => b.limit = 5)).reason === 'primary_mismatch'],
        ['changed offset is rejected', () => classify(changed(b => b.offset = 1)).reason === 'primary_mismatch'],
        ['missing hit is rejected', () => classify(changed(b => b.items.pop())).reason === 'primary_mismatch'],
        ['missing items is rejected', () => classify(changed(b => delete b.items)).kind === 'contract'],
        ['nonarray items is rejected', () => classify(changed(b => b.items = {})).kind === 'contract'],
        ['null body is rejected', () => classify(null).reason === 'invalid_shape'],
        ['invalid JSON is rejected', () => evaluateResponse({status: 200, json: () => {throw new Error('JSON');}}, base, true).reason === 'invalid_json'],
        ['nonboolean degraded flag is rejected', () => classify(changed(b => b.degraded = 'false')).kind === 'contract'],
        ['honest degradation retains primary result', () => {
            const b = body(false); b.degraded = true;
            const r = classify(b);
            return r.primary && r.degraded && !r.full;
        }],
        ['degradation is invalid when enrichment is disabled', () => {
            const b = body(false); b.degraded = true;
            return evaluateResponse(response(b), plain, false).kind === 'contract';
        }],
        ['degraded response with references is rejected', () => classify(changed(b => b.degraded = true)).reason === 'invalid_degradation'],
        ['503 OVERLOADED is distinguished', () => evaluateResponse(response({code:'OVERLOADED'},503),base,true).kind === 'overloaded'],
        ['503 DATABASE_UNAVAILABLE is an HTTP error', () => evaluateResponse(response({code:'DATABASE_UNAVAILABLE'},503),base,true).kind === 'http'],
        ['non-JSON 503 is an HTTP error', () => evaluateResponse({status:503,json:()=>{throw new Error('JSON');}},base,true).kind === 'http'],
        ['HTTP 500 is not a full response', () => evaluateResponse(response(body(),500),base,true).kind === 'http'],
        ['transport error is distinguished', () => evaluateResponse(response(null,0),base,true).kind === 'transport'],
        ['enriched baseline requires references', () => mustThrow(() => makeBaseline(body(false),true))],
        ['plain baseline rejects references', () => mustThrow(() => makeBaseline(body(),false))],
        ['degraded baseline is rejected', () => mustThrow(() => makeBaseline(changed(b => b.degraded = true),true))],
        ['empty baseline is rejected', () => mustThrow(() => makeBaseline(changed(b => b.items = []),true))],
    ];
    let passed = 0;
    cases.forEach(([name, test]) => {
        let ok = false;
        try { ok = test() === true; } catch (_) { ok = false; }
        if (check(ok, { [name]: value => value === true })) passed++;
    });
    console.log(`Response classification: ${passed}/${cases.length}`);
}
