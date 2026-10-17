export const LIMITS = Object.freeze({
  file: 1024 * 1024,
  expanded: 8 * 1024 * 1024,
  entry: 2 * 1024 * 1024,
  entries: 128,
  rows: 1000,
  cell: 16 * 1024,
  steps: 200,
  scenarios: 20,
  parseMs: 10000,
  response: 1024 * 1024,
  requestMs: 5000,
  scenarioMs: 60000,
  executionMs: 120000,
  requests: 1000,
});
export function fail(code) {
  throw new Error(code);
}
export function ensure(condition, code) {
  if (!condition) fail(code);
}
export function isRecord(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
export const ID = /^[A-Za-z][A-Za-z0-9_-]{0,63}$/;
