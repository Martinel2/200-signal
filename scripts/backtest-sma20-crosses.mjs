import assert from "node:assert/strict";
import { simpleMovingAverage } from "../public/strategy.js";

const SYMBOLS = ["QQQ", "SPYM", "TQQQ"];
const HORIZONS = [5, 10, 20];
const UA = "Mozilla/5.0 TQQQ-Signal-Guide/0.1";

function median(values) {
  const sorted = values.toSorted((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
}

export function analyze(rows, horizon) {
  const averages = simpleMovingAverage(rows.map(({ close }) => close), 20);
  const events = [];

  for (let i = 20; i + horizon < rows.length; i += 1) {
    const previous = rows[i - 1].close - averages[i - 1];
    const current = rows[i].close - averages[i];
    const direction = previous >= 0 && current < 0 ? "down" : previous <= 0 && current > 0 ? "up" : null;
    if (!direction) continue;

    let reversalDays = null;
    for (let j = i + 1; j <= i + horizon; j += 1) {
      const reversed = direction === "down" ? rows[j].close > averages[j] : rows[j].close < averages[j];
      if (reversed) {
        reversalDays = j - i;
        break;
      }
    }

    events.push({
      direction,
      reversed: reversalDays !== null,
      reversalDays,
      forwardReturn: rows[i + horizon].close / rows[i].close - 1
    });
  }

  const summarize = (direction) => {
    const selected = events.filter((event) => event.direction === direction);
    const reversed = selected.filter((event) => event.reversed).length;
    const favorable = selected.filter((event) => direction === "down" ? event.forwardReturn < 0 : event.forwardReturn > 0).length;
    return {
      events: selected.length,
      reversed,
      reversedPct: reversed / selected.length,
      heldPct: 1 - reversed / selected.length,
      favorablePct: favorable / selected.length,
      medianReturn: median(selected.map(({ forwardReturn }) => forwardReturn))
    };
  };

  return { down: summarize("down"), up: summarize("up") };
}

async function fetchYahoo(symbol, asOf, adjusted) {
  const period2 = Math.floor(new Date(`${asOf}T23:59:59Z`).getTime() / 1000);
  const url = `https://query1.finance.yahoo.com/v8/finance/chart/${symbol}?period1=0&period2=${period2}&interval=1d&events=div%2Csplits&includeAdjustedClose=true`;
  const response = await fetch(url, { headers: { "user-agent": UA, accept: "application/json" } });
  if (!response.ok) throw new Error(`${symbol}: Yahoo ${response.status}`);
  const payload = await response.json();
  const chart = payload.chart.result?.[0];
  if (!chart) throw new Error(payload.chart.error?.description || `${symbol}: no data`);
  const values = adjusted ? chart.indicators.adjclose[0].adjclose : chart.indicators.quote[0].close;
  const date = new Intl.DateTimeFormat("en-CA", {
    timeZone: chart.meta.exchangeTimezoneName,
    year: "numeric", month: "2-digit", day: "2-digit"
  });
  const rows = chart.timestamp.map((time, i) => ({ date: date.format(new Date(time * 1000)), close: values[i] }))
    .filter(({ date, close }) => date <= asOf && Number.isFinite(close));
  assert.ok(rows.length > 20, `${symbol}: not enough rows`);
  assert.ok(rows.every((row, i) => i === 0 || row.date > rows[i - 1].date), `${symbol}: dates not sorted`);
  return rows;
}

const pct = (value) => `${(value * 100).toFixed(1)}%`;

function selfCheck() {
  const closes = [...Array(20).fill(10), 9, 11, 11];
  const result = analyze(closes.map((close, i) => ({ date: String(i), close })), 2);
  assert.equal(result.down.events, 1);
  assert.equal(result.down.reversed, 1);
}

async function main() {
  selfCheck();
  const arg = process.argv.indexOf("--as-of");
  const asOf = arg >= 0 ? process.argv[arg + 1] : new Date().toISOString().slice(0, 10);
  const adjusted = process.argv.includes("--adjusted");
  assert.match(asOf, /^\d{4}-\d{2}-\d{2}$/);
  const data = Object.fromEntries(await Promise.all(SYMBOLS.map(async (symbol) => [symbol, await fetchYahoo(symbol, asOf, adjusted)])));

  console.log(`# SMA20 cross backtest (${adjusted ? "adjusted" : "close"}, through ${asOf})\n`);
  for (const symbol of SYMBOLS) console.log(`- ${symbol}: ${data[symbol][0].date}~${data[symbol].at(-1).date}, ${data[symbol].length} sessions`);

  console.log("\n## Downward crosses\n");
  console.log("| ticker | horizon | events | recovered above | stayed below | return < 0 | median return |");
  console.log("|---|---:|---:|---:|---:|---:|---:|");
  for (const symbol of SYMBOLS) for (const horizon of HORIZONS) {
    const result = analyze(data[symbol], horizon).down;
    console.log(`| ${symbol} | ${horizon}d | ${result.events} | ${pct(result.reversedPct)} | ${pct(result.heldPct)} | ${pct(result.favorablePct)} | ${pct(result.medianReturn)} |`);
  }

  console.log("\n## Upward crosses\n");
  console.log("| ticker | horizon | events | stayed above | fell below again | return > 0 | median return |");
  console.log("|---|---:|---:|---:|---:|---:|---:|");
  for (const symbol of SYMBOLS) for (const horizon of HORIZONS) {
    const result = analyze(data[symbol], horizon).up;
    console.log(`| ${symbol} | ${horizon}d | ${result.events} | ${pct(result.heldPct)} | ${pct(result.reversedPct)} | ${pct(result.favorablePct)} | ${pct(result.medianReturn)} |`);
  }
}

if (import.meta.url === new URL(`file://${process.argv[1]}`).href) main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
