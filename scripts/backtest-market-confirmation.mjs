import assert from "node:assert/strict";
import { simpleMovingAverage } from "../public/strategy.js";
import { detectEpisodes } from "./backtest-tqqq-downtrends.mjs";

const CONFIG = { TQQQ: 0.25, QQQ: 0.10, SPYM: 0.10, VOO: 0.10 };
const START = "2011-01-01";
const UA = "Mozilla/5.0 TQQQ-Signal-Guide/0.1";
const pct = (value) => `${(value * 100).toFixed(1)}%`;
const median = (values) => values.toSorted((a, b) => a - b)[Math.floor(values.length / 2)];

export const confirmed = (rows, i) => rows[i].close < rows[i].ma20 && rows[i].ma20 < rows[i - 5].ma20
  && rows.slice(i - 2, i + 1).every((row) => row.close < row.ma50);

async function fetchRows(symbol, asOf) {
  const period2 = Math.floor(new Date(`${asOf}T23:59:59Z`).getTime() / 1000);
  const response = await fetch(`https://query1.finance.yahoo.com/v8/finance/chart/${symbol}?period1=0&period2=${period2}&interval=1d`, {
    headers: { "user-agent": UA, accept: "application/json" }
  });
  if (!response.ok) throw new Error(`${symbol}: Yahoo ${response.status}`);
  const payload = await response.json();
  const chart = payload.chart.result?.[0];
  if (!chart) throw new Error(payload.chart.error?.description || `${symbol}: no data`);
  const quote = chart.indicators.quote[0];
  const date = new Intl.DateTimeFormat("en-CA", {
    timeZone: chart.meta.exchangeTimezoneName,
    year: "numeric", month: "2-digit", day: "2-digit"
  });
  const rows = chart.timestamp.map((time, i) => ({
    date: date.format(new Date(time * 1000)), high: quote.high[i], close: quote.close[i]
  })).filter(({ high, close }) => Number.isFinite(high) && Number.isFinite(close));
  for (const period of [20, 50]) {
    const averages = simpleMovingAverage(rows.map(({ close }) => close), period);
    rows.forEach((row, i) => { row[`ma${period}`] = averages[i]; });
  }
  return rows;
}

function summarize(rows, threshold) {
  const episodes = detectEpisodes(rows, threshold).filter(({ peak }) => rows[peak].date >= START);
  const hits = [];
  for (const event of episodes) for (let i = event.peak + 1; i <= event.trough; i += 1) if (confirmed(rows, i)) {
    hits.push({ lag: i - event.peak, drawdown: rows[i].close / rows[event.peak].high - 1 });
    break;
  }
  const signals = [];
  for (let i = 200; i + 20 < rows.length; i += 1) if (rows[i].date >= START && confirmed(rows, i) && !confirmed(rows, i - 1)) {
    const recentHigh = Math.max(...rows.slice(i - 39, i + 1).map(({ high }) => high));
    signals.push({
      lower: rows[i + 20].close < rows[i].close,
      deep: Math.min(...rows.slice(i + 1, i + 21).map(({ close }) => close)) / recentHigh - 1 <= -threshold
    });
  }
  return { episodes, hits, signals };
}

function selfCheck() {
  const rows = Array.from({ length: 60 }, () => ({ close: 8, ma20: 10, ma50: 9 }));
  rows[54].ma20 = 11;
  assert.equal(confirmed(rows, 59), true);
  rows[58].close = 10;
  assert.equal(confirmed(rows, 59), false);
}

async function main() {
  selfCheck();
  const arg = process.argv.indexOf("--as-of");
  const asOf = arg >= 0 ? process.argv[arg + 1] : new Date().toISOString().slice(0, 10);
  assert.match(asOf, /^\d{4}-\d{2}-\d{2}$/);
  const data = Object.fromEntries(await Promise.all(Object.keys(CONFIG).map(async (symbol) => [symbol, await fetchRows(symbol, asOf)])));

  console.log(`# Cross-market trend confirmation (${START}~${data.TQQQ.at(-1).date})\n`);
  console.log("Confirmation: falling MA20 and three consecutive closes below MA50. TQQQ episode threshold -25%; unlevered ETFs -10%.\n");
  console.log("| ticker | correction episodes | episodes confirmed | median lag | drawdown at confirmation | all signals | lower 20d later | reached threshold within 20d |");
  console.log("|---|---:|---:|---:|---:|---:|---:|---:|");
  for (const [symbol, threshold] of Object.entries(CONFIG)) {
    const { episodes, hits, signals } = summarize(data[symbol], threshold);
    console.log(`| ${symbol} | ${episodes.length} | ${hits.length}/${episodes.length} | ${median(hits.map(({ lag }) => lag))}d | ${pct(median(hits.map(({ drawdown }) => drawdown)))} | ${signals.length} | ${pct(signals.filter(({ lower }) => lower).length / signals.length)} | ${pct(signals.filter(({ deep }) => deep).length / signals.length)} |`);
  }

  const maps = Object.fromEntries(Object.entries(data).map(([symbol, rows]) => [symbol, new Map(rows.map((row, i) => [row.date, i]))]));
  const tqqqSignals = [];
  for (let i = 200; i + 20 < data.TQQQ.length; i += 1) if (data.TQQQ[i].date >= START && confirmed(data.TQQQ, i) && !confirmed(data.TQQQ, i - 1)) {
    const state = {};
    for (const symbol of ["QQQ", "SPYM", "VOO"]) {
      const row = data[symbol][maps[symbol].get(data.TQQQ[i].date)];
      state[symbol] = row.close < row.ma20;
    }
    const recentHigh = Math.max(...data.TQQQ.slice(i - 39, i + 1).map(({ high }) => high));
    const future = data.TQQQ.slice(i + 1, i + 21);
    tqqqSignals.push({
      ...state,
      lower: data.TQQQ[i + 20].close < data.TQQQ[i].close,
      deep: Math.min(...future.map(({ close }) => close)) / recentHigh - 1 <= -0.25,
      lose10: Math.min(...future.map(({ close }) => close)) / data.TQQQ[i].close - 1 <= -0.10
    });
  }
  const groups = {
    "all TQQQ confirmations": tqqqSignals,
    "QQQ weak, S&P ETFs strong": tqqqSignals.filter((row) => row.QQQ && !row.SPYM && !row.VOO),
    "QQQ + both S&P ETFs weak": tqqqSignals.filter((row) => row.QQQ && row.SPYM && row.VOO)
  };
  console.log("\n| breadth at TQQQ confirmation | signals | TQQQ lower 20d later | hit -25% from recent high | lost another 10% intraperiod |");
  console.log("|---|---:|---:|---:|---:|");
  for (const [label, signals] of Object.entries(groups)) console.log(`| ${label} | ${signals.length} | ${pct(signals.filter(({ lower }) => lower).length / signals.length)} | ${pct(signals.filter(({ deep }) => deep).length / signals.length)} | ${pct(signals.filter(({ lose10 }) => lose10).length / signals.length)} |`);

  console.log("\n## 2026 episode\n");
  for (const symbol of Object.keys(CONFIG)) {
    const rows = data[symbol];
    const start = maps[symbol].get("2026-06-03");
    const end = maps[symbol].get("2026-07-28");
    let signal = null;
    for (let i = start; i < rows.length && rows[i].date <= "2026-08-18"; i += 1) if (confirmed(rows, i) && !confirmed(rows, i - 1)) { signal = rows[i].date; break; }
    console.log(`- ${symbol}: ${pct(rows[end].close / rows[start].high - 1)} through 07-28; confirmation ${signal}`);
  }
}

if (import.meta.url === new URL(`file://${process.argv[1]}`).href) main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
