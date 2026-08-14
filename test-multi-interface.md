# 星展银行资金接口文档 v2.3

> 资金方编码: DBS
> 基础URL: https://api.dbs.com/fund/v2
> 文档日期: 2026-06

---

## 接口 1：放款申请

### 基本信息
- 接口名称: 放款申请
- 请求地址: POST /api/loan/apply
- 接口说明: 向银行提交放款申请，银行处理后返回放款结果

### 请求参数

| 字段名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| loanNo | String | 是 | 贷款编号，最长32位 |
| amount | BigDecimal | 是 | 贷款金额，单位元 |
| customerId | String | 是 | 客户身份证号 |
| customerName | String | 是 | 客户姓名 |
| idType | String | 是 | 证件类型: ID_CARD/PASSPORT |
| mobile | String | 是 | 手机号 |
| applyDate | String | 是 | 申请日期 yyyy-MM-dd |

### 响应参数

| 字段名 | 类型 | 说明 |
|--------|------|------|
| code | String | 响应码: 0000=成功 |
| message | String | 响应信息 |
| loanStatus | String | 放款状态: SUCCESS/FAIL/PROCESSING |
| bankOrderNo | String | 银行订单号 |

---

## 接口 2：放款查询

### 基本信息
- 接口名称: 放款查询
- 请求地址: POST /api/loan/query
- 接口说明: 根据贷款编号查询放款状态

### 请求参数

| 字段名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| loanNo | String | 是 | 贷款编号 |

### 响应参数

| 字段名 | 类型 | 说明 |
|--------|------|------|
| code | String | 响应码 |
| message | String | 响应信息 |
| loanNo | String | 贷款编号 |
| amount | BigDecimal | 贷款金额 |
| loanStatus | String | SUCCESS/FAIL/PROCESSING |
| finishTime | String | 完成时间 |

---

## 接口 3：还款申请

### 基本信息
- 接口名称: 还款申请
- 请求地址: POST /api/repay/apply
- 接口说明: 发起主动还款，支持全额和部分还款

### 请求参数

| 字段名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| loanNo | String | 是 | 贷款编号 |
| repayAmount | BigDecimal | 是 | 还款金额 |
| repayType | String | 是 | 还款类型: FULL/PART |
| repayAccount | Object | 是 | 还款账户信息 |
| repayAccount.bankCode | String | 是 | 银行编码 |
| repayAccount.bankName | String | 是 | 银行名称 |
| repayAccount.accountNo | String | 是 | 银行卡号 |

### 响应参数

| 字段名 | 类型 | 说明 |
|--------|------|------|
| code | String | 响应码 |
| message | String | 响应信息 |
| repayNo | String | 还款流水号 |
| repayStatus | String | 还款状态 |

---

## 接口 4：还款查询

### 基本信息
- 接口名称: 还款查询
- 请求地址: POST /api/repay/query
- 接口说明: 查询还款结果

### 请求参数

| 字段名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| loanNo | String | 是 | 贷款编号 |
| repayNo | String | 否 | 还款流水号 |

### 响应参数

| 字段名 | 类型 | 说明 |
|--------|------|------|
| code | String | 响应码 |
| message | String | 响应信息 |
| repayStatus | String | SUCCESS/FAIL/PROCESSING |
| repayAmount | BigDecimal | 实际还款金额 |
