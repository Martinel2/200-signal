import {
  actionForState,
  buildMilestones,
  calculateProfitRate,
  calculateTakeProfitPlan
} from "./strategy.js";

const $ = (selector) => document.querySelector(selector);

const elements = {
  refreshButton: $("#refreshButton"),
  demoButton: $("#demoButton"),
  dataStatus: $("#dataStatus"),
  signalCard: $("#signalCard"),
  signalDate: $("#signalDate"),
  signalBadge: $("#signalBadge"),
  signalLabel: $("#signalLabel"),
  signalArrow: $("#signalArrow"),
  signalAction: $("#signalAction"),
  signalReason: $("#signalReason"),
  closePrice: $("#closePrice"),
  smaPrice: $("#smaPrice"),
  distanceValue: $("#distanceValue"),
  distanceLabel: $("#distanceLabel"),
  sourceName: $("#sourceName"),
  updatedAt: $("#updatedAt"),
  chart: $("#priceChart"),
  accountForm: $("#accountForm"),
  quantityInput: $("#quantityInput"),
  averagePriceInput: $("#averagePriceInput"),
  buyFxInput: $("#buyFxInput"),
  fractionalInput: $("#fractionalInput"),
  milestoneChecks: $("#milestoneChecks"),
  profitRate: $("#profitRate"),
  profitExplanation: $("#profitExplanation"),
  accountAction: $("#accountAction"),
  orderPlan: $("#orderPlan")
};

let market = null;
let usingDemo = false;

const usd = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "USD",
  minimumFractionDigits: 2,
  maximumFractionDigits: 2
});

const percent = new Intl.NumberFormat("ko-KR", {
  style: "percent",
  signDisplay: "always",
  minimumFractionDigits: 2,
  maximumFractionDigits: 2
});

const number = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 6 });

function setStatus(kind, message) {
  elements.dataStatus.className = `data-status ${kind}`;
  elements.dataStatus.querySelector("span:last-child").textContent = message;
}

function formatKoreanDate(dateValue) {
  if (!dateValue) return "—";
  return new Intl.DateTimeFormat("ko-KR", {
    year: "numeric",
    month: "long",
    day: "numeric",
    weekday: "short"
  }).format(new Date(`${dateValue}T12:00:00Z`));
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function renderSignal() {
  const { regime, latest, meta } = market;
  elements.signalCard.dataset.tone = regime.tone;
  elements.signalDate.textContent = formatKoreanDate(latest.date);
  elements.signalBadge.textContent = meta.demo ? "DEMO · 종가 기준" : "LIVE · 종가 기준";
  elements.signalLabel.textContent = regime.label;
  elements.signalArrow.textContent = regime.state === "EXIT_PENDING" ? "↘" : regime.state === "SAFE" ? "○" : "→";
  elements.signalAction.textContent = actionForState(regime.state);
  elements.signalReason.textContent = regime.reason;
  elements.closePrice.textContent = Number.isFinite(latest.close) ? usd.format(latest.close) : "—";
  elements.smaPrice.textContent = Number.isFinite(latest.sma200) ? usd.format(latest.sma200) : "—";
  elements.distanceValue.textContent = Number.isFinite(latest.distance) ? percent.format(latest.distance) : "—";
  elements.distanceLabel.textContent =
    latest.distance >= 0 ? "기준선보다 위" : "기준선보다 아래";
  elements.sourceName.textContent = meta.source;
  elements.updatedAt.textContent = new Intl.DateTimeFormat("ko-KR", {
    dateStyle: "medium",
    timeStyle: "short"
  }).format(new Date(meta.fetchedAt));
  setStatus("live", meta.demo ? "데모 데이터 사용 중" : "종가 데이터 연결됨");
}

function pathFor(points) {
  return points.map((point, index) => `${index === 0 ? "M" : "L"} ${point.x.toFixed(2)} ${point.y.toFixed(2)}`).join(" ");
}

function renderChart() {
  const series = market.series.filter((row) => Number.isFinite(row.close) && Number.isFinite(row.sma200));
  if (series.length < 2) return;

  const width = 1000;
  const height = 330;
  const padding = { top: 20, right: 24, bottom: 42, left: 18 };
  const values = series.flatMap((row) => [row.close, row.sma200]);
  const min = Math.min(...values);
  const max = Math.max(...values);
  const spread = Math.max(1, max - min);
  const chartMin = min - spread * 0.1;
  const chartMax = max + spread * 0.1;
  const x = (index) => padding.left + (index / (series.length - 1)) * (width - padding.left - padding.right);
  const y = (value) => padding.top + ((chartMax - value) / (chartMax - chartMin)) * (height - padding.top - padding.bottom);

  const pricePoints = series.map((row, index) => ({ x: x(index), y: y(row.close) }));
  const smaPoints = series.map((row, index) => ({ x: x(index), y: y(row.sma200) }));
  const areaPath = `${pathFor(pricePoints)} L ${pricePoints.at(-1).x} ${height - padding.bottom} L ${pricePoints[0].x} ${height - padding.bottom} Z`;

  const grid = Array.from({ length: 4 }, (_, index) => {
    const value = chartMin + ((chartMax - chartMin) * index) / 3;
    const yValue = y(value);
    return `
      <line x1="${padding.left}" x2="${width - padding.right}" y1="${yValue}" y2="${yValue}" stroke="rgba(17,25,20,.1)" stroke-width="1"/>
      <text x="${width - padding.right}" y="${yValue - 7}" text-anchor="end" fill="#798078" font-size="11" font-family="IBM Plex Mono">${usd.format(value)}</text>
    `;
  }).join("");

  const dateIndexes = [0, Math.floor((series.length - 1) / 2), series.length - 1];
  const dateLabels = dateIndexes.map((index) => `
    <text x="${x(index)}" y="${height - 12}" text-anchor="${index === 0 ? "start" : index === series.length - 1 ? "end" : "middle"}" fill="#798078" font-size="11" font-family="IBM Plex Mono">${series[index].date.slice(2)}</text>
  `).join("");

  elements.chart.innerHTML = `
    <defs>
      <linearGradient id="priceArea" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" stop-color="#cbff3d" stop-opacity=".25"/>
        <stop offset="100%" stop-color="#cbff3d" stop-opacity="0"/>
      </linearGradient>
    </defs>
    ${grid}
    <path d="${areaPath}" fill="url(#priceArea)"/>
    <path d="${pathFor(smaPoints)}" fill="none" stroke="#8bb818" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>
    <path d="${pathFor(pricePoints)}" fill="none" stroke="#111914" stroke-width="3.5" stroke-linecap="round" stroke-linejoin="round"/>
    <circle cx="${pricePoints.at(-1).x}" cy="${pricePoints.at(-1).y}" r="6" fill="#111914" stroke="#cbff3d" stroke-width="3"/>
    ${dateLabels}
  `;
}

function savedAccount() {
  try {
    return JSON.parse(localStorage.getItem("tqqq-account") || "{}");
  } catch {
    return {};
  }
}

function completedMilestones() {
  return [...elements.milestoneChecks.querySelectorAll("input:checked")].map((input) => Number(input.value));
}

function renderMilestoneChecks(profitRate = 3) {
  const previous = new Set(completedMilestones());
  const stored = new Set((savedAccount().completed || []).map(Number));
  const milestones = buildMilestones(profitRate);
  elements.milestoneChecks.innerHTML = milestones
    .map((threshold) => {
      const checked = previous.has(threshold) || stored.has(threshold) ? "checked" : "";
      return `
        <label>
          <input type="checkbox" value="${threshold}" ${checked}/>
          <span>+${Math.round(threshold * 100)}%</span>
        </label>
      `;
    })
    .join("");
}

function saveAndCalculate(event) {
  event?.preventDefault();
  if (!market) return;

  const account = {
    quantity: Number(elements.quantityInput.value),
    averagePriceUsd: Number(elements.averagePriceInput.value),
    buyFx: Number(elements.buyFxInput.value),
    fractional: elements.fractionalInput.checked,
    completed: completedMilestones()
  };

  localStorage.setItem("tqqq-account", JSON.stringify(account));
  const profitRate = calculateProfitRate({
    averagePriceUsd: account.averagePriceUsd,
    buyFx: account.buyFx,
    currentPriceUsd: market.prices.TQQQ,
    currentFx: market.prices.USDKRW
  });

  if (!Number.isFinite(profitRate) || !Number.isFinite(account.quantity) || account.quantity <= 0) {
    elements.profitRate.textContent = "입력 확인";
    elements.profitExplanation.textContent = "보유 수량·평균단가·매수환율을 모두 입력해 주세요.";
    elements.accountAction.textContent = actionForState(market.regime.state);
    elements.orderPlan.innerHTML = "";
    return;
  }

  renderMilestoneChecks(profitRate);
  elements.profitRate.textContent = percent.format(profitRate);
  elements.profitExplanation.textContent =
    `현재 $${market.prices.TQQQ.toFixed(2)} · 환율 ${Math.round(market.prices.USDKRW).toLocaleString("ko-KR")}원 기준`;

  if (market.regime.state === "EXIT_PENDING" || market.regime.state === "SAFE") {
    elements.accountAction.textContent =
      market.regime.state === "EXIT_PENDING"
        ? `TQQQ ${number.format(account.quantity)}주와 SPYM 전량 청산을 검토하세요.`
        : "현재는 SGOV 대기 구간입니다. 신규 TQQQ 매수를 기다리세요.";
    elements.orderPlan.innerHTML = `
      <div class="order-row"><span>시장 상태</span><strong>${escapeHtml(market.regime.label)}</strong></div>
      <div class="order-row"><span>원문 기본 행동</span><strong>${market.regime.state === "EXIT_PENDING" ? "SGOV 전환" : "대기"}</strong></div>
    `;
    return;
  }

  const plan = calculateTakeProfitPlan({
    quantity: account.quantity,
    profitRate,
    completed: account.completed,
    fractional: account.fractional
  });

  if (plan.orders.length === 0) {
    const next = buildMilestones(profitRate).find((threshold) => threshold > profitRate && !account.completed.includes(threshold));
    elements.accountAction.textContent =
      market.regime.state.startsWith("ENTRY")
        ? actionForState(market.regime.state)
        : "TQQQ를 보유하고 다음 조건을 기다리세요.";
    elements.orderPlan.innerHTML = `
      <div class="order-row"><span>다음 익절 단계</span><strong>${next ? `+${Math.round(next * 100)}%` : "없음"}</strong></div>
      <div class="order-row"><span>하향 이탈 시</span><strong>TQQQ·SPYM 청산</strong></div>
    `;
    return;
  }

  elements.accountAction.textContent =
    `누락된 단계에 따라 TQQQ 총 ${number.format(plan.totalToSell)}주 익절을 검토하세요.`;
  elements.orderPlan.innerHTML = plan.orders
    .map(
      (order) => `
        <div class="order-row">
          <span>+${Math.round(order.threshold * 100)}% 최초 도달</span>
          <strong>${number.format(order.quantity)}주 → SPYM</strong>
        </div>
      `
    )
    .join("") +
    `<div class="order-row"><span>익절 후 예상 잔여</span><strong>${number.format(plan.remaining)}주</strong></div>`;
}

function restoreAccount() {
  const account = savedAccount();
  if (account.quantity) elements.quantityInput.value = account.quantity;
  if (account.averagePriceUsd) elements.averagePriceInput.value = account.averagePriceUsd;
  if (account.buyFx) elements.buyFxInput.value = account.buyFx;
  elements.fractionalInput.checked = Boolean(account.fractional);
  renderMilestoneChecks(3);
}

async function loadMarket(demo = false) {
  usingDemo = demo;
  setStatus("", "데이터 불러오는 중");
  elements.refreshButton.disabled = true;

  try {
    const response = await fetch(`/api/market${demo ? "?demo=1" : ""}`);
    const payload = await response.json();
    if (!response.ok) throw new Error(payload.detail || payload.error);
    market = payload;
    renderSignal();
    renderChart();
    saveAndCalculate();
  } catch (error) {
    market = null;
    setStatus("error", "데이터 연결 실패");
    elements.signalLabel.textContent = "신호 보류";
    elements.signalAction.textContent = "시장 데이터를 확인할 수 없어 행동 신호를 만들지 않았습니다.";
    elements.signalReason.textContent = error.message;
    elements.signalCard.dataset.tone = "danger";
    elements.sourceName.textContent = "연결 실패 · 데모 사용 가능";
  } finally {
    elements.refreshButton.disabled = false;
  }
}

elements.refreshButton.addEventListener("click", () => loadMarket(usingDemo));
elements.demoButton.addEventListener("click", () => {
  loadMarket(true);
  document.querySelector("#signal").scrollIntoView({ behavior: "smooth" });
});
elements.accountForm.addEventListener("submit", saveAndCalculate);
elements.milestoneChecks.addEventListener("change", saveAndCalculate);

restoreAccount();
loadMarket(false);
