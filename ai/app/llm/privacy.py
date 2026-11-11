"""GMS로 보내기 전에 전화번호·이메일·주민등록번호·'성 + 직함' 이름(예: 김 대리)을 가린다.

사람을 적는 칸의 이름 모양 값은 mask_value가 통째로 가린다. 글 속에서 직함 없이 쓴 이름은 찾지 못한다.
"""

import re
from dataclasses import dataclass

MASK = "***"
SURNAMES = "김이박최정강조윤장임한오서신권황안송류홍전고문양손배백허유남심노하곽성차주우구민진나지엄채원천방공현함변염여추도소석선설마길연위표명기반왕금옥육인맹제모탁국어은편용"
TITLES = "대리|과장|차장|부장|주임|반장|조장|사원|팀장|기사|씨"
# 성씨 글자로 시작하지만 사람 이름이 아닌 현장 단어 (예: 정비 기사, 성형 반장)
NOT_NAMES = {
    "정비",
    "성형",
    "보전",
    "품질",
    "생산",
    "금형",
    "설비",
    "현장",
    "주간",
    "야간",
    "검사",
    "용접",
    "공정",
    "자재",
    "구매",
    "영업",
    "관리",
    "안전",
    "조립",
    "전기",
    "기계",
    "도장",
    "출하",
    "물류",
    "공무",
    "외주",
    "신입",
    "선임",
    "수석",
    "반장",
}

PATTERNS = [
    # \b는 한글도 글자로 봐서 '…5678로'처럼 조사가 붙으면 놓친다. 숫자·영문 기준으로 경계를 본다
    ("RRN", re.compile(r"(?<!\d)\d{6}-[1-4]\d{6}(?!\d)")),
    (
        "PHONE",
        re.compile(
            r"(?<!\d)(?:01[016-9]-?\d{3,4}-?\d{4}|0\d{1,2}-\d{3,4}-\d{4})(?!\d)"
        ),
    ),
    (
        "EMAIL",
        re.compile(
            r"(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+"
        ),
    ),
    (
        "NAME",
        re.compile(rf"(?<![가-힣])([{SURNAMES}][가-힣]{{0,2}})(\s?)(?=(?:{TITLES}))"),
    ),
]


@dataclass
class Masked:
    text: str
    kinds: list[str]  # 가린 종류 (로그용. 원래 값은 남기지 않는다)


def mask_text(text: str) -> Masked:
    kinds = []
    for kind, pattern in PATTERNS:

        def replace(match, kind=kind):
            kinds.append(kind)
            if kind == "NAME":
                if match.group(1) in NOT_NAMES:
                    return match.group(0)
                return MASK + match.group(2)
            return MASK

        text = pattern.sub(replace, text)
    return Masked(text, kinds)


# 부서·조직·장소 이름 끝말. '보전팀'·'품질부'처럼 성씨 글자로 시작해도 사람 이름이 아니다
ORG_ENDINGS = (
    "팀",
    "부",
    "과",
    "실",
    "소",
    "센터",
    "라인",
    "공장",
    "창고",
    "조",
    "반",
    "본부",
    "사업",
    "지점",
)


def looks_like_name(value: str) -> bool:
    """값 전체가 한글 2~4자 사람 이름 모양인지(성씨로 시작, 부서·현장 단어 아님)."""
    v = value.strip()
    return bool(
        re.fullmatch(r"[가-힣]{2,4}", v)
        and v[0] in SURNAMES
        and v not in NOT_NAMES
        and v[:2] not in NOT_NAMES
        and not v.endswith(ORG_ENDINGS)
    )


# 사람을 적는 칸(담당·작성·검사자 등). 이런 칸에 든 이름 모양 값만 가린다. 상태('진행')·지역('서울')·상호는 안 가린다
PERSON_COLUMN = re.compile(
    r"담당|작성|검사자|검수자|기사|처리자|요청자|승인자|작업자명|"
    r"technician|inspector|manager|owner|staff|assignee|handler|writer|_rep|buyer",
    re.IGNORECASE,
)


def is_person_column(name_or_comment: str) -> bool:
    return bool(PERSON_COLUMN.search(name_or_comment or ""))


def mask_value(value: str, column: str = "") -> str:
    """LLM·근거에 보낼 값 예 하나. 사람을 적는 칸의 이름 모양 값은 통째로, 아니면 글 속 개인정보만 가린다."""
    if is_person_column(column) and looks_like_name(value):
        return MASK
    return mask_text(value).text
