# 招商银行 — 信贷放款接口文档

> 版本: V2.1 | 日期: 2026-07-15

---

## 接口概述

| 项目 | 说明 |
|------|------|
| 接口名称 | 信贷放款申请 |
| 请求地址 | POST /api/credit/loan/apply |
| 请求方式 | POST |
| 报文格式 | JSON |
| 加密方式 | SM4/CBC/PKCS5Padding |
| 签名算法 | SM2 with SM3 |
| 字符编码 | UTF-8 |

---

## 请求参数

### 公共请求头

| 字段名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| appId | String(32) | 是 | 平台分配的接入方ID |
| reqTime | String(14) | 是 | 请求时间，格式yyyyMMddHHmmss |
| sign | String(256) | 是 | SM2签名值 |
| serialNo | String(64) | 是 | 请求流水号，每次请求唯一 |

### 业务请求体

| 字段名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| loanNo | String(32) | 是 | 贷款编号，合作方唯一 |
| custName | String(64) | 是 | 客户姓名 |
| certType | String(2) | 是 | 证件类型：01-身份证 02-护照 03-港澳通行证 |
| certNo | String(32) | 是 | 证件号码 |
| mobile | String(11) | 是 | 手机号 |
| applyAmount | String(16) | 是 | 申请金额(元)，如"100000.00" |
| loanTerm | String(4) | 是 | 贷款期限(月)，如"12" |
| loanRate | String(8) | 是 | 年化利率，如"5.6000" |
| rateType | String(1) | 是 | 利率类型：1-固定 2-浮动 |
| repayMethod | String(2) | 是 | 还款方式：01-等额本息 02-等额本金 03-先息后本 |
| loanPurpose | String(4) | 是 | 贷款用途：1001-消费 1002-装修 1003-教育 1004-医疗 |
| bankCardNo | String(19) | 是 | 收款银行卡号 |
| bankCode | String(8) | 是 | 收款银行联行号 |
| bankName | String(64) | 是 | 收款银行名称 |
| annualIncome | String(12) | 否 | 年收入(元) |
| education | String(2) | 否 | 学历：01-高中及以下 02-大专 03-本科 04-硕士及以上 |

---

## 响应参数

### 业务响应体

| 字段名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| respCode | String(4) | 是 | 响应码：0000-成功 其他-失败 |
| respMsg | String(128) | 是 | 响应信息 |
| loanStatus | String(2) | 是 | 贷款状态：01-放款成功 02-审核中 03-放款失败 |
| bankLoanNo | String(32) | 否 | 银行端贷款编号 |
| failReason | String(256) | 否 | 失败原因 |

### 响应码说明

| 码值 | 说明 |
|------|------|
| 0000 | 成功 |
| 1001 | 参数校验失败 |
| 1002 | 签名验证失败 |
| 2001 | 客户资质不通过 |
| 2002 | 额度不足 |
| 3001 | 系统异常 |
| 3002 | 服务超时 |

---

## 示例

### 请求示例
```json
{
  "appId": "FUNDLINK001",
  "reqTime": "20260715143000",
  "sign": "MEUCIQDxxx...",
  "serialNo": "SN20260715143000001",
  "loanNo": "LOAN20260715001",
  "custName": "张三",
  "certType": "01",
  "certNo": "110101199001011234",
  "mobile": "13800138000",
  "applyAmount": "100000.00",
  "loanTerm": "12",
  "loanRate": "5.6000",
  "rateType": "1",
  "repayMethod": "01",
  "loanPurpose": "1001",
  "bankCardNo": "6222021234567890123",
  "bankCode": "308584000013",
  "bankName": "招商银行深圳分行",
  "annualIncome": "200000.00",
  "education": "03"
}
```

### 成功响应
```json
{
  "respCode": "0000",
  "respMsg": "放款申请已受理",
  "loanStatus": "02",
  "bankLoanNo": "CMB20260715001"
}
```

### 失败响应
```json
{
  "respCode": "2001",
  "respMsg": "客户资质审核不通过",
  "loanStatus": "03",
  "failReason": "信用评分不足，当前评分580，要求>=600"
}
```
