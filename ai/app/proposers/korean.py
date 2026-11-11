"""검토 화면 문장의 한국어 조사. 받침 유무에 맞춰 '이/가'·'을/를'·'으로/로'를 고른다."""


def final_consonant(word: str) -> int | None:
    """마지막 글자의 받침 번호(0은 받침 없음). 한글이 아니면 숫자·영문 읽기로 어림한다."""
    if not word:
        return 0
    ch = word[-1]
    if "가" <= ch <= "힣":
        return (ord(ch) - ord("가")) % 28
    if ch.isdigit():
        # 일·칠·팔은 ㄹ 받침(8), 영·삼·육은 다른 받침(1)
        return 8 if ch in "178" else 1 if ch in "036" else 0
    if not ch.isascii() or not ch.isalpha():
        return 0
    tail = word.split("_")[-1].lower()
    if len(tail) == 1 or not tail.isalpha():
        # 글자 하나는 알파벳 이름으로 읽는다(엘·알 → ㄹ, 엠·엔 → 받침)
        return 8 if ch.lower() in "lr" else 1 if ch.lower() in "mn" else 0
    # 영어 낱말은 소리로 어림한다: name·time(ㅁ), line(ㄴ), level(ㄹ), group(ㅂ), string(ㅇ)
    if tail.endswith("ng"):
        return 1
    if tail.endswith("e") and len(tail) > 2:
        prev = tail[-2]
        return 8 if prev == "l" else 1 if prev in "mn" else 0
    last = tail[-1]
    if last == "l":
        return 8
    return 1 if last in "mnpb" else 0  # number·order(-r)는 받침 없이 읽는다


def josa(word: str, pair: str) -> str:
    """word 뒤에 맞는 조사를 붙인다. pair: '이/가', '을/를', '은/는', '으로/로'."""
    with_batchim, without = pair.split("/")
    final = final_consonant(word)
    if pair == "으로/로":
        return word + (without if final in (0, 8) else with_batchim)  # ㄹ 받침은 '로'
    return word + (with_batchim if final else without)


def particle(word: str, pair: str) -> str:
    """word 뒤에 붙을 조사만. 따옴표로 감싼 말 뒤에 쓴다."""
    return josa(word, pair)[len(word) :]
