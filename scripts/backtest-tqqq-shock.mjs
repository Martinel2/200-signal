import assert from "node:assert/strict";
import { simpleMovingAverage as sma } from "../public/strategy.js";

const YAHOO = "https://query1.finance.yahoo.com/v8/finance/chart/TQQQ";
const NASDAQ = "https://api.nasdaq.com/api/quote/TQQQ/historical";
const UA = "Mozilla/5.0 TQQQ-Signal-Guide/0.1";

function classify(rows, averages, index, { horizon, confirm }) {
  const target = rows[index - 1].close;
  let below = 0;
  for (let i = index; i <= index + horizon; i += 1) {
    below = rows[i].close < averages[i] ? below + 1 : 0;
    if (i > index && rows[i].close >= target) return { outcome: "recovery", end: i, days: i - index };
    if (below >= confirm) return { outcome: "downtrend", end: i, days: i - index };
  }
  return { outcome: "unresolved", end: index + horizon, days: horizon };
}

export function analyze(rows, config) {
  const averages = sma(rows.map((row) => row.close), config.sma);
  const outcomes = [];
  for (let i = Math.max(config.sma, config.slopeDays + config.sma - 1); i + config.horizon < rows.length;) {
    const drop = rows[i].close / rows[i - 1].close - 1;
    const aboveBefore = rows[i - 1].close > averages[i - 1];
    const rising = !config.slopeDays || averages[i - 1] > averages[i - 1 - config.slopeDays];
    const stillAbove = !config.stillAbove || rows[i].close > averages[i];
    if (drop > -0.05 || !aboveBefore || !rising || !stillAbove) {
      i += 1;
      continue;
    }
    const result = classify(rows, averages, i, config);
    outcomes.push({ date: rows[i].date, drop, ...result });
    i = result.end + 1; // 같은 충격이 해소되기 전의 반복 급락은 한 사건으로 묶는다.
  }

  return summarize(outcomes);
}

function summarize(outcomes) {
  const counts = { recovery: 0, downtrend: 0, unresolved: 0 };
  outcomes.forEach(({ outcome }) => { counts[outcome] += 1; });
  const resolved = counts.recovery + counts.downtrend;
  const recoveryDays = outcomes.filter(({ outcome }) => outcome === "recovery").map(({ days }) => days).sort((a, b) => a - b);
  return {
    events: outcomes.length,
    ...counts,
    recoveryPct: resolved ? counts.recovery / resolved : 0,
    medianRecoveryDays: recoveryDays[Math.floor(recoveryDays.length / 2)] ?? null,
    outcomes
  };
}

async function fetchJson(url, headers = {}) {
  const response = await fetch(url, { headers: { "user-agent": UA, accept: "application/json", ...headers } });
  if (!response.ok) throw new Error(`${response.status} ${url}`);
  return response.json();
}

async function fetchYahoo(asOf) {
  const period2 = Math.floor(new Date(`${asOf}T23:59:59Z`).getTime() / 1000);
  const payload = await fetchJson(`${YAHOO}?period1=0&period2=${period2}&interval=1d&events=div%2Csplits&includeAdjustedClose=true`);
  const chart = payload.chart.result?.[0];
  if (!chart) throw new Error(payload.chart.error?.description || "Yahoo data is empty");
  const close = chart.indicators.quote[0].close;
  const date = new Intl.DateTimeFormat("en-CA", {
    timeZone: chart.meta.exchangeTimezoneName,
    year: "numeric", month: "2-digit", day: "2-digit"
  });
  return chart.timestamp.map((time, i) => ({ date: date.format(new Date(time * 1000)), close: close[i] }))
    .filter((row) => row.date <= asOf && Number.isFinite(row.close));
}

async function fetchNasdaq(asOf) {
  const from = `${Number(asOf.slice(0, 4)) - 10}${asOf.slice(4)}`;
  const url = `${NASDAQ}?assetclass=etf&fromdate=${from}&todate=${asOf}&limit=5000`;
  const payload = await fetchJson(url, { referer: "https://www.nasdaq.com/" });
  const rows = payload.data?.tradesTable?.rows;
  if (!rows) throw new Error("Nasdaq data is empty");
  return rows.map((row) => {
    const [month, day, year] = row.date.split("/");
    return { date: `${year}-${month}-${day}`, close: Number(row.close.replaceAll(",", "")) };
  }).filter((row) => Number.isFinite(row.close)).reverse();
}

function validate(rows, source) {
  assert.ok(rows.length > 200, `${source}: not enough rows`);
  assert.equal(new Set(rows.map(({ date }) => date)).size, rows.length, `${source}: duplicate dates`);
  assert.ok(rows.every(({ close }) => close > 0), `${source}: invalid close`);
  assert.ok(rows.every((row, i) => i === 0 || row.date > rows[i - 1].date), `${source}: dates not sorted`);
}

function config(overrides = {}) {
  return { sma: 200, slopeDays: 0, stillAbove: false, horizon: 60, confirm: 5, ...overrides };
}

function line(label, result) {
  return `| ${label} | ${result.events} | ${result.recovery} | ${result.downtrend} | ${result.unresolved} | ${(result.recoveryPct * 100).toFixed(1)}% |`;
}

function selfCheck() {
  assert.deepEqual(sma([1, 2, 3, 4], 3), [null, null, 2, 3]);
  const dates = Array.from({ length: 10 }, (_, i) => `2020-01-${String(i + 1).padStart(2, "0")}`);
  const rows = dates.map((date, i) => ({ date, close: [100, 100, 100, 100, 100, 94, 96, 100, 101, 102][i] }));
  const result = classify(rows, sma(rows.map(({ close }) => close), 3), 5, { horizon: 4, confirm: 3 });
  assert.equal(result.outcome, "recovery");
}

async function main() {
  selfCheck();
  const arg = process.argv.indexOf("--as-of");
  const asOf = arg >= 0 ? process.argv[arg + 1] : new Date().toISOString().slice(0, 10);
  assert.match(asOf, /^\d{4}-\d{2}-\d{2}$/);

  const [yahoo, nasdaq] = await Promise.all([fetchYahoo(asOf), fetchNasdaq(asOf)]);
  validate(yahoo, "Yahoo");
  validate(nasdaq, "Nasdaq");

  const yahooByDate = new Map(yahoo.map((row) => [row.date, row.close]));
  const common = nasdaq.filter((row) => yahooByDate.has(row.date));
  validate(common, "Yahoo/Nasdaq overlap");
  const yahooCommon = common.map((row) => ({ date: row.date, close: yahooByDate.get(row.date) }));
  const confirmedYahoo = yahoo.filter((row) => row.date <= common.at(-1).date);
  const differences = common.map((row, i) => Math.abs(row.close / yahooCommon[i].close - 1)).sort((a, b) => a - b);
  const primary = analyze(confirmedYahoo, config());
  const crossYahoo = analyze(yahooCommon, config());
  const crossNasdaq = analyze(common, config());

  console.log(`# TQQQ -5% shock backtest (${asOf})\n`);
  console.log(`- Yahoo fetched: ${yahoo[0].date}~${yahoo.at(-1).date}, ${yahoo.length} rows`);
  console.log(`- Nasdaq cross-check: ${common[0].date}~${common.at(-1).date}, ${common.length} common rows`);
  console.log(`- Analysis cutoff: ${confirmedYahoo.at(-1).date} (last date confirmed by both sources)`);
  console.log(`- Cross-source close difference: median ${(differences[Math.floor(differences.length / 2)] * 100).toFixed(4)}%, max ${(differences.at(-1) * 100).toFixed(4)}%\n`);
  console.log("| 기준 | 사건 | 회복 | 하락전환 | 미결 | 해결 사건 중 회복률 |");
  console.log("|---|---:|---:|---:|---:|---:|");
  console.log(line("기본: SMA200 / 60일 / 5일 확인", primary));
  for (const horizon of [20, 60, 120]) console.log(line(`관찰 ${horizon}일`, analyze(confirmedYahoo, config({ horizon }))));
  for (const confirm of [1, 3, 5, 10]) console.log(line(`SMA200 아래 ${confirm}일 확인`, analyze(confirmedYahoo, config({ confirm }))));
  for (const period of [50, 100, 200]) console.log(line(`SMA${period}`, analyze(confirmedYahoo, config({ sma: period }))));
  console.log(line("SMA200 + 20일 기울기 상승", analyze(confirmedYahoo, config({ slopeDays: 20 }))));
  console.log(line("급락 후에도 SMA200 위", analyze(confirmedYahoo, config({ stillAbove: true }))));
  console.log(line("2011~2018", summarize(primary.outcomes.filter(({ date }) => date < "2019-01-01"))));
  console.log(line("2019~현재", summarize(primary.outcomes.filter(({ date }) => date >= "2019-01-01"))));

  const grid = [];
  for (const period of [50, 100, 200]) for (const slopeDays of [0, 20]) {
    for (const horizon of [20, 60, 120]) for (const confirm of [1, 3, 5, 10]) {
      grid.push({ period, slopeDays, horizon, confirm, result: analyze(confirmedYahoo, config({ sma: period, slopeDays, horizon, confirm })) });
    }
  }
  grid.sort((a, b) => a.result.recoveryPct - b.result.recoveryPct);
  console.log(`\n- 72-combination sensitivity: recovery majority in ${grid.filter(({ result }) => result.recoveryPct > 0.5).length}/72; range ${(grid[0].result.recoveryPct * 100).toFixed(1)}%~${(grid.at(-1).result.recoveryPct * 100).toFixed(1)}%.`);
  console.log(`- Lowest case: SMA${grid[0].period}, slope ${grid[0].slopeDays}d, horizon ${grid[0].horizon}d, below-MA confirmation ${grid[0].confirm}d.`);
  console.log("\n## Source cross-check (common period)\n");
  console.log("| source | events | recovery | downtrend | unresolved | resolved recovery rate |");
  console.log("|---|---:|---:|---:|---:|---:|");
  console.log(line("Yahoo", crossYahoo));
  console.log(line("Nasdaq", crossNasdaq));
  console.log(`\nPrimary median recovery time: ${primary.medianRecoveryDays} trading days.`);
}

if (import.meta.url === new URL(`file://${process.argv[1]}`).href) main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
