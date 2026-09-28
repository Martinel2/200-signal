import assert from "node:assert/strict";
import { simpleMovingAverage } from "../public/strategy.js";

const UA = "Mozilla/5.0 TQQQ-Signal-Guide/0.1";
const pct = (value) => `${(value * 100).toFixed(1)}%`;
const AVERAGES = [20, 50, 75, 100, 125, 150, 160, 170, 175, 180, 190, 200, 210, 220];

export function bandStates(rows) {
  let riskOn = false;
  return rows.map(({ close, ma200 }) => {
    if (!riskOn && close > ma200 * 1.02) riskOn = true;
    else if (riskOn && close < ma200 * 0.98) riskOn = false;
    return riskOn;
  });
}

export function multiMaStates(rows) {
  let riskOn = false;
  return rows.map((row) => {
    const above = [50, 100, 175, 200].filter((period) => row.close > row[`ma${period}`]).length;
    if (riskOn && above <= 1) riskOn = false;
    else if (!riskOn && above >= 2) riskOn = true;
    return riskOn;
  });
}

export async function fetchRows(symbol, asOf) {
  const period2 = Math.floor(new Date(`${asOf}T23:59:59Z`).getTime() / 1000);
  const response = await fetch(`https://query1.finance.yahoo.com/v8/finance/chart/${symbol}?period1=0&period2=${period2}&interval=1d&events=div%2Csplits&includeAdjustedClose=true`, {
    headers: { "user-agent": UA, accept: "application/json" }
  });
  if (!response.ok) throw new Error(`${symbol}: Yahoo ${response.status}`);
  const payload = await response.json();
  const chart = payload.chart.result?.[0];
  if (!chart) throw new Error(payload.chart.error?.description || `${symbol}: no data`);
  const quote = chart.indicators.quote[0];
  const adjusted = chart.indicators.adjclose[0].adjclose;
  const date = new Intl.DateTimeFormat("en-CA", {
    timeZone: chart.meta.exchangeTimezoneName,
    year: "numeric", month: "2-digit", day: "2-digit"
  });
  const rows = chart.timestamp.map((time, i) => ({
    date: date.format(new Date(time * 1000)),
    open: quote.open[i],
    high: quote.high[i],
    close: quote.close[i],
    adjusted: adjusted[i],
    adjustedOpen: quote.open[i] * adjusted[i] / quote.close[i]
  })).filter(({ open, high, close, adjusted }) => [open, high, close, adjusted].every(Number.isFinite));
  for (const period of AVERAGES) {
    const averages = simpleMovingAverage(rows.map(({ close }) => close), period);
    rows.forEach((row, i) => { row[`ma${period}`] = averages[i]; });
  }
  return rows;
}

function backtest(rows, states, switchCost) {
  let equity = 1;
  let position = states[0];
  let peak = 1;
  let drawdown = 0;
  let switches = 0;
  let riskDays = 0;
  const dailyReturns = [];
  const yearEnds = new Map([[rows[0].date.slice(0, 4), equity]]);

  for (let i = 1; i < rows.length; i += 1) {
    const previousEquity = equity;
    const desired = states[i - 1]; // 전일 종가 신호를 다음 거래일 수익률부터 적용한다.
    if (desired !== position) {
      const oldAsset = position ? "tqqq" : "bil";
      const newAsset = desired ? "tqqq" : "bil";
      equity *= rows[i][oldAsset].adjustedOpen / rows[i - 1][oldAsset].adjusted;
      equity *= 1 - switchCost;
      equity *= rows[i][newAsset].adjusted / rows[i][newAsset].adjustedOpen;
      position = desired;
      switches += 1;
    } else {
      const asset = position ? "tqqq" : "bil";
      equity *= rows[i][asset].adjusted / rows[i - 1][asset].adjusted;
    }
    dailyReturns.push(equity / previousEquity - 1);
    if (position) riskDays += 1;
    peak = Math.max(peak, equity);
    drawdown = Math.min(drawdown, equity / peak - 1);
    yearEnds.set(rows[i].date.slice(0, 4), equity);
  }

  const years = (Date.parse(rows.at(-1).date) - Date.parse(rows[0].date)) / 31_556_952_000;
  const mean = dailyReturns.reduce((sum, value) => sum + value, 0) / dailyReturns.length;
  const volatility = Math.sqrt(dailyReturns.reduce((sum, value) => sum + (value - mean) ** 2, 0) / (dailyReturns.length - 1)) * Math.sqrt(252);
  const annual = [];
  let prior = 1;
  for (const [year, value] of yearEnds) {
    annual.push({ year, return: value / prior - 1 });
    prior = value;
  }
  const fullYears = annual.filter(({ year }) => year !== rows[0].date.slice(0, 4) && year !== rows.at(-1).date.slice(0, 4));
  return {
    multiple: equity,
    cagr: equity ** (1 / years) - 1,
    drawdown,
    volatility,
    switches,
    riskExposure: riskDays / dailyReturns.length,
    worstYear: fullYears.reduce((worst, row) => row.return < worst.return ? row : worst)
  };
}

function selfCheck() {
  assert.deepEqual(bandStates([
    { close: 101, ma200: 100 }, { close: 103, ma200: 100 }, { close: 100, ma200: 100 }, { close: 97, ma200: 100 }
  ]), [false, true, true, false]);
  assert.deepEqual(multiMaStates([
    { close: 90, ma50: 100, ma100: 100, ma175: 100, ma200: 100 },
    { close: 101, ma50: 100, ma100: 100, ma175: 102, ma200: 102 }
  ]), [false, true]);
}

async function main() {
  selfCheck();
  const dateArg = process.argv.indexOf("--as-of");
  const costArg = process.argv.indexOf("--cost");
  const asOf = dateArg >= 0 ? process.argv[dateArg + 1] : new Date().toISOString().slice(0, 10);
  const switchCost = costArg >= 0 ? Number(process.argv[costArg + 1]) : 0.001;
  assert.match(asOf, /^\d{4}-\d{2}-\d{2}$/);
  assert.ok(Number.isFinite(switchCost) && switchCost >= 0 && switchCost < 1);

  const [tqqq, qqq, bil] = await Promise.all(["TQQQ", "QQQ", "BIL"].map((symbol) => fetchRows(symbol, asOf)));
  const qqqByDate = new Map(qqq.map((row) => [row.date, row]));
  const bilByDate = new Map(bil.map((row) => [row.date, row]));
  const rows = tqqq.filter((row) => Number.isFinite(row.ma200) && qqqByDate.has(row.date) && bilByDate.has(row.date))
    .map((row) => ({ date: row.date, tqqq: row, qqq: qqqByDate.get(row.date), bil: bilByDate.get(row.date) }));
  const strategies = {
    "TQQQ buy & hold": rows.map(() => true),
    "200티큐단 signal": rows.map(({ tqqq: row }) => row.close > row.ma200),
    "QQQ SMA175": rows.map(({ qqq: row }) => row.close > row.ma175),
    "QQQ 4-MA 3-out / 2-in": multiMaStates(rows.map(({ qqq: row }) => row)),
    "200큐큐단 signal": bandStates(rows.map(({ qqq: row }) => row))
  };

  console.log(`# TQQQ regime strategy comparison (${rows[0].date}~${rows.at(-1).date})\n`);
  console.log(`Signal at close, executed next session open; safe asset BIL; adjusted returns; ${(switchCost * 100).toFixed(2)}% friction per allocation switch.\n`);
  console.log("| strategy | multiple | CAGR | MDD | volatility | Calmar | switches | TQQQ exposure | worst full year |");
  console.log("|---|---:|---:|---:|---:|---:|---:|---:|---:|");
  for (const [name, states] of Object.entries(strategies)) {
    const result = backtest(rows, states, switchCost);
    console.log(`| ${name} | ${result.multiple.toFixed(1)}x | ${pct(result.cagr)} | ${pct(result.drawdown)} | ${pct(result.volatility)} | ${(result.cagr / -result.drawdown).toFixed(2)} | ${result.switches} | ${pct(result.riskExposure)} | ${result.worstYear.year} ${pct(result.worstYear.return)} |`);
  }

  console.log("\n| strategy | 2010-11-24~2018 CAGR / MDD | 2019~2026-08 CAGR / MDD |");
  console.log("|---|---:|---:|");
  const split = rows.findIndex(({ date }) => date >= "2019-01-01");
  for (const [name, states] of Object.entries(strategies)) {
    const early = backtest(rows.slice(0, split), states.slice(0, split), switchCost);
    const late = backtest(rows.slice(split), states.slice(split), switchCost);
    console.log(`| ${name} | ${pct(early.cagr)} / ${pct(early.drawdown)} | ${pct(late.cagr)} / ${pct(late.drawdown)} |`);
  }

  console.log("\n| QQQ SMA | CAGR | MDD | Calmar | switches |");
  console.log("|---:|---:|---:|---:|---:|");
  for (const period of AVERAGES.filter((value) => value >= 150)) {
    const result = backtest(rows, rows.map(({ qqq: row }) => row.close > row[`ma${period}`]), switchCost);
    console.log(`| ${period} | ${pct(result.cagr)} | ${pct(result.drawdown)} | ${(result.cagr / -result.drawdown).toFixed(2)} | ${result.switches} |`);
  }
}

if (import.meta.url === new URL(`file://${process.argv[1]}`).href) main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
