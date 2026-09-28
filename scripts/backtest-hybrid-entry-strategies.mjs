import assert from "node:assert/strict";
import { fetchRows } from "./backtest-regime-strategies.mjs";

const pct = (value) => `${(value * 100).toFixed(1)}%`;

export function stagedStates(rows, enter, exit) {
  let step = 0;
  return rows.map((row, i) => {
    if (step && exit(row, rows[i - 1])) step = 0;
    else if (!step && enter(row, rows[i - 1])) step = 1;
    else if (step < 3 && step > 0) step += 1;
    return step / 3;
  });
}

function backtest(rows, weights, switchCost) {
  let equity = 1;
  let weight = 0;
  let peak = 1;
  let drawdown = 0;
  let changes = 0;
  let entries = 0;
  let exits = 0;
  let exposure = 0;
  const returns = [];
  const yearEnds = new Map([[rows[0].date.slice(0, 4), equity]]);

  for (let i = 1; i < rows.length; i += 1) {
    const previousEquity = equity;
    const desired = weights[i - 1];
    equity *= weight * rows[i].tqqq.adjustedOpen / rows[i - 1].tqqq.adjusted
      + (1 - weight) * rows[i].bil.adjustedOpen / rows[i - 1].bil.adjusted;
    if (desired !== weight) {
      equity *= 1 - Math.abs(desired - weight) * switchCost;
      changes += 1;
      if (!weight && desired) entries += 1;
      if (weight && !desired) exits += 1;
      weight = desired;
    }
    equity *= weight * rows[i].tqqq.adjusted / rows[i].tqqq.adjustedOpen
      + (1 - weight) * rows[i].bil.adjusted / rows[i].bil.adjustedOpen;
    returns.push(equity / previousEquity - 1);
    exposure += weight;
    peak = Math.max(peak, equity);
    drawdown = Math.min(drawdown, equity / peak - 1);
    yearEnds.set(rows[i].date.slice(0, 4), equity);
  }

  const years = (Date.parse(rows.at(-1).date) - Date.parse(rows[0].date)) / 31_556_952_000;
  const mean = returns.reduce((sum, value) => sum + value, 0) / returns.length;
  const volatility = Math.sqrt(returns.reduce((sum, value) => sum + (value - mean) ** 2, 0) / (returns.length - 1)) * Math.sqrt(252);
  const annual = [];
  let prior = 1;
  for (const [year, value] of yearEnds) {
    annual.push({ year, return: value / prior - 1 });
    prior = value;
  }
  const completeYears = annual.filter(({ year }) => year !== rows[0].date.slice(0, 4) && year !== rows.at(-1).date.slice(0, 4));
  return {
    multiple: equity,
    cagr: equity ** (1 / years) - 1,
    drawdown,
    volatility,
    calmar: (equity ** (1 / years) - 1) / -drawdown,
    changes,
    entries,
    exits,
    exposure: exposure / returns.length,
    latestYear: annual.at(-1),
    worstYear: completeYears.reduce((worst, row) => row.return < worst.return ? row : worst)
  };
}

function selfCheck() {
  const rows = [
    { close: 9, ma200: 10 }, { close: 11, ma200: 10 }, { close: 12, ma200: 10 },
    { close: 13, ma200: 10 }, { close: 8, ma200: 10 }
  ];
  assert.deepEqual(stagedStates(rows, (row, prior) => row.close > row.ma200 && (!prior || prior.close <= prior.ma200), (row) => row.close < row.ma200), [0, 1 / 3, 2 / 3, 1, 0]);
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
  const crossAbove = (key, multiplier = 1) => (row, prior) => row[key].close > row[key].ma200 * multiplier
    && (!prior || prior[key].close <= prior[key].ma200 * multiplier);
  const crossBelow = (key, period) => (row, prior) => row[key].close < row[key][`ma${period}`]
    && (!prior || prior[key].close >= prior[key][`ma${period}`]);
  const strategies = {
    "TQQQ buy & hold": rows.map(() => 1),
    "TQQQ150 sell / TQQQ200 buy": stagedStates(rows, crossAbove("tqqq"), crossBelow("tqqq", 150)),
    "TQQQ175 sell / TQQQ200 buy": stagedStates(rows, crossAbove("tqqq"), crossBelow("tqqq", 175)),
    "200티큐단": stagedStates(rows, crossAbove("tqqq"), (row) => row.tqqq.close < row.tqqq.ma200),
    "200큐큐단": stagedStates(rows, crossAbove("qqq", 1.02), (row) => row.qqq.close < row.qqq.ma200 * 0.98)
  };

  console.log(`# Hybrid exit/re-entry comparison (${rows[0].date}~${rows.at(-1).date})\n`);
  console.log(`Three-session equal entry; close signal executed next open; safe asset BIL; adjusted returns; ${pct(switchCost)} cost per traded allocation.\n`);
  console.log("| strategy | multiple | CAGR | MDD | volatility | Calmar | entries / exits | TQQQ exposure | 2026 YTD | current |" );
  console.log("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|");
  for (const [name, weights] of Object.entries(strategies)) {
    const result = backtest(rows, weights, switchCost);
    console.log(`| ${name} | ${result.multiple.toFixed(1)}x | ${pct(result.cagr)} | ${pct(result.drawdown)} | ${pct(result.volatility)} | ${result.calmar.toFixed(2)} | ${result.entries} / ${result.exits} | ${pct(result.exposure)} | ${pct(result.latestYear.return)} | ${pct(weights.at(-1))} |`);
  }

  console.log("\n| strategy | 2010-11~2018 CAGR / MDD | 2019~2026-08 CAGR / MDD |");
  console.log("|---|---:|---:|");
  const split = rows.findIndex(({ date }) => date >= "2019-01-01");
  for (const [name, weights] of Object.entries(strategies)) {
    const early = backtest(rows.slice(0, split), weights.slice(0, split), switchCost);
    const late = backtest(rows.slice(split), weights.slice(split), switchCost);
    console.log(`| ${name} | ${pct(early.cagr)} / ${pct(early.drawdown)} | ${pct(late.cagr)} / ${pct(late.drawdown)} |`);
  }
}

if (import.meta.url === new URL(`file://${process.argv[1]}`).href) main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
