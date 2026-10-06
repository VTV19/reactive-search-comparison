import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';
import { primarySignature, makeBaseline, evaluateResponse } from './response-check.js';

const LOAD_TEST_REVISION = 2;

const BASE = __ENV.BASE_URL || 'http://app:8080';
const PROFILE = __ENV.PROFILE || 'database';
const IMPLEMENTATION = __ENV.IMPLEMENTATION || 'mvc';
const VARIANT = __ENV.VARIANT || IMPLEMENTATION;
const QUERIES = JSON.parse(open('./queries.json'));

function integer(name, fallback, min, max) {
    const raw = __ENV[name] || String(fallback);
    if (!/^\d+$/.test(raw)) throw new Error(`${name}: expected an integer`);
    const value = Number(raw);
    if (value < min || value > max) throw new Error(`${name}: expected ${min}..${max}`);
    return value;
}
const RATE = integer('RATE', 20, 1, 3000);
const WARMUP = integer('WARMUP_SECONDS', 60, 1, 600);
const DURATION = integer('DURATION_SECONDS', 180, 5, 3600);
const VUS = integer('VUS', 128, 1, 2048);
const P95 = integer('P95_MS', 1000, 1, 10000);
if (!['database', 'http', 'delayed'].includes(PROFILE)) throw new Error('Unknown PROFILE');
if (!['mvc', 'webflux'].includes(IMPLEMENTATION)) throw new Error('Unknown IMPLEMENTATION');

const ENRICH = PROFILE !== 'database';
const attempts = new Counter('load_attempts');
const primaryCount = new Counter('load_primary');
const fullCount = new Counter('load_full');
const degradedCount = new Counter('load_degraded');
const overloadCount = new Counter('load_overloaded');
const errorCount = new Counter('load_errors');
const contractErrorCount = new Counter('load_contract_errors');
const httpErrorCount = new Counter('load_http_errors');
const transportErrorCount = new Counter('load_transport_errors');
const warmupAttempts = new Counter('load_warmup_attempts');
const warmupFull = new Counter('load_warmup_full');
const warmupDegraded = new Counter('load_warmup_degraded');
const warmupOverloaded = new Counter('load_warmup_overloaded');
const warmupErrors = new Counter('load_warmup_errors');
// k6 изолирует состояние VU. Ограничиваем диагностику одним примером на VU.
let contractLogged = false;
const fullRate = new Rate('load_full_rate');
const allLatency = new Trend('load_all_ms', true);
const fullLatency = new Trend('load_full_ms', true);
const failureLatency = new Trend('load_failure_ms', true);
// Границы измерения: плановое начало фазы и последнее завершение запроса.
const clock = new Trend('load_clock_ms');

export const options = {
    scenarios: {
        warmup: {
            executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s',
            duration: `${WARMUP}s`, preAllocatedVUs: VUS, maxVUs: VUS, gracefulStop: '12s',
        },
        measurement: {
            executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s',
            startTime: `${WARMUP + 12}s`, duration: `${DURATION}s`,
            preAllocatedVUs: VUS, maxVUs: VUS, gracefulStop: '12s',
        },
    },
    thresholds: {
        load_attempts: ['count>0'],
        load_full_rate: ['rate>=0.99'],
        load_contract_errors: ['count==0'],
        load_full_ms: [`p(95)<${P95}`],
        'dropped_iterations{scenario:measurement}': ['count==0'],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
    systemTags: ['status', 'method', 'name', 'scenario', 'expected_response', 'error_code'],
    setupTimeout: '60s',
    teardownTimeout: '60s',
    noConnectionReuse: false,
    noVUConnectionReuse: false,
    maxRedirects: 0,
    userAgent: 'java-search-load-test',
};

function url(query, enrich) {
    const values = { q: query.q, mode: query.mode, sort: 'RELEVANCE',
        limit: '10', offset: '0', enrich: String(enrich) };
    if (query.category) values.category = query.category;
    return `${BASE}/api/search?` + Object.keys(values)
        .map(key => `${encodeURIComponent(key)}=${encodeURIComponent(values[key])}`).join('&');
}
function getJson(address, name) {
    const response = http.get(address, { timeout: '10s', tags: { name } });
    if (response.status !== 200) throw new Error(`${name}: HTTP ${response.status}`);
    return response.json();
}

export function setup() {
    const info = getJson(`${BASE}/api/info`, 'control-info');
    if (info.implementation !== IMPLEMENTATION) throw new Error('Wrong application at BASE_URL');
    const baselines = QUERIES.map(query => {
        const body = getJson(url(query, ENRICH), `control-${query.name}`);
        return makeBaseline(body, ENRICH);
    });
    console.log(JSON.stringify({ event: 'baseline', load_test_revision: LOAD_TEST_REVISION,
        implementation: IMPLEMENTATION, variant: VARIANT,
        version: info.version, profile: PROFILE, queries: QUERIES, baselines }));
    return { baselines };
}

export default function (data) {
    const index = exec.scenario.iterationInTest % QUERIES.length;
    const query = QUERIES[index];
    const started = Date.now();
    const response = http.get(url(query, ENRICH),
        { timeout: '10s', tags: { name: query.name } });
    const finished = Date.now();
    const outcome = evaluateResponse(response, data.baselines[index], ENRICH);
    const tags = { query: query.name, outcome: outcome.kind, reason: outcome.reason };

    if (exec.scenario.name !== 'measurement') {
        warmupAttempts.add(1);
        warmupFull.add(outcome.full ? 1 : 0);
        warmupDegraded.add(outcome.degraded ? 1 : 0);
        warmupOverloaded.add(outcome.kind === 'overloaded' ? 1 : 0);
        warmupErrors.add(outcome.full || outcome.degraded ? 0 : 1, tags);
        return;
    }

    attempts.add(1);
    primaryCount.add(outcome.primary ? 1 : 0);
    fullCount.add(outcome.full ? 1 : 0);
    degradedCount.add(outcome.degraded ? 1 : 0);
    overloadCount.add(outcome.kind === 'overloaded' ? 1 : 0);
    errorCount.add(outcome.full || outcome.degraded ? 0 : 1, tags);
    contractErrorCount.add(outcome.kind === 'contract' ? 1 : 0, tags);
    httpErrorCount.add(outcome.kind === 'http' ? 1 : 0);
    transportErrorCount.add(outcome.kind === 'transport' ? 1 : 0);
    fullRate.add(outcome.full);
    allLatency.add(finished - started, tags);
    clock.add(exec.scenario.startTime);
    clock.add(finished);
    if (outcome.full) fullLatency.add(finished - started);
    else failureLatency.add(finished - started, tags);

    if (outcome.kind === 'contract' && !contractLogged) {
        contractLogged = true;
        const raw = typeof response.body === 'string' ? response.body : '';
        console.warn(JSON.stringify({
            event: 'contract_mismatch', query: query.name, reason: outcome.reason,
            status: response.status, primary_matches: outcome.primary,
            body: raw.slice(0, 4096), body_truncated: raw.length > 4096,
        }));
    }
}

export function teardown(data) {
    QUERIES.forEach((query, index) => {
        const body = getJson(url(query, false), `postflight-${query.name}`);
        if (primarySignature(body) !== data.baselines[index].signature) {
            throw new Error(`Search changed during test: ${query.name}`);
        }
    });
}

export function handleSummary(data) {
    const values = name => data.metrics[name] ? data.metrics[name].values : {};
    const count = name => values(name).count || 0;
    const times = values('load_clock_ms');
    const observed = times.max > times.min ? (times.max - times.min) / 1000 : null;
    // Редкие или быстрые ответы не сокращают плановое окно и не завышают RPS.
    const elapsed = observed === null ? null : Math.max(DURATION, observed);
    const thresholds = Object.values(data.metrics).flatMap(metric =>
        Object.values(metric.thresholds || {}).map(threshold => threshold.ok));
    const result = {
        load_test_revision: LOAD_TEST_REVISION,
        implementation: IMPLEMENTATION, variant: VARIANT, profile: PROFILE,
        parameters: { rate: RATE, warmup_seconds: WARMUP, duration_seconds: DURATION,
            preallocated_vus: VUS, p95_limit_ms: P95, queries: QUERIES },
        warmup: {
            attempts: count('load_warmup_attempts'), full: count('load_warmup_full'),
            degraded: count('load_warmup_degraded'), overloaded: count('load_warmup_overloaded'),
            errors: count('load_warmup_errors'),
        },
        measurement: {
            attempts: count('load_attempts'), primary: count('load_primary'),
            full: count('load_full'), degraded: count('load_degraded'),
            overloaded: count('load_overloaded'), errors: count('load_errors'),
            contract_errors: count('load_contract_errors'),
            http_errors: count('load_http_errors'), transport_errors: count('load_transport_errors'),
            scheduled_seconds: DURATION,
            observed_span_seconds: observed,
            observed_seconds_including_drain: elapsed,
            full_rps: elapsed ? count('load_full') / elapsed : null,
            all_ms: values('load_all_ms'), full_ms: values('load_full_ms'),
            failure_ms: values('load_failure_ms'),
            dropped: count('dropped_iterations{scenario:measurement}'),
        },
        thresholds_passed: thresholds.length > 0 && thresholds.every(Boolean),
        k6: data,
    };
    const text = JSON.stringify({
        load_test_revision: LOAD_TEST_REVISION,
        implementation: result.implementation, variant: VARIANT, profile: PROFILE,
        warmup: result.warmup,
        ...result.measurement, thresholds_passed: result.thresholds_passed,
    }, null, 2) + '\n';
    return {
        '/results/summary.json': JSON.stringify(result, null, 2),
        '/results/summary.txt': text,
        stdout: text,
    };
}
