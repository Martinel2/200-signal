# 알파(200티큐단) 앱용 전략 명세

QQQ SMA200 ±2%를 사용하는 별도 전략은 [200큐큐단(베타) 감시 명세](beta-strategy-spec.md)에 정의한다.

## 1. 목적과 범위

이 문서는 원문 재현 모드의 상태, 입력, 신호, 주문 제안을 정의한다. 초기 버전은 주문을 자동 실행하지 않고 사용자에게 행동을 안내한다.

## 2. 자산

| 역할 | 자산 |
|---|---|
| 신호 계산 | TQQQ |
| 상승 추세 핵심자산 | TQQQ |
| 익절금 및 후발 위험자산 | SPYM |
| 대피 및 현금성 자산 | SGOV |

## 3. 필수 입력

### 시장 데이터

- 미국 거래일
- TQQQ 일별 종가
- TQQQ SMA200
- TQQQ 최신 종가 확정 시각
- USD/KRW 환율
- SPYM 및 SGOV 가격

### 사용자 계좌

- TQQQ 보유수량
- TQQQ 매수별 수량과 달러 체결가
- 매수별 실제 적용환율
- 이미 완료한 익절 단계
- SPYM 및 SGOV 보유수량
- 사용 가능한 현금
- 사용 증권사와 소수점 거래 지원 여부
- 사용자 시간대

## 4. 상태

```text
SAFE
ENTRY_DAY_1
ENTRY_DAY_2
ENTRY_DAY_3
RISK_ON
EXIT_PENDING
LATE_ENTRY
DATA_ERROR
```

### SAFE

- TQQQ 종가가 SMA200 아래
- 기본 보유자산은 SGOV
- 다음 상향 돌파 대기

### ENTRY_DAY_1~3

- 상향 돌파 후 분할매수 진행
- 매 거래일 종가 확정 후 SMA200 위 여부 재확인

### RISK_ON

- 3분할 진입 완료
- TQQQ 보유 및 익절 단계 감시

### EXIT_PENDING

- 하향 이탈 확정
- TQQQ·SPYM 매도와 SGOV 전환을 사용자에게 제안

### LATE_ENTRY

- 정규 상향 돌파를 놓친 신규 사용자
- SPYM 50%·SGOV 50% 제안
- 앱 사용자 규칙: TQQQ 보유수량이 0이고 확정 종가가 SMA200 위면 별도 확인 없이 이 상태를 적용

### DATA_ERROR

- 종가 미확정, 결측, 중복 거래일, SMA 계산 불가 또는 데이터 제공자 간 불일치
- 매매 신호를 내지 않고 오류를 표시

## 5. 지표

```text
SMA200[t] = mean(close[t-199 ... t])
distance[t] = close[t] / SMA200[t] - 1
```

상향 돌파:

```text
close[t-1] <= SMA200[t-1] and close[t] > SMA200[t]
```

하향 이탈:

```text
close[t-1] >= SMA200[t-1] and close[t] < SMA200[t]
```

종가가 SMA200과 동일할 때의 원문 규칙은 확인되지 않았다. 앱은 동일값에서 신규 주문을 제안하지 않고 다음 확정 종가를 기다린다.

## 6. 진입 주문 제안

사이클 최초 가용자금을 `entry_capital`로 고정한다.

앱에서는 사용자가 `entry_capital`을 설정한 뒤 1·2·3차 실제 체결수량·체결가·환율·수수료를 TQQQ 거래 원장에 순서대로 기록한다. 다음 차수는 원장의 실제 사용금액을 차감한 잔액으로 계산하며, 이전 차수 체결 기록이 없으면 주문을 제안하지 않는다.

```text
day_1_budget = entry_capital / 3
day_2_budget = remaining_cash / 2
day_3_budget = remaining_cash
```

- 각 단계는 별도 미국 거래일이다.
- 휴장일은 일수에 포함하지 않는다.
- 분할 도중 하향 이탈하면 매수한 수량 전량 청산을 제안한다.
- 진입 도중 새로 들어온 돈은 이번 TQQQ 진입자금에 섞지 않는다.

## 7. 계좌 수익률

원문은 환율 반영 계좌 수익률을 사용한다. 잠정 공식:

```text
cost_krw =
  sum(buy_quantity_i × buy_price_usd_i × buy_fx_i)
  + allocated_fees_krw

market_value_krw =
  current_quantity × latest_price_usd × current_fx

profit_rate =
  (realized_tqqq_value_krw + market_value_krw - cost_krw)
  / cost_krw
```

실현된 부분익절을 수익률 단계 계산에 계속 포함할지는 원문이 불명확하다. 초기 구현 전 결정이 필요하다.

## 8. 익절 단계

```text
small_take_profit = [
  { threshold: 0.10, fraction_of_remaining: 0.10 },
  { threshold: 0.25, fraction_of_remaining: 0.10 },
  { threshold: 0.50, fraction_of_remaining: 0.10 }
]

large_take_profit:
  threshold = 1.00, 2.00, 3.00, 4.00, ...
  fraction_of_remaining = 0.50
```

- 각 단계는 사이클당 한 번만 발동한다.
- 한 거래일에 여러 단계를 건너뛴 경우 낮은 단계부터 순차 계산한다.
- 매도대금으로 SPYM 매수를 제안한다.
- 완료 단계는 이벤트 로그에 영구 기록한다.

### 정수 수량

원문 예시는 가장 가까운 정수 반올림으로 보인다.

```text
sell_quantity = round(current_quantity × fraction)
sell_quantity = min(sell_quantity, current_quantity)
```

최소 1주 규칙과 소수점 거래는 증권사 설정에 따라 분리한다.

## 9. 하향 이탈 주문 제안

1. TQQQ 전량 매도
2. SPYM 전량 매도
3. 매도 가능 현금으로 SGOV 매수
4. 사이클 종료

SGOV는 이미 보유한 수량을 유지한다.

## 10. 신규 자금

| 상태 | 목돈 | 소액 |
|---|---|---|
| SAFE | SGOV 100% | SGOV 100% |
| ENTRY | SPYM 50% + SGOV 50% | SPYM 또는 보류 |
| RISK_ON | SPYM 50% + SGOV 50% | SPYM 100% 허용 |
| LATE_ENTRY | SPYM 50% + SGOV 50% | SPYM 100% 허용 |

'목돈'과 '소액'의 경계는 원문에 없으므로 사용자가 직접 선택하게 한다.

## 11. 알림

- 종가 확정 및 지표 계산 완료 알림
- 상향 돌파 알림
- 분할매수 1·2·3일 차 알림
- 분할 도중 하향 이탈 알림
- 익절 단계 도달 알림
- 하향 이탈 및 전량청산 알림
- 데이터 오류 및 신호 보류 알림

각 알림은 다음을 포함한다.

- 기준 거래일
- 종가
- SMA200
- 이격률
- 현재 상태
- 제안 행동과 계산 수량
- 신호가 확정 종가 기준이라는 설명

## 12. 안전장치

- 장중 데이터로 확정 신호를 만들지 않는다.
- 데이터가 200거래일 미만이면 신호를 만들지 않는다.
- 이미 완료한 익절 단계를 중복 실행하지 않는다.
- 사용자가 실제 체결을 확인하기 전에는 다음 계좌 상태로 자동 전환하지 않는다.
- 주문 가능 시간과 증권사 지원 여부를 확인하지 못하면 '다음 지원 세션에서 실행'으로 표시한다.
- 자동매매는 별도 검증과 명시적 사용자 승인이 있기 전까지 제공하지 않는다.
