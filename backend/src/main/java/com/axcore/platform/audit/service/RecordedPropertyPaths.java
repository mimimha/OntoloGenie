package com.axcore.platform.audit.service;

import java.util.List;

/**
 * 감사 기록(실행 입력·변경 내용)에 객체 속성 값을 남기는 쪽이 그 값의 자리를 알린다. 실행 이력 조회(API 080·096)는 이 자리의 값 중 사용자가 볼 수 없는
 * 민감(RESTRICTED) 속성 값을 가린다(S15P31C106-205). 기록하는 값의 모양을 바꾸면 이 자리도 함께 바꾼다.
 */
public interface RecordedPropertyPaths {

    List<Path> recordedPropertyPaths();

    /** 값이 든 곳. INPUT은 Handler 키, CHANGE는 변경된 표 이름으로 기록을 고른다. */
    enum Section {
        INPUT,
        CHANGE
    }

    /**
     * 기록 하나 안에서 속성 값 하나의 자리. keys는 JSON 안의 경로이고 {@code "*"}는 목록의 모든 원소다. 예: {@code
     * ["source_snapshot", "operations", "*", "status"]}.
     */
    record Path(
            Section section,
            String owner,
            List<String> keys,
            String objectTypeCode,
            String propertyCode) {

        public Path {
            keys = List.copyOf(keys);
            if (keys.isEmpty() || "*".equals(keys.getLast())) {
                throw new IllegalArgumentException("경로는 속성 값이 든 키로 끝나야 합니다: " + keys);
            }
        }

        public static Path input(
                String handlerKey, List<String> keys, String objectTypeCode, String propertyCode) {
            return new Path(Section.INPUT, handlerKey, keys, objectTypeCode, propertyCode);
        }

        public static Path change(
                String tableName, List<String> keys, String objectTypeCode, String propertyCode) {
            return new Path(Section.CHANGE, tableName, keys, objectTypeCode, propertyCode);
        }
    }
}
