import os

import pytest

from app.config import Settings, get_settings, load_env_file
from app.llm.client import (
    ChatLLMClient,
    LLMError,
    MockLLMClient,
    ResponsesLLMClient,
    create_llm_client,
)


def test_create_llm_client_by_provider():
    assert isinstance(create_llm_client(Settings(llm_provider="mock")), MockLLMClient)
    assert isinstance(
        create_llm_client(Settings(llm_provider="gms", llm_api_key="test-key")),
        ResponsesLLMClient,
    )
    with pytest.raises(ValueError):
        create_llm_client(Settings(llm_provider="unknown"))


def test_default_provider_is_real_gms():
    assert Settings().llm_provider == "gms"


def test_gms_without_key_fails_before_any_call():
    with pytest.raises(LLMError):
        create_llm_client(Settings(llm_provider="gms", llm_api_key=None))


def _fake_sender(content: str, calls: list):
    def send(url, headers, body, timeout):
        calls.append({"url": url, "headers": headers, "body": body})
        return {
            "status": "completed",
            "usage": {"input_tokens": 12, "output_tokens": 5},
            "output": [
                {"type": "reasoning", "content": []},
                {
                    "type": "message",
                    "content": [{"type": "output_text", "text": content}],
                },
            ],
        }

    return send


def test_gms_client_builds_request_and_parses_json_without_network():
    calls = []
    client = ResponsesLLMClient(
        "test-key",
        "https://gms.example/v1/",
        "gpt-6-luna",
        send=_fake_sender('{"ok": true}', calls),
    )

    assert client.complete_json("prompt") == {"ok": True}
    assert calls[0]["url"] == "https://gms.example/v1/responses"
    assert calls[0]["headers"]["Authorization"] == "Bearer test-key"
    assert calls[0]["body"]["model"] == "gpt-6-luna"
    assert calls[0]["body"]["input"] == "prompt"
    assert calls[0]["body"]["text"] == {"format": {"type": "json_object"}}
    assert client.last_usage == {
        "input_tokens": 12,
        "output_tokens": 5,
        "reasoning_tokens": None,
    }


def test_gms_client_rejects_non_json_answer():
    client = ResponsesLLMClient(
        "test-key",
        "https://gms.example/v1",
        "gpt-6-luna",
        send=_fake_sender("JSON 아님", []),
    )

    with pytest.raises(LLMError):
        client.complete_json("prompt")


def test_gms_client_reports_error_response():
    client = ResponsesLLMClient(
        "test-key",
        "https://gms.example/v1",
        "gpt-6-luna",
        send=lambda *args: {"error": {"message": "invalid model"}},
    )

    with pytest.raises(LLMError):
        client.complete_json("prompt")


def test_settings_repr_hides_key():
    assert "secret" not in repr(Settings(llm_api_key="secret"))


def test_env_file_fills_only_missing_values(tmp_path, monkeypatch):
    env = tmp_path / ".env"
    env.write_text(
        "# 주석\nAI_TEST_A=from_file\nAI_TEST_B='quoted'\n", encoding="utf-8"
    )
    monkeypatch.delenv("AI_ONTOLOGY_SKIP_ENV_FILE", raising=False)
    monkeypatch.setenv("AI_TEST_A", "from_server")
    monkeypatch.delenv("AI_TEST_B", raising=False)

    load_env_file(env)

    assert os.environ["AI_TEST_A"] == "from_server"
    assert os.environ["AI_TEST_B"] == "quoted"
    monkeypatch.delenv("AI_TEST_B")


def test_company_llm_needs_only_env_values():
    # 회사 LLM으로 바꿀 때 코드 수정 없이 provider·키·주소·모델만 바꾼다
    openai = create_llm_client(
        Settings(llm_provider="openai", llm_api_key="k", model="company-model")
    )
    assert isinstance(openai, ResponsesLLMClient)
    assert openai.base_url == "https://api.openai.com/v1"
    assert openai.model == "company-model"

    chat = create_llm_client(
        Settings(
            llm_provider="openai-chat", base_url="http://llm.internal/v1", model="m"
        )
    )
    assert isinstance(chat, ChatLLMClient)


def test_missing_settings_are_named_without_values():
    assert Settings(llm_provider="gms").llm_missing == "AI_ONTOLOGY_LLM_API_KEY"
    assert (
        Settings(llm_provider="openai", llm_api_key="k").llm_missing
        == "AI_ONTOLOGY_LLM_MODEL"
    )
    assert Settings(llm_provider="openai-chat", model="m").llm_missing == (
        "AI_ONTOLOGY_LLM_BASE_URL"
    )
    assert Settings(llm_provider="mock").llm_ready
    with pytest.raises(LLMError, match="AI_ONTOLOGY_LLM_MODEL"):
        create_llm_client(Settings(llm_provider="openai", llm_api_key="k"))


def test_none_provider_means_code_analysis_only():
    settings = Settings(llm_provider="none", llm_api_key="k")
    assert not settings.llm_ready
    with pytest.raises(LLMError):
        create_llm_client(settings)


def test_old_gms_env_names_still_work_and_new_names_win(monkeypatch):
    monkeypatch.setenv("AI_ONTOLOGY_LLM_PROVIDER", "gms")
    monkeypatch.setenv("AI_ONTOLOGY_GMS_API_KEY", "old-key")
    monkeypatch.setenv("AI_ONTOLOGY_GMS_MODEL", "old-model")
    monkeypatch.setenv("AI_ONTOLOGY_LLM_MODEL", "new-model")
    get_settings.cache_clear()
    try:
        settings = get_settings()
        assert settings.llm_api_key == "old-key"
        assert settings.llm_model == "new-model"
    finally:
        get_settings.cache_clear()


def _chat_sender(content: str, calls: list):
    def send(url, headers, body, timeout):
        calls.append({"url": url, "headers": headers, "body": body})
        return {
            "choices": [{"message": {"role": "assistant", "content": content}}],
            "usage": {"prompt_tokens": 7, "completion_tokens": 3},
        }

    return send


def test_chat_client_builds_request_and_reads_answer():
    calls = []
    client = ChatLLMClient(
        None,
        "http://llm.internal/v1/",
        "local-model",
        send=_chat_sender('{"ok": 1}', calls),
    )

    assert client.complete_json("prompt") == {"ok": 1}
    assert calls[0]["url"] == "http://llm.internal/v1/chat/completions"
    assert "Authorization" not in calls[0]["headers"]  # 키 없는 내부 서버
    assert calls[0]["body"]["messages"][-1] == {"role": "user", "content": "prompt"}
    assert calls[0]["body"]["response_format"] == {"type": "json_object"}
    assert client.last_usage["input_tokens"] == 7
    assert client.last_usage["output_tokens"] == 3


def test_chat_client_accepts_json_in_code_fence_and_rejects_empty_answer():
    fenced = ChatLLMClient(
        "k", "http://x/v1", "m", send=_chat_sender('```json\n{"ok": true}\n```', [])
    )
    assert fenced.complete_json("prompt") == {"ok": True}

    empty = ChatLLMClient("k", "http://x/v1", "m", send=lambda *a: {"choices": []})
    with pytest.raises(LLMError):
        empty.complete_json("prompt")


def test_credits_follow_model_and_unknown_model_counts_as_most_expensive():
    from app.llm.client import token_credits

    assert token_credits("gpt-6-luna", 1000, 200) == pytest.approx(2.0)
    # 6.1 sol은 GMS 등급(20)으로 잡은 추정 단가
    assert token_credits("gpt-6.1-sol", 1000, 200) == pytest.approx(40.0)
    assert token_credits("gpt-6-astra", 1000, 200) == pytest.approx(200.0)
    # 표에 없는 모델은 적게 세지 않도록 가장 비싼 단가로 센다
    assert token_credits("gpt-new", 1000, 200) == pytest.approx(200.0)


def test_timeout_and_broken_body_become_llm_error(monkeypatch):
    # 응답을 기다리다 시간이 지나면 분석 전체가 아니라 그 호출만 실패해야 한다
    import io

    import pytest

    from app.llm import client
    from app.llm.client import LLMError

    def slow(*_args, **_kwargs):
        raise TimeoutError("timed out")

    monkeypatch.setattr(client.urllib.request, "urlopen", slow)
    with pytest.raises(LLMError, match="응답이 없습니다"):
        client._post_json("http://llm", {}, {}, 1)

    class Body(io.BytesIO):
        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

    monkeypatch.setattr(
        client.urllib.request, "urlopen", lambda *a, **k: Body(b"<html>")
    )
    with pytest.raises(LLMError, match="JSON이 아닌"):
        client._post_json("http://llm", {}, {}, 1)


def test_none_provider_is_reported_as_turned_off():
    from app.config import Settings

    settings = Settings(llm_provider="none")
    assert not settings.llm_ready
    assert "LLM 사용 안 함" in settings.llm_missing
