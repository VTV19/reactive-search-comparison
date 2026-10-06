import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';
import { makeBaseline, evaluateResponse } from './response-check.js';

const BASE = __ENV.BASE_URL || 'http://app:8080';
const METADATA = 'http://metadata:9080';
const IMPLEMENTATION = __ENV.IMPLEMENTATION || 'mvc';
const VARIANT = __ENV.VARIANT || IMPLEMENTATION;
const RATE_VALUE = Number(__ENV.RATE || '100');
const DURATION = Number(__ENV.DURATION_SECONDS || '90');
const VUS = Number(__ENV.VUS || '256');
const QUERIES = JSON.parse(open('./queries.json'));
const PHASE = Math.floor(DURATION / 3);
if (PHASE < 20) throw new Error('resilience duration must be at least 60 seconds');

const normalRate = new Rate('resilience_normal_full');
const faultRate = new Rate('resilience_fault_degraded');
const recoveryRate = new Rate('resilience_recovery_full');
const unexpected = new Counter('resilience_unexpected');
const controlErrors = new Counter('resilience_control_errors');
const overload = new Counter('resilience_overloaded');
const normalLatency = new Trend('resilience_normal_ms', true);
const faultLatency = new Trend('resilience_fault_ms', true);
const recoveryLatency = new Trend('resilience_recovery_ms', true);
const toDegraded = new Trend('transition_to_degraded_ms', true);
const toFull = new Trend('transition_to_full_ms', true);

export const options = {
  scenarios: {
    load: {
      executor: 'constant-arrival-rate',
      rate: RATE_VALUE, timeUnit: '1s',
      duration: `${DURATION}s`,
      preAllocatedVUs: VUS, maxVUs: VUS,
      gracefulStop: '10s',
      exec: 'load',
    },
    fault: {
      executor: 'shared-iterations',
      vus: 1, iterations: 1,
      startTime: `${PHASE}s`,
      maxDuration: '10s',
      exec: 'switchToFault',
    },
    recovery: {
      executor: 'shared-iterations',
      vus: 1, iterations: 1,
      startTime: `${PHASE * 2}s`,
      maxDuration: '10s',
      exec: 'switchToNormal',
    },
  },
  thresholds: {
    resilience_normal_full: ['rate>=0.99'],
    resilience_fault_degraded: ['rate>=0.95'],
    resilience_recovery_full: ['rate>=0.99'],
    resilience_unexpected: ['count==0'],
    resilience_control_errors: ['count==0'],
    resilience_overloaded: ['count==0'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
  maxRedirects: 0,
  noConnectionReuse: false,
  noVUConnectionReuse: false,
};

function url(query, enrich = true) {
  const values = { q: query.q, mode: query.mode, sort: 'RELEVANCE',
    limit: '10', offset: '0', enrich: String(enrich) };
  if (query.category) values.category = query.category;
  return `${BASE}/api/search?` + Object.keys(values)
    .map(key => `${encodeURIComponent(key)}=${encodeURIComponent(values[key])}`).join('&');
}

function parse(response) {
  try { return response.json(); } catch (_) { return null; }
}

export function setup() {
  const info = http.get(`${BASE}/api/info`, { timeout: '5s' });
  if (info.status !== 200) throw new Error(`info HTTP ${info.status}`);
  const identity = parse(info);
  if (!identity || identity.implementation !== IMPLEMENTATION) throw new Error('wrong implementation');

  const baselines = QUERIES.map(query => {
    const response = http.get(url(query, true), { timeout: '10s' });
    if (response.status !== 200) throw new Error(`baseline ${query.name}: HTTP ${response.status}`);
    return makeBaseline(parse(response), true);
  });
  return { baselines };
}

export function load(data) {
  const idx = exec.scenario.iterationInTest % QUERIES.length;
  const query = QUERIES[idx];
  const started = Date.now();
  const response = http.get(url(query, true), { timeout: '10s', tags: { name: query.name } });
  const elapsed = Date.now() - started;
  const outcome = evaluateResponse(response, data.baselines[idx], true);

  const sinceStart = (Date.now() - Number(exec.scenario.startTime)) / 1000;
  const grace = 3;

  if (outcome.kind === 'overloaded') overload.add(1);

  if (sinceStart < PHASE - grace) {
    normalRate.add(outcome.full);
    normalLatency.add(elapsed);
    if (!outcome.full) unexpected.add(1);
  } else if (sinceStart >= PHASE + grace && sinceStart < PHASE * 2 - grace) {
    faultRate.add(outcome.degraded);
    faultLatency.add(elapsed);
    if (!outcome.degraded || !outcome.primary) unexpected.add(1);
  } else if (sinceStart >= PHASE * 2 + grace) {
    recoveryRate.add(outcome.full);
    recoveryLatency.add(elapsed);
    if (!outcome.full) unexpected.add(1);
  }
}

function setMode(mode) {
  const response = http.post(`${METADATA}/admin/mode?mode=${mode}`, null, { timeout: '3s' });
  if (response.status !== 200) {
    controlErrors.add(1);
    throw new Error(`metadata mode ${mode}: HTTP ${response.status}`);
  }
}

function probeUntil(data, expected, metric) {
  const query = QUERIES[0];
  const baseline = data.baselines[0];
  const started = Date.now();
  let consecutive = 0;
  for (let attempt = 0; attempt < 60; attempt++) {
    const response = http.get(url(query, true), { timeout: '3s', tags: { name: `control-${expected}` } });
    const outcome = evaluateResponse(response, baseline, true);
    const ok = expected === 'degraded' ? (outcome.degraded && outcome.primary) : outcome.full;
    consecutive = ok ? consecutive + 1 : 0;
    if (consecutive >= 3) {
      metric.add(Date.now() - started);
      return;
    }
    sleep(0.1);
  }
  controlErrors.add(1);
  throw new Error(`transition to ${expected} was not observed`);
}

export function switchToFault(data) {
  setMode('error');
  probeUntil(data, 'degraded', toDegraded);
}

export function switchToNormal(data) {
  setMode('normal');
  probeUntil(data, 'full', toFull);
}

export function teardown() {
  http.post(`${METADATA}/admin/mode?mode=normal`, null, { timeout: '3s' });
}

export function handleSummary(data) {
  const out = {
    implementation: IMPLEMENTATION,
    variant: VARIANT,
    profile: 'resilience',
    rate: RATE_VALUE,
    duration_seconds: DURATION,
    metrics: {
      normal_full: data.metrics.resilience_normal_full?.values || {},
      fault_degraded: data.metrics.resilience_fault_degraded?.values || {},
      recovery_full: data.metrics.resilience_recovery_full?.values || {},
      unexpected: data.metrics.resilience_unexpected?.values || {},
      control_errors: data.metrics.resilience_control_errors?.values || {},
      overloaded: data.metrics.resilience_overloaded?.values || {},
      normal_ms: data.metrics.resilience_normal_ms?.values || {},
      fault_ms: data.metrics.resilience_fault_ms?.values || {},
      recovery_ms: data.metrics.resilience_recovery_ms?.values || {},
      transition_to_degraded_ms: data.metrics.transition_to_degraded_ms?.values || {},
      transition_to_full_ms: data.metrics.transition_to_full_ms?.values || {},
    },
  };
  return {
    '/results/summary.json': JSON.stringify(out, null, 2),
    '/results/summary.txt': JSON.stringify(out, null, 2) + '\n',
    stdout: JSON.stringify(out, null, 2) + '\n',
  };
}
