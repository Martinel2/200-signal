export const SMALL_MILESTONES = [0.1, 0.25, 0.5];

export function simpleMovingAverage(values, period) {
  if (!Array.isArray(values) || values.length < period || period <= 0) return [];
  const result = new Array(values.length).fill(null);
  let sum = 0;

  for (let index = 0; index < values.length; index += 1) {
    sum += values[index];
    if (index >= period) sum -= values[index - period];
    if (index >= period - 1) result[index] = sum / period;
  }

  return result;
}

export function deriveRegime(rows) {
  if (!Array.isArray(rows) || rows.length < 201) {
    return {
      state: "DATA_ERROR",
      label: "데이터 부족",
      tone: "neutral",
      reason: "SMA200 판정에는 최소 201거래일이 필요합니다."
    };
  }

  const closes = rows.map((row) => row.close);
  const averages = simpleMovingAverage(closes, 200);
  const latestIndex = rows.length - 1;
  const latest = rows[latestIndex];
  const previous = rows[latestIndex - 1];
  const latestSma = averages[latestIndex];
  const previousSma = averages[latestIndex - 1];

  if (![latest.close, previous.close, latestSma, previousSma].every(Number.isFinite)) {
    return {
      state: "DATA_ERROR",
      label: "데이터 오류",
      tone: "neutral",
      reason: "종가 또는 SMA200 값이 올바르지 않습니다."
    };
  }

  const crossUp = previous.close <= previousSma && latest.close > latestSma;
  const crossDown = previous.close >= previousSma && latest.close < latestSma;

  if (crossDown) {
    return {
      state: "EXIT_PENDING",
      label: "대피 신호",
      tone: "danger",
      reason: "확정 종가가 SMA200을 아래로 이탈했습니다."
    };
  }

  if (crossUp) {
    return {
      state: "ENTRY_DAY_1",
      label: "1차 진입",
      tone: "positive",
      reason: "확정 종가가 SMA200을 위로 돌파했습니다."
    };
  }

  if (latest.close === latestSma) {
    return {
      state: "WAIT",
      label: "경계 대기",
      tone: "warning",
      reason: "종가와 SMA200이 같습니다. 방향이 확정될 때까지 기다립니다."
    };
  }

  if (latest.close > latestSma) {
    let mostRecentCrossUp = -1;
    let mostRecentCrossDown = -1;

    for (let index = 200; index <= latestIndex; index += 1) {
      const priorClose = rows[index - 1].close;
      const priorSma = averages[index - 1];
      const close = rows[index].close;
      const sma = averages[index];
      if (priorClose <= priorSma && close > sma) mostRecentCrossUp = index;
      if (priorClose >= priorSma && close < sma) mostRecentCrossDown = index;
    }

    const sessionsSinceEntry = latestIndex - mostRecentCrossUp;
    if (mostRecentCrossUp > mostRecentCrossDown && sessionsSinceEntry === 1) {
      return {
        state: "ENTRY_DAY_2",
        label: "2차 진입",
        tone: "positive",
        reason: "상향 돌파 후 두 번째 거래일이며 종가가 SMA200 위에 있습니다."
      };
    }
    if (mostRecentCrossUp > mostRecentCrossDown && sessionsSinceEntry === 2) {
      return {
        state: "ENTRY_DAY_3",
        label: "3차 진입",
        tone: "positive",
        reason: "상향 돌파 후 세 번째 거래일이며 종가가 SMA200 위에 있습니다."
      };
    }

    return {
      state: "RISK_ON",
      label: "TQQQ 보유",
      tone: "positive",
      reason: "확정 종가가 SMA200 위에 있습니다."
    };
  }

  return {
    state: "SAFE",
    label: "SGOV 대기",
    tone: "safe",
    reason: "확정 종가가 SMA200 아래에 있습니다."
  };
}

export function buildMarketSnapshot(rows) {
  const validRows = rows.filter((row) => Number.isFinite(row.close));
  const closes = validRows.map((row) => row.close);
  const averages = simpleMovingAverage(closes, 200);
  const regime = deriveRegime(validRows);
  const latestIndex = validRows.length - 1;
  const previousIndex = latestIndex - 1;
  const latest = validRows[latestIndex];
  const previous = validRows[previousIndex];
  const latestSma = averages[latestIndex];
  const previousSma = averages[previousIndex];

  const series = validRows.slice(-260).map((row, sliceIndex) => {
    const originalIndex = validRows.length - Math.min(260, validRows.length) + sliceIndex;
    return {
      date: row.date,
      close: row.close,
      sma200: averages[originalIndex]
    };
  });

  return {
    regime,
    latest: {
      date: latest?.date,
      close: latest?.close,
      sma200: latestSma,
      distance: latestSma ? latest.close / latestSma - 1 : null
    },
    previous: {
      date: previous?.date,
      close: previous?.close,
      sma200: previousSma
    },
    series,
    rowCount: validRows.length
  };
}

export function calculateProfitRate({ averagePriceUsd, buyFx, currentPriceUsd, currentFx }) {
  const values = [averagePriceUsd, buyFx, currentPriceUsd, currentFx].map(Number);
  if (values.some((value) => !Number.isFinite(value) || value <= 0)) return null;
  const [averagePrice, entryFx, currentPrice, fx] = values;
  return (currentPrice * fx) / (averagePrice * entryFx) - 1;
}

export function buildMilestones(profitRate) {
  if (!Number.isFinite(profitRate)) return SMALL_MILESTONES;
  const largeCount = Math.max(3, Math.floor(Math.max(profitRate, 3)));
  const large = Array.from({ length: largeCount }, (_, index) => index + 1);
  return [...SMALL_MILESTONES, ...large];
}

export function calculateTakeProfitPlan({
  quantity,
  profitRate,
  completed = [],
  fractional = false
}) {
  let remaining = Number(quantity);
  if (!Number.isFinite(remaining) || remaining <= 0 || !Number.isFinite(profitRate)) {
    return { orders: [], totalToSell: 0, remaining: Math.max(0, remaining || 0) };
  }

  const completedSet = new Set(completed.map(Number));
  const reached = buildMilestones(profitRate)
    .filter((threshold) => profitRate >= threshold && !completedSet.has(threshold))
    .sort((a, b) => a - b);

  const orders = [];
  for (const threshold of reached) {
    const fraction = threshold < 1 ? 0.1 : 0.5;
    const raw = remaining * fraction;
    const sell = fractional ? Number(raw.toFixed(6)) : Math.round(raw);
    const bounded = Math.min(remaining, Math.max(sell, fractional ? 0.000001 : 1));
    if (bounded <= 0) continue;
    orders.push({ threshold, fraction, quantity: bounded });
    remaining = Number((remaining - bounded).toFixed(6));
  }

  return {
    orders,
    totalToSell: Number((Number(quantity) - remaining).toFixed(6)),
    remaining
  };
}

export function actionForState(state) {
  const actions = {
    SAFE: "SGOV를 유지하고 다음 상향 돌파를 기다리세요.",
    ENTRY_DAY_1: "가용 진입자금의 1/3만 TQQQ 매수를 검토하세요.",
    ENTRY_DAY_2: "남은 진입 현금의 1/2로 2차 매수를 검토하세요.",
    ENTRY_DAY_3: "남은 진입 현금으로 3차 매수를 검토하세요.",
    RISK_ON: "TQQQ를 보유하고 다음 익절 단계와 하향 이탈을 감시하세요.",
    EXIT_PENDING: "TQQQ와 SPYM 전량 매도 후 SGOV 전환을 검토하세요.",
    WAIT: "방향이 확정될 때까지 새로운 주문을 보류하세요.",
    DATA_ERROR: "데이터를 확인하기 전에는 행동하지 마세요."
  };
  return actions[state] || actions.DATA_ERROR;
}
