import assert from "node:assert/strict";
import { fetchRows } from "./backtest-regime-strategies.mjs";

const PERIODS = [20, 50, 75, 100, 125, 150, 175];
const pct = (value) => `${(value * 100).toFixed(1)}%`;
const median = (values) => values.toSorted((a, b) => a - b)[Math.floor(values.length / 2)];

export function analyze(rows, period) {
  const events = [];
  let inCycle = false;
  let cycleHigh = null;
  let pending = null;

  for (let i = 200; i < rows.length; i += 1) {
    if (!inCycle) {
      if (rows[i].close > rows[i].ma200) {
        inCycle = true;
        cycleHigh = rows[i].high;
      }
      continue;
    }

    if (rows[i].close <= rows[i].ma200) {
      if (pending) events.push({
        outcome: "ma200",
        startDate: rows[pending.start].date,
        days: i - pending.start,
        return: rows[i].close / rows[pending.start].close - 1
      });
      inCycle = false;
      cycleHigh = null;
      pending = null;
      continue;
    }

    if (pending && rows[i].high > pending.high) {
      events.push({ outcome: "newHigh", startDate: rows[pending.start].date, days: i - pending.start, return: rows[i].close / rows[pending.start].close - 1 });
      pending = null;
    }
    cycleHigh = Math.max(cycleHigh, rows[i].high);

    if (!pending
      && rows[i - 1].close >= rows[i - 1][`ma${period}`]
      && rows[i].close < rows[i][`ma${period}`]) {
      pending = { start: i, high: cycleHigh };
    }
  }

  return events;
}

function selfCheck() {
  const rows = Array.from({ length: 204 }, () => ({ high: 7, close: 7, ma20: 8, ma200: 8 }));
  rows[200] = { high: 11, close: 10, ma20: 9, ma200: 8 };
  rows[201] = { high: 10, close: 9, ma20: 9.5, ma200: 8 };
  rows[202] = { high: 10.5, close: 8.5, ma20: 9.4, ma200: 8 };
  rows[203] = { high: 8.2, close: 7.9, ma20: 9.2, ma200: 8 };
  assert.equal(analyze(rows, 20)[0].outcome, "ma200");
}

async function main() {
  selfCheck();
  const arg = process.argv.indexOf("--as-of");
  const asOf = arg >= 0 ? process.argv[arg + 1] : new Date().toISOString().slice(0, 10);
  assert.match(asOf, /^\d{4}-\d{2}-\d{2}$/);
  const data = Object.fromEntries(await Promise.all(["QQQ", "TQQQ", "VOO", "SPYM"].map(async (symbol) => [symbol, await fetchRows(symbol, asOf)])));

  for (const [symbol, rows] of Object.entries(data)) {
    console.log(`\n## ${symbol} (${rows[200].date}~${rows.at(-1).date})\n`);
    console.log("Cycle begins above MA200. Rebound means an intraday high above the cycle high that existed at the MA breakdown.\n");
    console.log("| broken MA | resolved episodes | new cycle high first | MA200 first | median days to MA200 | median fall after break |");
    console.log("|---:|---:|---:|---:|---:|---:|");
    for (const period of PERIODS) {
      const events = analyze(rows, period);
      const hits = events.filter(({ outcome }) => outcome === "ma200");
      console.log(`| ${period} | ${events.length} | ${pct(1 - hits.length / events.length)} | ${pct(hits.length / events.length)} | ${median(hits.map(({ days }) => days))}d | ${pct(median(hits.map((event) => event.return)))} |`);
    }
  }
}

if (import.meta.url === new URL(`file://${process.argv[1]}`).href) main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
