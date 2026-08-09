# 信贷核心-还款申请接口 V2.3

## 接口概述

| 项目 | 说明 |
|------|------|
| 接口名称 | 还款申请 |
| 接口编号 | LN_REPAY_V2 |
| 请求方式 | POST |
| Content-Type | application/json;charset=UTF-8 |
| 字符编码 | UTF-8 |
| 调用方 | 合作方系统 |

## 业务说明

本接口用于合作方向我方信贷核心系统提交还款申请。支持以下还款方式：
1. 按期还款（正常还款计划）
2. 提前结清（还清剩余全部本息）
3. 部分提前还款（还款金额大于当期应还，但未结清）
4. 逾期还款（包含罚息和违约金）

还款顺序：先还罚息 → 再还利息 → 最后还本金。

## 公共请求头

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| X-Req-Id | String(32) | Y | 请求流水号，全局唯一 |
| X-Req-Time | String(19) | Y | 请求时间，格式 yyyy-MM-dd HH:mm:ss |
| X-Sign-Type | String(10) | Y | 签名算法，固定值 RSA2 |
| X-Sign | String(256) | Y | 请求签名，签名规则见文档附录A |
| X-Partner-Id | String(16) | Y | 合作方编码 |

## 请求参数

### 顶层字段

| 参数名 | 类型 | 必填 | 字段说明 |
|--------|------|------|----------|
| loanNo | String(20) | Y | 借据编号 |
| repayType | String(2) | Y | 还款类型：01-按期 02-提前结清 03-部分提前 04-逾期 |
| repayAmount | Number(16,2) | Y | 还款总金额（单位：元） |
| repayCurrency | String(3) | N | 币种，默认 CNY |
| repayDate | String(8) | N | 还款日期，格式 yyyyMMdd，默认当天 |
| repayAccount | Object | Y | 还款账户信息，结构见下方 |
| feeDetail | Object | N | 费用明细（逾期场景必填），结构见下方 |
| attach | String(200) | N | 附加信息，原样返回 |

### repayAccount（还款账户信息）

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| bankCode | String(12) | Y | 银行联行号 |
| bankName | String(50) | Y | 开户行名称 |
| cardNo | String(19) | Y | 银行卡号 |
| accountName | String(30) | Y | 户名 |
| idType | String(2) | Y | 证件类型：01-身份证 02-护照 03-营业执照 |
| idNo | String(30) | Y | 证件号码 |
| mobile | String(11) | Y | 银行预留手机号 |

### feeDetail（费用明细）

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| principal | Number(16,2) | Y | 应还本金 |
| interest | Number(16,2) | Y | 应还利息 |
| penalty | Number(16,2) | N | 罚息金额 |
| lateFee | Number(16,2) | N | 违约金 |
| otherFee | Number(16,2) | N | 其他费用 |
| repayPeriods | Array | N | 还款期次明细，逾期或部分还款时必填 |

### repayPeriods[]（还款期次明细）

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| periodNo | Number(3) | Y | 期次编号 |
| periodStart | String(8) | Y | 当期起息日 yyyyMMdd |
| periodEnd | String(8) | Y | 当期止息日 yyyyMMdd |
| principalAmt | Number(16,2) | Y | 当期本金 |
| interestAmt | Number(16,2) | Y | 当期利息 |
| penaltyAmt | Number(16,2) | N | 当期罚息 |
| paidAmt | Number(16,2) | Y | 已还金额 |

## 响应参数

### 成功响应

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| code | String(4) | Y | 响应码，0000 表示成功 |
| msg | String(64) | Y | 响应信息 |
| seqNo | String(32) | Y | 我方流水号 |
| tradeTime | String(19) | Y | 交易完成时间 yyyy-MM-dd HH:mm:ss |
| repayResult | Object | N | 还款结果（code=0000时返回） |

### repayResult（还款结果）

| 参数名 | 类型 | 说明 |
|--------|------|------|
| repayNo | String(20) | 还款单号 |
| loanNo | String(20) | 借据编号 |
| actualAmount | Number(16,2) | 实际扣款金额 |
| repayStatus | String(2) | 还款结果：01-成功 02-处理中 03-失败 |
| failReason | String(100) | 失败原因（repayStatus=03时返回） |
| principalPaid | Number(16,2) | 已还本金 |
| interestPaid | Number(16,2) | 已还利息 |
| penaltyPaid | Number(16,2) | 已还罚息 |
| remainPrincipal | Number(16,2) | 剩余本金 |
| waitRepayDate | String(8) | 下一还款日（未结清时返回） |

### 失败响应

| 参数名 | 类型 | 说明 |
|--------|------|------|
| code | String(4) | 非0000的错误码 |
| msg | String(64) | 错误描述 |
| seqNo | String(32) | 我方流水号 |

常见错误码：
- 1001：借据不存在
- 1002：借据状态不允许还款
- 1003：还款金额与应还金额不匹配
- 1004：还款账户校验失败
- 1005：签名校验失败
- 2001：重复请求（相同X-Req-Id）
- 9999：系统错误

## 请求示例

```json
{
  "loanNo": "LN2024010101001",
  "repayType": "04",
  "repayAmount": 10580.50,
  "repayCurrency": "CNY",
  "repayDate": "20240315",
  "repayAccount": {
    "bankCode": "102100099996",
    "bankName": "中国工商银行北京分行",
    "cardNo": "6222021234567890123",
    "accountName": "张三",
    "idType": "01",
    "idNo": "110101199001011234",
    "mobile": "13800138000"
  },
  "feeDetail": {
    "principal": 10000.00,
    "interest": 350.00,
    "penalty": 180.50,
    "lateFee": 50.00,
    "otherFee": 0.00,
    "repayPeriods": [
      {
        "periodNo": 3,
        "periodStart": "20240201",
        "periodEnd": "20240229",
        "principalAmt": 5000.00,
        "interestAmt": 175.00,
        "penaltyAmt": 90.25,
        "paidAmt": 0.00
      },
      {
        "periodNo": 4,
        "periodStart": "20240301",
        "periodEnd": "20240331",
        "principalAmt": 5000.00,
        "interestAmt": 175.00,
        "penaltyAmt": 90.25,
        "paidAmt": 0.00
      }
    ]
  },
  "attach": "附言信息"
}
```

## 响应示例（成功）

```json
{
  "code": "0000",
  "msg": "success",
  "seqNo": "REP20240315001",
  "tradeTime": "2024-03-15 14:30:25",
  "repayResult": {
    "repayNo": "RP2024031500001",
    "loanNo": "LN2024010101001",
    "actualAmount": 10580.50,
    "repayStatus": "01",
    "principalPaid": 10000.00,
    "interestPaid": 350.00,
    "penaltyPaid": 180.50,
    "remainPrincipal": 0.00,
    "waitRepayDate": null
  }
}
```
