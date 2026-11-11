-- =====================================================================
-- V15 오래된 대화 요약 (S15P31C106-333, 2026-11-11)
-- 질문 해석에는 최근 대화 원문(기본 4턴·2000자, 147·334)만 넘긴다. 그보다 오래된 대화는 요약해 대화방에 저장하고, 다음 질문부터 원문과
-- 함께 넘긴다. 답변이 끝난 뒤 다음 질문의 원문 범위 밖으로 밀려난 턴을 이전 요약에 합쳐 새 요약을 만든다.
-- 기존 대화는 두 칸이 NULL이라 다음 답변 뒤에 처음 요약된다.
-- =====================================================================

ALTER TABLE agent_conversation
    ADD COLUMN summary                     text,
    ADD COLUMN summarized_until_message_id bigint,
    ADD CONSTRAINT ck_agent_conversation_summary
        CHECK ((summary IS NULL) = (summarized_until_message_id IS NULL));

COMMENT ON COLUMN agent_conversation.summary IS '최근 대화 원문 범위 밖의 오래된 대화 요약(333). 물어본 대상·기간·주제만 남기고 조회 결과 숫자는 넣지 않는다. 질문 해석 AI에만 넘기며, 이전 답변에서 확정한 조건과 다르면 조건을 따른다. NULL이면 아직 요약할 대화가 없다';

COMMENT ON COLUMN agent_conversation.summarized_until_message_id IS '요약에 넣은 마지막 메시지 ID(agent_message.id, 333). 다음 요약은 이 뒤의 메시지만 이전 요약에 합친다. 같은 대화의 요약이 동시에 만들어지면 이 값이 읽은 때와 같을 때만 저장해 나중 것이 앞선 것을 덮지 않게 한다. 메시지는 대화와 함께만 지워지므로 외래 키는 두지 않는다';
