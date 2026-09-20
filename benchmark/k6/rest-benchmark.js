import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'https://localhost:8443';
const API_KEY = __ENV.API_KEY || 'benchmark-poc-api-key';
const PRODUTO_ID = __ENV.PRODUTO_ID || '311';
const SCENARIO = __ENV.SCENARIO || 'large'; // 'medium' (~1 month) or 'large' (~12 months)

const DIAS_ATRAS = SCENARIO === 'medium' ? 30 : 380;
const dataFim = new Date();
const dataInicio = new Date(dataFim.getTime() - DIAS_ATRAS * 24 * 60 * 60 * 1000);

const url =
  `${BASE_URL}/produtos/${PRODUTO_ID}/vendas` +
  `?dataInicio=${dataInicio.toISOString()}&dataFim=${dataFim.toISOString()}`;

export const options = {
  insecureSkipTLSVerify: true,
  vus: Number(__ENV.VUS || 20),
  duration: __ENV.DURATION || '30s',
};

export default function () {
  const res = http.get(url, {
    headers: { 'X-Api-Key': API_KEY },
  });
  check(res, {
    'status is 200': (r) => r.status === 200,
  });
}

export function handleSummary(data) {
  const outDir = __ENV.OUT_DIR || 'benchmark/results';
  const outFile = `${outDir}/rest-${SCENARIO}.json`;
  return { [outFile]: JSON.stringify(data, null, 2) };
}
