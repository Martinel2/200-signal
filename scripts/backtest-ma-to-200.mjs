import assert from "node:assert/strict";
import { fetchRows } from "./backtest-regime-strategies.mjs";

const PERIODS = [20, 50, 75, 100, 125, 150, 175];
const pct = (value) => `${(value * 100).toFixed(1)}%`;
const median = (values) => values.toSorted((a, b) => a - b)[Math.floor(values.length / 2)];

export function analyze(rows, period, highLookback) {
  const events = [];
  for (let i = 200; i < rows.length; i += 1) {
    if (rows[i - 1].close < rows[i - 1][`ma${period}`]
      || rows[i].close >= rows[i][`ma${period}`]
      || rows[i].close <= rows[i].ma200) continue;

    const referenceHigh = highLookback
      ? Math.max(...rows.slice(i - highLookback, i).map(({ high }) => high))
      : rows[i].high;
    let outcome = "unresolved";
    let end = null;
    for (let j = i + 1; j < Math.min(i + 253, rows.length); j += 1) {
      if (rows[j].close <= rows[j].ma200) {
        outcome = "ma200";
        end = j;
        break;
      }
      if (rows[j].close > referenceHigh) {
        outcome = "rebound";
        end = j;
        break;
      }
    }
    events.push({
      outcome,
      days: end === null ? null : end - i,
      return: end === null ? null : rows[end].close / rows[i].close - 1
    });
    if (end !== null) i = end;
  }
  return events;
}

function selfCheck() {
  const rows = Array.from({ length: 204 }, () => ({ high: 12, close: 12, ma20: 10, ma200: 8 }));
  rows[200] = { high: 9.5, close: 9, ma20: 10, ma200: 8 };
  rows[201] = { high: 9, close: 8.5, ma20: 9.8, ma200: 8 };
  rows[202] = { high: 9.2, close: 8.8, ma20: 9.6, ma200: 8 };
  rows[203] = { high: 8.2, close: 7.9, ma20: 9.4, ma200: 8 };
  const event = analyze(rows, 20, 0)[0];
  assert.equal(event.outcome, "ma200");
}

async function main() {
  selfCheck();
  const arg = process.argv.indexOf("--as-of");
  const asOf = arg >= 0 ? process.argv[arg + 1] : new Date().toISOString().slice(0, 10);
  assert.match(asOf, /^\d{4}-\d{2}-\d{2}$/);
  const data = Object.fromEntries(await Promise.all(["QQQ", "TQQQ"].map(async (symbol) => [symbol, await fetchRows(symbol, asOf)])));

  for (const [symbol, rows] of Object.entries(data)) {
    console.log(`\n## ${symbol} (${rows[200].date}~${rows.at(-1).date})\n`);
    console.log("Rebound is a confirmed close above the reference high. Episodes do not overlap.\n");
    console.log("| broken MA | events (break-day high) | MA200 before break-day high | events (prior 20d high) | MA200 before prior 20d high | median days to MA200 | median fall after break |");
    console.log("|---:|---:|---:|---:|---:|---:|---:|");
    for (const period of PERIODS) {
      const short = analyze(rows, period, 0).filter(({ outcome }) => outcome !== "unresolved");
      const swing = analyze(rows, period, 20).filter(({ outcome }) => outcome !== "unresolved");
      const hits = swing.filter(({ outcome }) => outcome === "ma200");
      console.log(`| ${period} | ${short.length} | ${pct(short.filter(({ outcome }) => outcome === "ma200").length / short.length)} | ${swing.length} | ${pct(hits.length / swing.length)} | ${median(hits.map(({ days }) => days))}d | ${pct(median(hits.map((event) => event.return)))} |`);
    }
  }
}

if (import.meta.url === new URL(`file://${process.argv[1]}`).href) main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
