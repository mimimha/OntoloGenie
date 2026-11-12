package com.axcore.platform.ontology.service;

/** 검색어를 ILIKE 패턴으로 바꾼다. 사용자가 입력한 %·_는 글자 그대로 찾는다. */
public final class SearchPattern {

    private SearchPattern() {}

    /** 검색어가 비어 있으면 모두 맞는 패턴을 돌려준다. */
    public static String contains(String q) {
        if (q == null || q.isBlank()) {
            return "%";
        }
        return "%" + exact(q) + "%";
    }

    public static String exact(String q) {
        return q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
