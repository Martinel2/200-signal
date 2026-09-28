import test from "node:test";
import assert from "node:assert/strict";
import {
  buildMarketSnapshot,
  calculateProfitRate,
  calculateTakeProfitPlan,
  deriveRegime,
  simpleMovingAverage
} from "../public/strategy.js";

function rowsFromCloses(closes) {
  return closes.map((close, index) => ({
    date: `2026-01-${String(index + 1).padStart(2, "0")}`,
    close
  }));
}

test("SMA는 지정 기간의 단순평균을 계산한다", () => {
  assert.deepEqual(simpleMovingAverage([1, 2, 3, 4], 3), [null, null, 2, 3]);
});

test("상향 돌파는 1차 진입 신호가 된다", () => {
  const closes = [...Array(200).fill(100), 99, 101];
  const regime = deriveRegime(rowsFromCloses(closes));
  assert.equal(regime.state, "ENTRY_DAY_1");
});

test("하향 이탈은 대피 신호가 된다", () => {
  const closes = [...Array(200).fill(100), 101, 99];
  const regime = deriveRegime(rowsFromCloses(closes));
  assert.equal(regime.state, "EXIT_PENDING");
});

test("상향 돌파 다음 거래일은 2차 진입이다", () => {
  const closes = [...Array(200).fill(100), 99, 101, 102];
  const regime = deriveRegime(rowsFromCloses(closes));
  assert.equal(regime.state, "ENTRY_DAY_2");
});

test("환율 반영 수익률을 계산한다", () => {
  const result = calculateProfitRate({
    averagePriceUsd: 50,
    buyFx: 1300,
    currentPriceUsd: 60,
    currentFx: 1400
  });
  assert.ok(Math.abs(result - 0.2923076923) < 1e-9);
});

test("소익절은 남은 수량의 10%씩 순차 계산한다", () => {
  const plan = calculateTakeProfitPlan({
    quantity: 100,
    profitRate: 0.3,
    completed: [],
    fractional: false
  });
  assert.deepEqual(plan.orders.map((order) => order.quantity), [10, 9]);
  assert.equal(plan.remaining, 81);
});

test("완료한 익절 단계는 반복하지 않는다", () => {
  const plan = calculateTakeProfitPlan({
    quantity: 90,
    profitRate: 0.3,
    completed: [0.1],
    fractional: false
  });
  assert.deepEqual(plan.orders.map((order) => order.threshold), [0.25]);
});

test("대익절은 남은 수량의 50%를 반올림한다", () => {
  const plan = calculateTakeProfitPlan({
    quantity: 73,
    profitRate: 1.1,
    completed: [0.1, 0.25, 0.5],
    fractional: false
  });
  assert.equal(plan.orders[0].quantity, 37);
  assert.equal(plan.remaining, 36);
});

test("시장 스냅샷은 최신 SMA와 시계열을 만든다", () => {
  const rows = rowsFromCloses([...Array(210).fill(100), 101]);
  const snapshot = buildMarketSnapshot(rows);
  assert.equal(snapshot.latest.close, 101);
  assert.ok(Number.isFinite(snapshot.latest.sma200));
  assert.ok(snapshot.series.length > 0);
});
