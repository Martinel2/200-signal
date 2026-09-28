import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { extname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";
import { buildMarketSnapshot } from "./public/strategy.js";

const root = fileURLToPath(new URL("./public/", import.meta.url));
const port = Number(process.env.PORT || 4173);

const mimeTypes = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".ico": "image/x-icon"
};

const json = (response, status, value) => {
  response.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store"
  });
  response.end(JSON.stringify(value));
};

async function fetchYahooChart(symbol, range = "2y") {
  const encoded = encodeURIComponent(symbol);
  const url =
    `https://query1.finance.yahoo.com/v8/finance/chart/${encoded}` +
    `?range=${range}&interval=1d&events=div%2Csplits&includeAdjustedClose=true`;

  const result = await fetch(url, {
    headers: {
      "user-agent": "Mozilla/5.0 TQQQ-Signal-Guide/0.1",
      accept: "application/json"
    },
    signal: AbortSignal.timeout(12_000)
  });

  if (!result.ok) {
    throw new Error(`${symbol} 데이터 응답 오류 (${result.status})`);
  }

  const payload = await result.json();
  const chart = payload?.chart?.result?.[0];
  const error = payload?.chart?.error;
  if (error || !chart?.timestamp?.length) {
    throw new Error(error?.description || `${symbol} 데이터가 비어 있습니다.`);
  }

  const quote = chart.indicators?.quote?.[0];
  const adjusted = chart.indicators?.adjclose?.[0]?.adjclose;
  const timezone = chart.meta?.exchangeTimezoneName || "America/New_York";

  const rows = chart.timestamp
    .map((timestamp, index) => ({
      timestamp,
      date: new Intl.DateTimeFormat("en-CA", {
        timeZone: timezone,
        year: "numeric",
        month: "2-digit",
        day: "2-digit"
      }).format(new Date(timestamp * 1000)),
      close: quote?.close?.[index],
      adjustedClose: adjusted?.[index]
    }))
    .filter(
      (row) =>
        Number.isFinite(row.close) &&
        Number.isFinite(row.adjustedClose)
    );

  return {
    symbol,
    currency: chart.meta?.currency,
    exchange: chart.meta?.exchangeName,
    timezone,
    marketPrice: chart.meta?.regularMarketPrice,
    marketTime: chart.meta?.regularMarketTime,
    rows
  };
}

function createDemoData() {
  const start = new Date("2025-07-01T20:00:00Z");
  const rows = [];
  let price = 54;
  let cursor = new Date(start);

  while (rows.length < 320) {
    const weekday = cursor.getUTCDay();
    if (weekday !== 0 && weekday !== 6) {
      const index = rows.length;
      const trend = index < 210 ? 0.0008 : index < 285 ? -0.0017 : 0.005;
      const wave = Math.sin(index / 7) * 0.006 + Math.cos(index / 19) * 0.003;
      price = Math.max(5, price * (1 + trend + wave));
      rows.push({
        timestamp: Math.floor(cursor.getTime() / 1000),
        date: cursor.toISOString().slice(0, 10),
        close: Number(price.toFixed(4)),
        adjustedClose: Number(price.toFixed(4))
      });
    }
    cursor.setUTCDate(cursor.getUTCDate() + 1);
  }

  return {
    symbol: "TQQQ",
    currency: "USD",
    exchange: "DEMO",
    timezone: "America/New_York",
    marketPrice: rows.at(-1).close,
    marketTime: rows.at(-1).timestamp,
    rows
  };
}

async function marketPayload(demo = false) {
  if (demo) {
    const chart = createDemoData();
    return {
      ...buildMarketSnapshot(chart.rows),
      prices: {
        TQQQ: chart.rows.at(-1).close,
        SPYM: 74.21,
        SGOV: 100.54,
        USDKRW: 1382.45
      },
      meta: {
        source: "내장 데모 데이터",
        demo: true,
        fetchedAt: new Date().toISOString(),
        timezone: chart.timezone
      }
    };
  }

  const [tqqq, spym, sgov, fx] = await Promise.all([
    fetchYahooChart("TQQQ", "2y"),
    fetchYahooChart("SPYM", "5d"),
    fetchYahooChart("SGOV", "5d"),
    fetchYahooChart("KRW=X", "5d")
  ]);

  return {
    ...buildMarketSnapshot(tqqq.rows),
    prices: {
      TQQQ: tqqq.rows.at(-1).close,
      SPYM: spym.rows.at(-1).close,
      SGOV: sgov.rows.at(-1).close,
      USDKRW: fx.rows.at(-1).close
    },
    meta: {
      source: "Yahoo Finance 비공식 Chart API",
      demo: false,
      fetchedAt: new Date().toISOString(),
      timezone: tqqq.timezone,
      priceType: "종가",
      adjustmentNote: "SMA는 원문 표현에 맞춰 비수정 종가로 계산"
    }
  };
}

async function serveFile(requestPath, response) {
  const requested = requestPath === "/" ? "/index.html" : requestPath;
  const safePath = normalize(requested).replace(/^(\.\.[/\\])+/, "");
  const filePath = join(root, safePath);

  if (!filePath.startsWith(root)) {
    response.writeHead(403);
    response.end("Forbidden");
    return;
  }

  try {
    const body = await readFile(filePath);
    response.writeHead(200, {
      "content-type": mimeTypes[extname(filePath)] || "application/octet-stream",
      "cache-control": extname(filePath) === ".html" ? "no-cache" : "public, max-age=300"
    });
    response.end(body);
  } catch {
    response.writeHead(404);
    response.end("Not found");
  }
}

const server = createServer(async (request, response) => {
  const url = new URL(request.url, `http://${request.headers.host || "localhost"}`);

  if (url.pathname === "/api/health") {
    json(response, 200, { ok: true, now: new Date().toISOString() });
    return;
  }

  if (url.pathname === "/api/market") {
    try {
      const payload = await marketPayload(url.searchParams.get("demo") === "1");
      json(response, 200, payload);
    } catch (error) {
      json(response, 502, {
        error: "시장 데이터를 불러오지 못했습니다.",
        detail: error instanceof Error ? error.message : String(error),
        canUseDemo: true
      });
    }
    return;
  }

  await serveFile(decodeURIComponent(url.pathname), response);
});

server.listen(port, "127.0.0.1", () => {
  console.log(`TQQQ Signal Guide running at http://127.0.0.1:${port}`);
});
