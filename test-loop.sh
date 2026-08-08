#!/bin/bash
# FundLink Agent Loop 端到端测试
# 用法: bash test-loop.sh
# 前置: FundLink(8080) + AI(8081) + RAG(8000) 都启动

BASE="http://localhost:8081"

echo "============================================"
echo " FundLink Agent Loop — E2E Test"
echo "============================================"
echo ""

# -------- Manual Mode --------
echo ">>> [Manual] 1/2 — Analyze"
ANALYZE=$(curl -s -X POST "$BASE/api/ai/analyze" \
  -H "Content-Type: application/json" \
  -d '{
    "documentText": "# 招商银行 — 信贷放款接口文档\n\n## 接口概述\n| 项目 | 说明 |\n|------|------|\n| 接口名称 | 信贷放款申请 |\n| 请求地址 | POST /api/credit/loan/apply |\n| 请求方式 | POST |\n| 报文格式 | JSON |\n\n## 请求参数\n| 字段名 | 类型 | 必填 | 说明 |\n|--------|------|------|------|\n| loanNo | String(32) | 是 | 贷款编号 |\n| custName | String(64) | 是 | 客户姓名 |\n| certType | String(2) | 是 | 证件类型 |\n| certNo | String(32) | 是 | 证件号码 |\n| mobile | String(11) | 是 | 手机号 |\n| applyAmount | String(16) | 是 | 申请金额(元) |\n| loanTerm | String(4) | 是 | 贷款期限(月) |\n| loanRate | String(8) | 是 | 年化利率 |\n| bankCardNo | String(19) | 是 | 收款银行卡号 |\n| bankCode | String(8) | 是 | 收款银行联行号 |\n| bankName | String(64) | 是 | 收款银行名称 |\n\n## 业务流程\n风控校验 → 获取客户信息 → 渲染报文 → 条件判断(风控=A?)\n  ├─是→ 发送银行 → 放款成功\n  └─否→ 转人工审核\n",
    "providerCode": "CMB"
  }')

CODE=$(echo "$ANALYZE" | grep -o '"code":[-0-9]*' | head -1 | cut -d: -f2)
if [ "$CODE" = "0" ]; then
    MAPPINGS=$(echo "$ANALYZE" | grep -o '"fieldMappings":\[[^]]*' | head -1)
    echo "✅ Analyze OK — $MAPPINGS"
else
    echo "❌ Analyze FAILED:"
    echo "$ANALYZE" | head -5
fi

echo ""

# -------- Loop Mode --------
echo ">>> [Loop] 1/3 — Create Task"
LOOP=$(curl -s -X POST "$BASE/api/ai/loop" \
  -H "Content-Type: application/json" \
  -d '{
    "documentText": "# 招商银行 — 信贷放款接口文档\n\n## 请求参数\n| 字段名 | 类型 | 必填 | 说明 |\n|--------|------|------|------|\n| loanNo | String(32) | 是 | 贷款编号 |\n| custName | String(64) | 是 | 客户姓名 |\n| certNo | String(32) | 是 | 证件号码 |\n| mobile | String(11) | 是 | 手机号 |\n| applyAmount | String(16) | 是 | 申请金额(元) |\n\n## 业务流程\n风控校验 → 渲染报文 → 条件判断(风控=A?) → 发送银行\n",
    "providerCode": "CMB2",
    "flowType": "LOAN"
  }')

TASK_ID=$(echo "$LOOP" | grep -o '"taskId":[0-9]*' | head -1 | cut -d: -f2)
TASK_NO=$(echo "$LOOP" | grep -o '"taskNo":"[^"]*"' | head -1 | cut -d'"' -f4)

if [ -n "$TASK_ID" ]; then
    echo "✅ Task created — id=$TASK_ID  no=$TASK_NO"
else
    echo "❌ Create task FAILED:"
    echo "$LOOP"
    exit 1
fi

echo ""
echo ">>> [Loop] 2/3 — SSE Stream (Ctrl+C to stop)"
echo "    (waiting for events...)"

# Listen for SSE events for up to 60 seconds, print key ones
timeout 60 curl -s -N "$BASE/api/ai/loop/$TASK_ID/stream" 2>/dev/null | while IFS= read -r line; do
    case "$line" in
        event:*)  EVENT=$(echo "$line" | cut -d: -f2- | tr -d ' ') ;;
        data:*)   DATA=$(echo "$line" | cut -d: -f2-)
                  case "$EVENT" in
                      phase:start)    echo "  ▶ $DATA" ;;
                      phase:complete) echo "  ✅ $DATA" ;;
                      phase:error)    echo "  ❌ $DATA" ;;
                      decision_required)
                          echo "  ⏸️  DECISION REQUIRED: $DATA"
                          # Auto-retry once
                          echo "  → Auto-sending RETRY..."
                          curl -s -X POST "$BASE/api/ai/loop/$TASK_ID/decide" \
                              -H "Content-Type: application/json" \
                              -d "{\"taskId\":$TASK_ID,\"decision\":\"RETRY\"}" > /dev/null
                          ;;
                      task:complete)
                          echo "  🎉 TASK COMPLETE: $DATA"
                          ;;
                      task:failed)
                          echo "  💀 TASK FAILED: $DATA"
                          ;;
                  esac ;;
    esac
done

echo ""
echo ">>> [Loop] 3/3 — Final Status"
STATUS=$(curl -s "$BASE/api/ai/loop/$TASK_ID")
echo "$STATUS"
echo ""
echo "============================================"
echo " Done."
echo "============================================"
