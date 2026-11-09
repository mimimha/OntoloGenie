"""이미 확정된 모델(API 043 모델 요약)과 같은 후보 거르기 (S15P31C106-281)."""

import io
import json
import urllib.error
import urllib.request

import pytest

from app.analysis import backend, engine
from app.analysis.backend import BackendError, fetch_model_summary
from app.analysis.engine import analyze
from app.config import Settings
from app.dataset.build import generate
from app.models.analysis import AnalysisStartRequest
from app.proposers.existing import filter_existing


def request(targets, code="MES_MAIN"):
    return AnalysisStartRequest.model_validate(
        {
            "runId": 1,
            "runKind": "INITIAL",
            "targets": [
                {"sourceResourceId": i, "dataSourceCode": code, "resourceKey": key}
                for i, key in enumerate(targets, 1)
            ],
            "sources": [{"dataSourceCode": code, "sourceKind": "CSV"}],
        }
    )


SUMMARY = {
    "objectTypes": [{"id": 1, "code": "WORK_ORDER"}, {"id": 2, "code": "SALES_ORDER"}],
    "properties": [
        {"id": 11, "objectTypeId": 1, "code": "wo_no"},
        {"id": 12, "objectTypeId": 1, "code": "so_ref"},
        {"id": 21, "objectTypeId": 2, "code": "so_no"},
    ],
    "linkTypes": [{"id": 5, "code": "WORK_ORDER_SO_REF_TO_SALES_ORDER"}],
    "activeRules": [
        {
            "id": 9,
            "code": "WORK_ORDER_SO_REF_TO_SALES_ORDER",
            "conditions": [{"sourcePropertyId": 12, "targetPropertyId": 21}],
        }
    ],
}


def object_type(code, *props):
    return {
        "itemKind": "OBJECT_TYPE",
        "payload": {"code": code, "properties": [{"code": p} for p in props]},
        "evidence": {
            "reason": "원천 표의 한 행을 객체 하나로 본다.",
            "holdRecommended": False,
        },
    }


def link_rule(code, source, target, link_code=None):
    return {
        "itemKind": "LINK_RULE",
        "payload": {
            "code": code,
            "linkType": {"code": link_code or code},
            "conditions": [{"sourceProperty": source, "targetProperty": target}],
        },
        "evidence": {"reason": "값이 이어진다.", "holdRecommended": False},
    }


def test_same_object_type_and_rule_are_not_sent_again():
    proposals = [
        object_type("WORK_ORDER", "wo_no", "so_ref"),
        link_rule(
            "WORK_ORDER_SO_REF_TO_SALES_ORDER", "WORK_ORDER.so_ref", "SALES_ORDER.so_no"
        ),
        {
            "itemKind": "LINK_TYPE",
            "payload": {"code": "WORK_ORDER_SO_REF_TO_SALES_ORDER"},
            "evidence": {},
        },
        {
            "itemKind": "DOCUMENT_LINK",
            "payload": {"documentId": "HN-1"},
            "evidence": {},
        },
    ]
    kept, skipped = filter_existing(proposals, SUMMARY)
    assert [p["itemKind"] for p in kept] == ["DOCUMENT_LINK"]
    assert skipped == {"objectTypes": 1, "linkRules": 1, "linkTypes": 1}


def test_new_property_or_new_rule_is_kept_with_a_note():
    proposals = [
        # CDC로 납기일 컬럼이 생긴 작업지시: 새 속성이 있으니 보낸다
        object_type("WORK_ORDER", "wo_no", "so_ref", "due_date"),
        # 같은 관계 유형에 다른 속성끼리 비교하는 규칙
        link_rule(
            "WORK_ORDER_SO_REF_TO_SALES_ORDER", "WORK_ORDER.wo_no", "SALES_ORDER.so_no"
        ),
        # 이미 있는 관계 유형에 다른 코드의 규칙
        link_rule(
            "WORK_ORDER_X",
            "WORK_ORDER.wo_no",
            "SALES_ORDER.so_no",
            link_code="WORK_ORDER_SO_REF_TO_SALES_ORDER",
        ),
        object_type("ITEM", "item_code"),
    ]
    kept, skipped = filter_existing(proposals, SUMMARY)
    assert len(kept) == 4 and skipped == {
        "objectTypes": 0,
        "linkRules": 0,
        "linkTypes": 0,
    }
    assert "새로 제안하는 속성: due_date" in kept[0]["evidence"]["reason"]
    assert "비교하는 속성이 다릅니다" in kept[1]["evidence"]["reason"]
    assert "이미 등록된 관계 유형" in kept[2]["evidence"]["reason"]
    assert kept[3]["evidence"]["reason"] == "원천 표의 한 행을 객체 하나로 본다."
    # 원래 후보는 바꾸지 않는다
    assert proposals[0]["evidence"]["reason"] == "원천 표의 한 행을 객체 하나로 본다."


@pytest.mark.parametrize("summary", [None, {}])
def test_without_model_summary_everything_is_sent(summary):
    proposals = [object_type("WORK_ORDER", "wo_no")]
    assert filter_existing(proposals, summary) == (
        proposals,
        {"objectTypes": 0, "linkRules": 0, "linkTypes": 0},
    )


class _Response(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


def test_fetch_model_summary_reads_data_with_internal_token(monkeypatch):
    seen = {}

    def fake(req, timeout):
        seen["url"], seen["token"] = req.full_url, req.get_header("X-internal-token")
        return _Response(json.dumps({"data": SUMMARY}).encode("utf-8"))

    monkeypatch.setattr(urllib.request, "urlopen", fake)
    data = fetch_model_summary(
        Settings(backend_url="http://backend:8080/", internal_token="t")
    )
    assert data == SUMMARY
    assert seen == {
        "url": "http://backend:8080/api/v1/internal/ontology/model-summary",
        "token": "t",
    }


@pytest.mark.parametrize(
    "error",
    [
        urllib.error.HTTPError("u", 403, "Forbidden", None, None),
        urllib.error.URLError(ConnectionRefusedError()),
        "not json",
    ],
    ids=["http-403", "refused", "not-json"],
)
def test_fetch_model_summary_failures_raise_backend_error(monkeypatch, error):
    def fake(req, timeout):
        if isinstance(error, str):
            return _Response(error.encode("utf-8"))
        raise error

    monkeypatch.setattr(urllib.request, "urlopen", fake)
    with pytest.raises(BackendError):
        fetch_model_summary(Settings(internal_token="t"))


def test_no_token_means_no_call(monkeypatch):
    monkeypatch.setattr(engine, "get_settings", lambda: Settings(internal_token=None))
    monkeypatch.setattr(
        backend.urllib.request, "urlopen", lambda *a, **k: pytest.fail("호출하면 안 됨")
    )
    assert engine.get_model_summary() == (None, "내부 토큰 없음")


def test_backend_error_keeps_the_analysis_going(monkeypatch):
    monkeypatch.setattr(engine, "get_settings", lambda: Settings(internal_token="t"))

    def broken(settings):
        raise BackendError("HTTP 500")

    monkeypatch.setattr(engine, "fetch_model_summary", broken)
    assert engine.get_model_summary() == (None, "모델 요약을 받지 못함(HTTP 500)")


def test_second_analysis_skips_what_was_already_approved(tmp_path, monkeypatch):
    monkeypatch.setattr(engine, "get_llm", lambda: None)
    monkeypatch.setattr(engine, "get_model_summary", lambda: (None, "내부 토큰 없음"))
    generate("t5", root=tmp_path)
    mes = tmp_path / "data-t5" / "mes"
    req = request(["mes.work_order", "mes.production_result"])
    first = analyze(req, {"MES_MAIN": mes})
    assert first.summary["modelSummarySkipReason"] == "내부 토큰 없음"

    # 첫 분석의 작업지시 객체 유형이 승인됐다고 보고 모델 요약을 만든다
    work_order = next(
        p
        for p in first.proposals
        if p["itemKind"] == "OBJECT_TYPE" and p["payload"]["code"] == "WORK_ORDER"
    )
    summary = {
        "objectTypes": [{"id": 1, "code": "WORK_ORDER"}],
        "properties": [
            {"id": i, "objectTypeId": 1, "code": q["code"]}
            for i, q in enumerate(work_order["payload"]["properties"], 10)
        ],
        "linkTypes": [],
        "activeRules": [],
    }
    second = analyze(req, {"MES_MAIN": mes}, model_summary=summary)
    assert second.summary["existingSkipped"]["objectTypes"] == 1
    assert second.summary["proposals"] == first.summary["proposals"] - 1
    assert second.summary["modelSummarySkipReason"] is None
    assert not [
        p
        for p in second.proposals
        if p["itemKind"] == "OBJECT_TYPE" and p["payload"]["code"] == "WORK_ORDER"
    ]
