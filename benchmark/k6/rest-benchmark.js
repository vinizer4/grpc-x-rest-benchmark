import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'https://localhost:8443';
const API_KEY = __ENV.API_KEY || 'benchmark-poc-api-key';
const STORE_COUNT = Number(__ENV.STORE_COUNT || 50);
const SCENARIO = __ENV.SCENARIO || 'large'; // 'medium' (~3 months) or 'large' (~12 months)
const PROFILE = __ENV.PROFILE || 'baseline'; // baseline|same-az|cross-az|cross-region, for output naming only

const MONTHS_BACK = SCENARIO === 'medium' ? 3 : 12;
const endDate = new Date();
const startDate = new Date(endDate);
startDate.setMonth(startDate.getMonth() - MONTHS_BACK);

const toDateOnly = (d) => d.toISOString().slice(0, 10);
const query = `startDate=${toDateOnly(startDate)}&endDate=${toDateOnly(endDate)}`;

export const options = {
  insecureSkipTLSVerify: true,
  vus: Number(__ENV.VUS || 20),
  duration: __ENV.DURATION || '30s',
  gracefulStop: '90s',
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

// Each request targets a different, randomly picked store, matching how
// production traffic actually spreads across stores rather than hammering
// a single one.
export default function () {
  const storeId = 1 + Math.floor(Math.random() * STORE_COUNT);
  const url = `${BASE_URL}/stores/${storeId}/annual-history?${query}`;
  const res = http.get(url, {
    headers: { 'X-Api-Key': API_KEY },
    timeout: '90s',
  });
  check(res, {
    'status is 200': (r) => r.status === 200,
  });
}

export function handleSummary(data) {
  const outDir = __ENV.OUT_DIR || 'benchmark/results';
  const outFile = `${outDir}/rest-${SCENARIO}-${PROFILE}.json`;
  return { [outFile]: JSON.stringify(data, null, 2) };
}
