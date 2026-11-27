import json

from app.agent.agents.main_agent import EVIDENCE_CHAR_LIMIT, summary_evidence
from app.agent.registry import compact_outcome


def record(tool, rows=0, ok=True, **extra):
    result = {"ok": ok, "rows": [
        {"order_no": f"RK2026112500{index:02d}", "product_name": "商品" * 20, "quantity": index}
        for index in range(rows)
    ], **extra}
    return {"agent": "order_agent", "tool": tool, "args": {"limit": 50}, "result": result, "summary": "摘要"}


def test_empty_evidence_is_explicit():
    payload = json.loads(summary_evidence([]))
    assert payload["records"] == []
    assert "没有工具记录" in payload["note"]


def test_rows_are_compacted_but_count_kept():
    payload = json.loads(summary_evidence([record("query_orders", rows=50)]))
    entry = payload["records"][0]
    assert entry["row_count"] == 50
    assert len(entry["rows"]) <= 5
    assert "note" in payload


def test_draft_and_failure_survive_budget_pressure():
    records = [record("query_orders", rows=50, key=f"r{index}") for index in range(8)]
    records.append({"agent": "order_agent", "tool": "create_inbound_draft", "args": {},
                    "result": {"ok": True, "order_no": "RK202611250009", "order_id": 9, "status": 0},
                    "summary": "已保存草稿"})
    records.append({"agent": "order_agent", "tool": "create_outbound_draft", "args": {},
                    "result": {"ok": False, "message": "可用库存不足", "shortages": [
                        {"product_id": 1, "shortage": index} for index in range(9)
                    ]}, "summary": "可用库存不足"})

    payload = json.loads(summary_evidence(records))
    tools = [entry["tool"] for entry in payload["records"]]
    assert "create_inbound_draft" in tools
    assert "create_outbound_draft" in tools
    assert payload["omitted_record_count"] >= 0
    assert len(json.dumps(payload, ensure_ascii=False)) <= EVIDENCE_CHAR_LIMIT + 1200

    draft = next(entry for entry in payload["records"] if entry["tool"] == "create_inbound_draft")
    assert draft["order_no"] == "RK202611250009"
    shortage = next(entry for entry in payload["records"] if entry["tool"] == "create_outbound_draft")
    assert shortage["shortage_count"] == 9
    assert len(shortage["shortages"]) == 5


def test_oversized_records_are_omitted_not_silently_dropped():
    huge = [record("query_realtime_inventory", rows=5, ok=False, message="x" * 40000) for _ in range(6)]
    payload = json.loads(summary_evidence(huge))
    assert payload["omitted_record_count"] > 0
    assert payload["omitted_tools"] == ["query_realtime_inventory"]


def test_unknown_result_fields_are_dropped():
    payload = json.loads(summary_evidence([record("query_orders", rows=3, padding="x" * 40000)]))
    entry = payload["records"][0]
    assert "padding" not in entry
    assert entry["row_count"] == 3


def test_compact_outcome_limits_rows():
    outcome = {"ok": True, "rows": [{"id": index} for index in range(50)]}
    compact = compact_outcome(outcome)
    assert len(compact["rows"]) == 8
    assert compact["row_count"] == 50
    assert len(outcome["rows"]) == 50
