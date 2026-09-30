"""페르소나를 프롬프트 문장으로 바꾸는 네 가지 형태.

(a) label       라벨만: 기본 정보 + 생활 변수 + 성향 상위 2개를 형용사로 (1차 실험 수준)
(b) behavior    (a) + 행동 문장 (④, 점수 구간별 템플릿 4개)
(c) experience  (b) + 경험 서술 4개 (⑤, LLM 이 쓴 것)
(d) related     (b) + 질문 주제와 태그가 같은 경험만 (없으면 (b) 와 같다)
"""
from schema import behaviors, trait_labels

FORMATS = ['label', 'behavior', 'experience', 'related']
FORMAT_KO = {'label': '(a) 라벨만', 'behavior': '(b) 라벨+행동', 'experience': '(c) 라벨+행동+경험', 'related': '(d) 라벨+행동+관련 경험'}


def label_line(p: dict) -> str:
    relationship = p['relationship'] if p['marital'] == '미혼' else p['marital']
    if p['marital'] == '이혼·사별' and p['relationship'] == '연애 중':
        relationship = '이혼·사별 후 연애 중'
    return (f"#{p['id']}: {p['age']}세 {p['gender']}, {p['region']}, {p['job']}({p['career']}), {p['education']}, "
            f"{relationship}, {p['family']}, 경제적으로 {p['economy']}. {', '.join(trait_labels(p))}")


def behavior_lines(p: dict) -> list[str]:
    return behaviors(p)


def render(p: dict, fmt: str, experiences: list[dict] | None, topic: str | None = None) -> str:
    """experiences: [{'topic': '연애', 'text': '...'}, ...]. topic: (d) 에서 고를 주제"""
    text = label_line(p)
    if fmt == 'label':
        return text
    text += '\n  평소 행동: ' + ' / '.join(behavior_lines(p))
    if fmt == 'behavior':
        return text
    chosen = experiences or []
    if fmt == 'related':
        chosen = [e for e in chosen if e['topic'] == topic]
    if chosen:
        text += '\n  살아온 이야기: ' + ' / '.join(e['text'] for e in chosen)
    return text


def full_text(p: dict, experiences: list[dict] | None) -> str:
    """LLM 심판·검수용: (c) 전체"""
    return render(p, 'experience', experiences)
