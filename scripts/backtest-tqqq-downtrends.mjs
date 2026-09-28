import assert from "node:assert/strict";
import { simpleMovingAverage } from "../public/strategy.js";

const PERIODS = [5, 10, 20, 50, 100, 200];
const UA = "Mozilla/5.0 TQQQ-Signal-Guide/0.1";
const pct = (value) => `${(value * 100).toFixed(1)}%`;
const median = (values) => values.toSorted((a, b) => a - b)[Math.floor(values.length / 2)];

export function detectEpisodes(rows, drawdown = 0.25, lookback = 63, horizon = 40) {
  const candidates = [];
  for (let i = lookback - 1; i + horizon < rows.length; i += 1) {
    if (rows[i].high < Math.max(...rows.slice(i - lookback + 1, i + 1).map(({ high }) => high))) continue;
    const future = rows.slice(i + 1, i + horizon + 1);
    const trough = i + 1 + future.reduce((best, row, index) => row.close < future[best].close ? index : best, 0);
    if (rows[trough].close / rows[i].high - 1 <= -drawdown) candidates.push({ peak: i, trough });
  }

  const groups = [];
  for (const candidate of candidates) {
    const group = groups.at(-1);
    if (!group || candidate.peak - group.at(-1).peak > horizon) groups.push([candidate]);
    else group.push(candidate);
  }
  return groups.map((group) => group.reduce((best, event) => rows[event.peak].high > rows[best.peak].high ? event : best));
}

function firstCross(rows, event, period) {
  for (let i = event.peak + 1; i <= event.trough; i += 1) {
    if (rows[i - 1].close >= rows[i - 1][`ma${period}`] && rows[i].close < rows[i][`ma${period}`]) return i;
  }
  return null;
}

function addAverages(rows) {
  for (const period of PERIODS) {
    const averages = simpleMovingAverage(rows.map(({ close }) => close), period);
    rows.forEach((row, i) => { row[`ma${period}`] = averages[i]; });
  }
  return rows;
}

async function fetchTqqq(asOf) {
  const period2 = Math.floor(new Date(`${asOf}T23:59:59Z`).getTime() / 1000);
  const response = await fetch(`https://query1.finance.yahoo.com/v8/finance/chart/TQQQ?period1=0&period2=${period2}&interval=1d`, {
    headers: { "user-agent": UA, accept: "application/json" }
  });
  if (!response.ok) throw new Error(`Yahoo ${response.status}`);
  const payload = await response.json();
  const chart = payload.chart.result?.[0];
  if (!chart) throw new Error(payload.chart.error?.description || "TQQQ data is empty");
  const quote = chart.indicators.quote[0];
  const date = new Intl.DateTimeFormat("en-CA", {
    timeZone: chart.meta.exchangeTimezoneName,
    year: "numeric", month: "2-digit", day: "2-digit"
  });
  return addAverages(chart.timestamp.map((time, i) => ({
    date: date.format(new Date(time * 1000)),
    high: quote.high[i], close: quote.close[i], volume: quote.volume[i]
  })).filter(({ high, close }) => Number.isFinite(high) && Number.isFinite(close)));
}

function selfCheck() {
  const rows = Array.from({ length: 104 }, (_, i) => ({ high: i <= 62 ? i + 38 : 100 - i, close: i <= 62 ? i + 38 : 100 - i }));
  assert.equal(detectEpisodes(rows).length, 1);
  assert.equal(detectEpisodes(rows)[0].peak, 62);
}

async function main() {
  selfCheck();
  const arg = process.argv.indexOf("--as-of");
  const asOf = arg >= 0 ? process.argv[arg + 1] : new Date().toISOString().slice(0, 10);
  assert.match(asOf, /^\d{4}-\d{2}-\d{2}$/);
  const rows = await fetchTqqq(asOf);
  const episodes = detectEpisodes(rows);

  console.log(`# TQQQ major downtrend episodes (through ${rows.at(-1).date})\n`);
  console.log("Definition: 63-session high followed by a >=25% close drawdown within 40 sessions.\n");
  console.log("| peak | trough | drawdown |");
  console.log("|---|---|---:|");
  for (const event of episodes) console.log(`| ${rows[event.peak].date} | ${rows[event.trough].date} | ${pct(rows[event.trough].close / rows[event.peak].high - 1)} |`);

  console.log("\n## Moving-average breaks inside those episodes\n");
  console.log("| MA | episodes hit | median lag | median drawdown at break | reclaimed before trough |");
  console.log("|---:|---:|---:|---:|---:|");
  for (const period of PERIODS) {
    const hits = episodes.map((event) => ({ event, cross: firstCross(rows, event, period) })).filter(({ cross }) => cross !== null);
    const reclaims = hits.filter(({ event, cross }) => rows.slice(cross + 1, event.trough + 1).some((row) => row.close > row[`ma${period}`])).length;
    console.log(`| ${period} | ${hits.length}/${episodes.length} | ${median(hits.map(({ event, cross }) => cross - event.peak))}d | ${pct(median(hits.map(({ event, cross }) => rows[cross].close / rows[event.peak].high - 1)))} | ${pct(reclaims / hits.length)} |`);
  }

  console.log("\n## All downward crosses, without hindsight\n");
  console.log("| MA | events | lower 20d later | no reclaim for 20d | median 20d return |");
  console.log("|---:|---:|---:|---:|---:|");
  for (const period of PERIODS) {
    const events = [];
    for (let i = period; i + 20 < rows.length; i += 1) {
      if (rows[i - 1].close < rows[i - 1][`ma${period}`] || rows[i].close >= rows[i][`ma${period}`]) continue;
      events.push({
        return: rows[i + 20].close / rows[i].close - 1,
        reclaimed: rows.slice(i + 1, i + 21).some((row) => row.close > row[`ma${period}`])
      });
    }
    console.log(`| ${period} | ${events.length} | ${pct(events.filter((event) => event.return < 0).length / events.length)} | ${pct(events.filter((event) => !event.reclaimed).length / events.length)} | ${pct(median(events.map((event) => event.return)))} |`);
  }

  const confirmed = (i) => rows[i].close < rows[i].ma20 && rows[i].ma20 < rows[i - 5].ma20
    && rows.slice(i - 2, i + 1).every((row) => row.close < row.ma50);
  const confirmations = episodes.map((event) => {
    for (let i = event.peak + 1; i <= event.trough; i += 1) if (confirmed(i)) return { event, index: i };
    return null;
  }).filter(Boolean);
  console.log(`\nCombined confirmation (falling MA20 + 3 closes below MA50): ${confirmations.length}/${episodes.length} episodes, median ${median(confirmations.map(({ event, index }) => index - event.peak))} sessions after peak at ${pct(median(confirmations.map(({ event, index }) => rows[index].close / rows[event.peak].high - 1)))}.`);
  const allConfirmations = [];
  for (let i = 200; i + 20 < rows.length; i += 1) if (confirmed(i) && !confirmed(i - 1)) {
    const recentHigh = Math.max(...rows.slice(i - 39, i + 1).map(({ high }) => high));
    allConfirmations.push({
      return: rows[i + 20].close / rows[i].close - 1,
      deep: Math.min(...rows.slice(i + 1, i + 21).map(({ close }) => close)) / recentHigh - 1 <= -0.25
    });
  }
  console.log(`Across all ${allConfirmations.length} confirmations: ${pct(allConfirmations.filter((event) => event.return < 0).length / allConfirmations.length)} were lower 20 sessions later and ${pct(allConfirmations.filter(({ deep }) => deep).length / allConfirmations.length)} reached a 25% drawdown from the recent 40-session high.`);

  const shocks = episodes.map((event) => {
    let worst = event.peak + 1;
    for (let i = event.peak + 1; i <= Math.min(event.peak + 10, event.trough); i += 1) {
      if (rows[i].close / rows[i - 1].close < rows[worst].close / rows[worst - 1].close) worst = i;
    }
    const averageVolume = rows.slice(worst - 20, worst).reduce((sum, row) => sum + row.volume, 0) / 20;
    return { drop: rows[worst].close / rows[worst - 1].close - 1, volumeRatio: rows[worst].volume / averageVolume };
  });
  console.log(`Early shock: ${shocks.filter(({ drop }) => drop <= -0.05).length}/${episodes.length} had a >=5% down day in the first 10 sessions; median worst day ${pct(median(shocks.map(({ drop }) => drop)))}, median volume ${median(shocks.map(({ volumeRatio }) => volumeRatio)).toFixed(1)}x its prior 20-day average.`);

  const target = episodes.find((event) => rows[event.peak].date === "2026-06-03");
  if (target) {
    console.log("\n## 2026-06-03 episode\n");
    for (const period of PERIODS) {
      const cross = firstCross(rows, target, period);
      console.log(`- MA${period}: ${cross === null ? "not crossed before trough" : `${rows[cross].date}, ${pct(rows[cross].close / rows[target.peak].high - 1)} from peak`}`);
    }
    for (let i = target.peak + 1; i <= target.trough; i += 1) if (confirmed(i)) {
      console.log(`- Combined confirmation: ${rows[i].date}, ${pct(rows[i].close / rows[target.peak].high - 1)} from peak`);
      break;
    }
  }
}

if (import.meta.url === new URL(`file://${process.argv[1]}`).href) main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
