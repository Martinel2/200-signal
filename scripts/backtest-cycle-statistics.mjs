// Trade statistics for the 200일선 cycle, at the two counting units the source
// mixes: one trade per SMA200 cycle, and one trade per realised sale once the
// partial-exit ladder is counted. backtest-audit.md records a negative expected
// value from the source's 19% win rate paired with +12.82% / -4.09% averages;
// this measures both units on real TQQQ data so the mismatch can be checked
// rather than assumed.
import assert from "node:assert/strict";
import { fetchRows } from "./backtest-regime-strategies.mjs";

const pct = (value) => `${(value * 100).toFixed(2)}%`;
// 익절 사다리: +10·25·50% 최초 도달에 남은 수량의 10%, 이후 +100% 단위마다 50%.
const SMALL_STEPS = [0.10, 0.25, 0.50];
const SMALL_FRACTION = 0.10;
const LARGE_FRACTION = 0.50;

export function cycles(rows, signal) {
  const out = [];
  let open = null;
  for (let i = 1; i < rows.length; i += 1) {
    const want = signal(rows[i - 1]); // 전일 종가 신호를 다음 거래일 시가에 체결한다.
    if (want && !open) open = { entryIndex: i, entry: rows[i].adjustedOpen, marks: [] };
    else if (!want && open) {
      open.exitIndex = i;
      open.exit = rows[i].adjustedOpen;
      out.push(open);
      open = null;
    } else if (open) open.marks.push(rows[i].adjusted);
  }
  if (open) {
    open.exitIndex = rows.length - 1;
    open.exit = rows.at(-1).adjusted;
    open.unresolved = true;
    out.push(open);
  }
  return out;
}

// Each rung is a sale, so counting rungs as trades adds only winners by
// construction; the final sale of what is left is the one that can lose.
export function ladderTrades(cycle, cost) {
  const trades = [];
  let remaining = 1;
  let step = 0;
  let large = 1;
  for (const price of cycle.marks) {
    const gain = price / cycle.entry - 1;
    while (step < SMALL_STEPS.length && gain >= SMALL_STEPS[step]) {
      const sold = remaining * SMALL_FRACTION;
      trades.push({ weight: sold, return: gain - cost * 2 });
      remaining -= sold;
      step += 1;
    }
    while (gain >= large) {
      const sold = remaining * LARGE_FRACTION;
      trades.push({ weight: sold, return: gain - cost * 2 });
      remaining -= sold;
      large += 1;
    }
  }
  trades.push({ weight: remaining, return: cycle.exit / cycle.entry - 1 - cost * 2, final: true });
  return trades;
}

export function summarise(returns) {
  const wins = returns.filter((r) => r > 0);
  const losses = returns.filter((r) => r <= 0);
  const mean = (list) => list.reduce((sum, r) => sum + r, 0) / (list.length || 1);
  const winRate = wins.length / returns.length;
  const averageWin = mean(wins);
  const averageLoss = mean(losses);
  return {
    count: returns.length,
    winRate,
    averageWin,
    averageLoss,
    // The same arithmetic the audit memo applies to the source's figures.
    expectancy: winRate * averageWin + (1 - winRate) * averageLoss,
    profitFactor: wins.reduce((s, r) => s + r, 0) / Math.abs(losses.reduce((s, r) => s + r, 0) || NaN)
  };
}

function selfCheck() {
  const rows = [0.5, 1.2, 1.3, 1.4, 0.8, 0.7].map((close, i) => ({
    date: `2020-01-0${i + 1}`, adjusted: close, adjustedOpen: close, ma200: 1
  }));
  const found = cycles(rows, (row) => row.adjusted > row.ma200);
  assert.equal(found.length, 1);
  // Signal on day 2's close, filled at day 3's open; exit signal on day 5, filled day 6.
  assert.equal(found[0].entry, 1.3);
  assert.equal(found[0].exit, 0.7);
  assert.equal(found[0].unresolved, undefined);

  // A cycle that only ever gains 12% fires the +10% rung and nothing above it.
  const ladder = ladderTrades({ entry: 1, marks: [1.12], exit: 1.12 }, 0);
  assert.equal(ladder.length, 2);
  assert.ok(Math.abs(ladder[0].weight - 0.1) < 1e-9);

  const stats = summarise([0.2, -0.1]);
  assert.equal(stats.winRate, 0.5);
  assert.ok(Math.abs(stats.expectancy - 0.05) < 1e-9);
}

function table(name, stats) {
  const factor = Number.isFinite(stats.profitFactor) ? stats.profitFactor.toFixed(2) : "손실 없음";
  return `| ${name} | ${stats.count} | ${pct(stats.winRate)} | ${pct(stats.averageWin)} | `
    + `${pct(stats.averageLoss)} | ${pct(stats.expectancy)} | ${factor} |`;
}

async function main() {
  selfCheck();
  const dateArg = process.argv.indexOf("--as-of");
  const costArg = process.argv.indexOf("--cost");
  const asOf = dateArg >= 0 ? process.argv[dateArg + 1] : new Date().toISOString().slice(0, 10);
  const cost = costArg >= 0 ? Number(process.argv[costArg + 1]) : 0.001;
  assert.match(asOf, /^\d{4}-\d{2}-\d{2}$/);
  assert.ok(Number.isFinite(cost) && cost >= 0 && cost < 1);

  const tqqq = (await fetchRows("TQQQ", asOf)).filter((row) => Number.isFinite(row.ma200));
  const found = cycles(tqqq, (row) => row.close > row.ma200);
  const closed = found.filter((cycle) => !cycle.unresolved);

  const cycleReturns = closed.map((cycle) => cycle.exit / cycle.entry - 1 - cost * 2);
  const ladder = closed.flatMap((cycle) => ladderTrades(cycle, cost));

  console.log(`# 200일선 사이클 거래 통계 (${tqqq[0].date}~${tqqq.at(-1).date})\n`);
  console.log(`종가 신호를 다음 거래일 시가에 체결하고, 배당 조정 종가와 왕복 ${pct(cost * 2)} 비용을 적용했습니다.`);
  console.log(`사이클 ${found.length}개 중 청산이 끝난 ${closed.length}개를 셌습니다. 환율과 세금은 넣지 않았습니다.\n`);

  console.log("| 세는 단위 | 거래 수 | 승률 | 평균이익 | 평균손실 | 산술 기대값 | profit factor |");
  console.log("|---|---:|---:|---:|---:|---:|---:|");
  console.log(table("사이클 1건 = 거래 1건", summarise(cycleReturns)));
  console.log(table("부분익절 1건 = 거래 1건", summarise(ladder.map((t) => t.return))));
  console.log(table("└ 최종 청산분만", summarise(ladder.filter((t) => t.final).map((t) => t.return))));
  console.log(table("└ 부분익절분만", summarise(ladder.filter((t) => !t.final).map((t) => t.return))));

  const source = { winRate: 0.19, averageWin: 0.1282, averageLoss: -0.0409 };
  const expectancy = (rate) => rate * source.averageWin + (1 - rate) * source.averageLoss;
  console.log("\n## 원문 수치를 승률만 바꿔 계산\n");
  console.log("| 원문이 제시한 승률 | 평균이익 | 평균손실 | 산술 기대값 |");
  console.log("|---|---:|---:|---:|");
  console.log(`| 사이클 승률 19% | ${pct(source.averageWin)} | ${pct(source.averageLoss)} | ${pct(expectancy(0.19))} |`);
  console.log(`| 부분익절 포함 승률 59% | ${pct(source.averageWin)} | ${pct(source.averageLoss)} | ${pct(expectancy(0.59))} |`);
}

if (import.meta.url === new URL(`file://${process.argv[1]}`).href) main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
