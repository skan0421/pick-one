"""⑤ 경험 서술 생성 + ⑥ 검수.

페르소나마다 exaone 이 "살아온 이야기" 4개를 쓴다 (각 1~2문장, 주제 태그 하나씩). 그다음
 - 정규식으로 고정관념 표현("여자라서", "나이 들어서" 등)과 실존 인물·상호 언급을 거르고
 - exaone 에게 변수와 이야기 사이의 모순을 묻는다 (JSON).
걸리면 최대 2번 다시 쓴다. 1명당 걸린 시간을 재서 1,000명 환산에 쓴다.

실행: python experiences.py  → generated/experiences.json
"""
import json
import random
import re
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from formats import label_line, behavior_lines
from ollama import chat_json
from schema import TRAIT_KO

HERE = Path(__file__).parent
TOPICS = ['연애', '소비', '직장', '취미', '가족']
CONCURRENCY = 8
MAX_ATTEMPTS = 3

# 고정관념·부적절 표현. "라서/니까/이니까/이라" 로 성별·나이를 이유로 대는 문장을 잡는다
STEREOTYPE_PATTERNS = [
    r'(여자|남자|여성|남성)(라서|이라서|니까|이니까|답게|이라|라)\b',
    r'(여자|남자|여성|남성)들?은 (원래|다|보통)',
    r'나이(가)? (들어서|많아서|먹어서|드니까)',
    r'(늙어서|젊어서|어려서|나이답게|나잇값)',
    r'(아줌마|아저씨|할머니|할아버지)(라서|니까|답게)',
    r'(학생|주부|엄마|아빠)(이니까|니까|라서|답게)',
]
# 실존 인물·특정 브랜드/상호를 막는 대략적인 필터 (완전하지 않다. 사람이 샘플을 읽는 이유)
NAMED_ENTITY_PATTERNS = [r'(BTS|손흥민|유재석|아이유|이재용|삼성전자|스타벅스|애플|맥도날드|쿠팡|배민)']

SYSTEM = (
    '너는 한국어 소설가다. 주어진 가상 인물의 정보에 맞는 "살아온 이야기" 를 한국어로 짓는다. '
    '이야기는 모두 지어낸 것이고, 실존 인물·실제 상호·특정 사건을 넣지 않는다. '
    '성향을 설명하지 말고 행동으로 보여 준다 ("감정 표현이 큰 편이라", "즉흥적인 성격답게" 같은 설명 문구 금지). '
    '성별이나 나이를 이유로 대는 표현("여자라서", "나이 들어서")을 쓰지 않는다. '
    'JSON 외의 텍스트는 쓰지 않고, JSON 은 한 줄로 쓴다.'
)


def experience_schema() -> dict:
    return {
        'type': 'object',
        'properties': {
            'experiences': {
                'type': 'array', 'minItems': 4, 'maxItems': 4,
                'items': {
                    'type': 'object',
                    'properties': {'topic': {'type': 'string', 'enum': TOPICS}, 'text': {'type': 'string'}},
                    'required': ['topic', 'text'],
                },
            },
        },
        'required': ['experiences'],
    }


def persona_sheet(p: dict) -> str:
    traits = ', '.join(f"{TRAIT_KO[t]} {v}" for t, v in p['traits'].items())
    return (label_line(p) + '\n성향 점수(0~100): ' + traits + '\n평소 행동: ' + ' / '.join(behavior_lines(p)))


def assigned_topics(p: dict) -> list[str]:
    """페르소나마다 5개 주제 중 4개를 시드로 고른다. 모델에게 맡기면 '가족' 을 거의 쓰지 않아 주제가 치우친다"""
    rng = random.Random(7 * 1000 + p['id'])
    topics = TOPICS[:]
    rng.shuffle(topics)
    return topics[:4]


def generate_prompt(p: dict, feedback: str | None) -> str:
    topics = assigned_topics(p)
    text = (
        f'{persona_sheet(p)}\n\n'
        '이 사람이 살아온 이야기를 4개 지어라. 조건:\n'
        '- 각 이야기는 한국어 1~2문장, 40~90자, 구체적인 한 장면 (언제, 무엇을 했고, 어떻게 느꼈는지)\n'
        f'- 주제(topic)는 정확히 이 4개를 하나씩: {", ".join(topics)}\n'
        '- 위 정보와 어긋나지 않게: 미혼이면 배우자 없음, 배우자·자녀 정보에 없는 자녀는 없음, 학생이면 회사 생활 없음, '
        '사는 지역은 위에 적힌 곳 하나\n'
        f'- 연애 이야기: {relationship_rule(p)}\n'
        '- 성향 점수나 평소 행동을 그대로 설명하지 말고, 그 사람이 실제로 한 일로 보여 준다\n'
        '- 1인칭으로 쓴다 ("~했다")\n'
        '- 형식: {"experiences":[{"topic":"연애","text":"..."},...]}'
    )
    if feedback:
        text += f'\n\n앞선 초안의 문제: {feedback}\n문제를 고쳐 다시 써라.'
    return text


def regex_problems(experiences: list[dict], topics: list[str] | None = None) -> list[str]:
    problems = []
    if topics is not None and sorted(e['topic'] for e in experiences) != sorted(topics):
        problems.append(f'주제가 지정과 다름: {[e["topic"] for e in experiences]} (지정 {topics})')
    for e in experiences:
        for pattern in STEREOTYPE_PATTERNS:
            m = re.search(pattern, e['text'])
            if m:
                problems.append(f'고정관념 표현 "{m.group(0)}": {e["text"]}')
        for pattern in NAMED_ENTITY_PATTERNS:
            m = re.search(pattern, e['text'])
            if m:
                problems.append(f'실존 이름 "{m.group(0)}": {e["text"]}')
        if len(e['text']) > 160:
            problems.append(f'너무 김({len(e["text"])}자): {e["text"][:30]}…')
        if not re.search(r'[가-힣]', e['text']):
            problems.append(f'한국어가 아님: {e["text"][:30]}…')
        if re.search(r'(편이라|편이어서|성격답게|성향답게|성향이라|편이기에|편인 나는)', e['text']):
            problems.append(f'성향을 설명함: {e["text"][:40]}…')
    if len({e['topic'] for e in experiences}) < 3:
        problems.append('주제가 3개 미만')
    return problems


def relationship_rule(p: dict) -> str:
    if p['marital'] == '기혼':
        return '배우자와의 이야기여야 한다 (다른 사람과의 연애 금지)'
    if p['relationship'] == '연애 중':
        return '지금 사귀는 연인과의 이야기 (배우자·결혼 언급 금지)'
    if p['relationship'] == '썸 타는 중':
        return '아직 사귀지 않는 썸 상대와의 이야기 (연인·배우자 언급 금지)'
    return '지금 연인이 없다. 과거의 연애나 짝사랑, 혼자 보내는 시간 이야기 (현재 연인·배우자 언급 금지)'


# ⑥ 검수 = LLM 이 "무엇이 언급됐는지" 만 뽑고(추출), 모순 판정은 코드가 설정과 대조해서 한다.
# 처음에는 LLM 에게 모순 판정까지 맡겼는데 7.8B 모델은 "남자친구" 를 배우자로 보거나 "~일 수 있음" 같은 추측을
# 문제로 적어 30명 전원이 탈락했다 (docs/persona-format-bench.md 7장)
REVIEW_SYSTEM = '너는 문장에서 사실만 뽑는 도구다. 판단하지 말고, 아래 항목이 이야기에 나오는지만 JSON 으로 답한다. JSON 은 한 줄로 쓴다.'


def review_schema() -> dict:
    return {
        'type': 'object',
        'properties': {
            'spouse': {'type': 'boolean'},          # 배우자(남편·아내·신랑·신부)가 나오는가
            'ownChild': {'type': 'boolean'},        # 화자 자신의 자녀(아이·아들·딸)가 나오는가
            'currentPartner': {'type': 'boolean'},  # 지금 사귀는 연인(남자친구·여자친구·애인)이 나오는가
            'otherRomance': {'type': 'boolean'},    # 배우자·연인이 아닌 사람과의 연애·데이트가 나오는가
            'fullTimeJob': {'type': 'boolean'},     # 회사·직장에 정규로 다니는 장면이 나오는가 (아르바이트 제외)
            'cities': {'type': 'array', 'items': {'type': 'string'}},  # 이야기에 나오는 도시·지역 이름
            'stereotypeSentences': {'type': 'array', 'items': {'type': 'string'}},  # 성별·나이를 이유로 대는 문장
            'realNames': {'type': 'array', 'items': {'type': 'string'}},  # 실존 인물·실제 상호·브랜드
        },
        'required': ['spouse', 'ownChild', 'currentPartner', 'otherRomance', 'fullTimeJob', 'cities', 'stereotypeSentences', 'realNames'],
    }


def review_prompt(p: dict, experiences: list[dict]) -> str:
    stories = '\n'.join(f'- [{e["topic"]}] {e["text"]}' for e in experiences)
    return (
        f'이야기:\n{stories}\n\n'
        '위 이야기에 다음이 나오는지 사실대로 답하라.\n'
        '- spouse: 화자의 배우자(남편, 아내)가 나오는가\n'
        '- ownChild: 화자의 자녀(내 아이, 아들, 딸)가 나오는가. 남의 아이는 아니다\n'
        '- currentPartner: 지금 사귀는 연인(남자친구, 여자친구, 애인)이 나오는가. 배우자는 아니다\n'
        '- otherRomance: 배우자나 연인이 아닌 새로운 사람과 데이트하거나 사귀는 장면이 나오는가\n'
        '- fullTimeJob: 회사나 직장에 정규 직원으로 다니는 장면이 나오는가 (아르바이트, 동아리, 학교는 아니다)\n'
        '- cities: 이야기에 나오는 도시나 지역 이름 (없으면 빈 배열)\n'
        '- stereotypeSentences: "여자라서", "남자는 원래", "나이 들어서 ~해야" 처럼 성별이나 나이를 이유로 대는 문장 (없으면 빈 배열)\n'
        '- realNames: 실존 인물, 실제 상호, 브랜드 이름 (없으면 빈 배열)'
    )


# 시도별 이름과 그 안의 주요 도시. 이야기에 나온 지명이 "다른 시도" 의 이름과 맞을 때만 모순으로 본다 ("동네 시장", "한강" 은 판정하지 않는다)
REGION_ALIASES = {
    '서울': ['서울', '서울특별시'], '경기': ['경기', '경기도', '수원', '성남', '고양', '용인', '부천', '안산', '안양', '화성', '평택', '의정부', '파주', '김포', '남양주'],
    '인천': ['인천', '인천광역시'], '부산': ['부산', '부산광역시', '해운대'], '대구': ['대구', '대구광역시'], '광주': ['광주', '광주광역시'],
    '대전': ['대전', '대전광역시'], '울산': ['울산', '울산광역시'], '세종': ['세종', '세종시'],
    '경남': ['경남', '경상남도', '창원', '김해', '진주', '거제', '양산', '통영'], '경북': ['경북', '경상북도', '포항', '구미', '경주', '안동', '경산'],
    '충남': ['충남', '충청남도', '천안', '아산', '서산', '공주'], '전남': ['전남', '전라남도', '여수', '순천', '목포', '광양'],
    '전북': ['전북', '전라북도', '전북특별자치도', '전주', '익산', '군산'], '충북': ['충북', '충청북도', '청주', '충주'],
    '강원': ['강원', '강원도', '강원특별자치도', '춘천', '원주', '강릉', '속초'], '제주': ['제주', '제주도', '제주특별자치도', '서귀포'],
}


def judge_review(p: dict, extracted: dict) -> list[str]:
    """추출된 사실을 설정과 대조해 모순을 문장으로. 비어 있으면 통과"""
    problems = []
    if p['marital'] == '미혼' and extracted.get('spouse'):
        problems.append('미혼인데 배우자가 나옴')
    if p['marital'] != '기혼' and p['relationship'] == '솔로' and extracted.get('currentPartner'):
        problems.append('솔로인데 현재 연인이 나옴')
    if p['relationship'] == '썸 타는 중' and (extracted.get('currentPartner') or extracted.get('spouse')):
        problems.append('썸 타는 중인데 연인·배우자가 나옴')
    if p['marital'] == '기혼' and extracted.get('otherRomance'):
        problems.append('기혼인데 배우자 아닌 사람과의 연애가 나옴')
    if p['relationship'] == '연애 중' and extracted.get('otherRomance'):
        problems.append('연애 중인데 연인 아닌 사람과의 연애가 나옴')
    has_child = '자녀' in p['family'] or '3세대' in p['family']
    if not has_child and extracted.get('ownChild'):
        problems.append('자녀가 없는 가족 형태인데 자녀가 나옴')
    if p['status'] == '학생' and extracted.get('fullTimeJob'):
        problems.append('학생인데 회사에 정규로 다님')
    allowed = REGION_ALIASES.get(p['region'], [p['region']])
    for city in extracted.get('cities') or []:
        city = city.strip()
        if not city or any(a in city for a in allowed):
            continue
        other = next((region for region, aliases in REGION_ALIASES.items() if region != p['region'] and any(a in city for a in aliases)), None)
        if other:
            problems.append(f'다른 지역이 나옴: {city} ({other})')
            break
    for sentence in extracted.get('stereotypeSentences') or []:
        if sentence.strip():
            problems.append(f'고정관념 문장(LLM 추출): {sentence[:60]}')
            break
    for name in extracted.get('realNames') or []:
        if name.strip():
            problems.append(f'실존 이름(LLM 추출): {name}')
            break
    return problems


def build(p: dict) -> dict:
    """한 명분. 생성 → 검수 → (필요하면) 재생성. 시도 기록을 모두 남긴다"""
    started = time.perf_counter()
    attempts = []
    feedback = None
    final = None
    for attempt in range(1, MAX_ATTEMPTS + 1):
        gen = chat_json(SYSTEM, generate_prompt(p, feedback), experience_schema(), num_predict=700)
        record = {'attempt': attempt, 'generation': {k: gen[k] for k in ('error', 'promptTokens', 'evalTokens', 'wallSeconds')}}
        if gen['error'] or not gen['data']:
            record['problems'] = [f'생성 실패: {gen["error"]}']
            attempts.append(record)
            feedback = None
            continue
        experiences = gen['data']['experiences']
        problems = regex_problems(experiences, assigned_topics(p))
        rev = chat_json(REVIEW_SYSTEM, review_prompt(p, experiences), review_schema(), num_predict=300, temperature=0.1)
        record['review'] = {k: rev[k] for k in ('error', 'promptTokens', 'evalTokens', 'wallSeconds')}
        if rev['data']:
            problems += [f'LLM 검수: {x}' for x in judge_review(p, rev['data'])]
            record['extracted'] = rev['data']
        record['experiences'] = experiences
        record['problems'] = problems
        attempts.append(record)
        if not problems:
            final = experiences
            break
        feedback = ' / '.join(problems)
    if final is None:
        # 끝까지 걸리면 마지막 초안을 쓰되 표시해 둔다 (실제 기능이라면 사람 검수 대상)
        last = next((a for a in reversed(attempts) if a.get('experiences')), None)
        final = last['experiences'] if last else []
    return {
        'personaId': p['id'], 'experiences': final, 'attempts': attempts,
        'accepted': attempts[-1]['problems'] == [] if attempts else False,
        'wallSeconds': time.perf_counter() - started,
    }


def main():
    personas = json.loads((HERE / 'generated' / 'personas.json').read_text(encoding='utf-8'))['personas']
    # 모델 예열 (로딩 시간을 측정에서 뺀다)
    chat_json('답하라', '{"ok":true} 라고만 답하라', {'type': 'object', 'properties': {'ok': {'type': 'boolean'}}}, num_predict=10)
    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
        results = list(pool.map(build, personas))
    wall = time.perf_counter() - started

    accepted = sum(r['accepted'] for r in results)
    attempts = sum(len(r['attempts']) for r in results)
    regex_hits = sum(1 for r in results for a in r['attempts'] for x in a.get('problems', []) if not x.startswith('LLM 검수') and not x.startswith('생성 실패'))
    llm_hits = sum(1 for r in results for a in r['attempts'] for x in a.get('problems', []) if x.startswith('LLM 검수'))
    tokens = sum((a['generation']['evalTokens'] or 0) + (a.get('review', {}).get('evalTokens') or 0) for r in results for a in r['attempts'])
    out = {
        'model': 'exaone3.5:7.8b', 'concurrency': CONCURRENCY, 'personaCount': len(personas),
        'wallSeconds': wall, 'secondsPerPersona': wall / len(personas), 'estimatedSecondsPer1000': wall / len(personas) * 1000,
        'accepted': accepted, 'attemptsTotal': attempts, 'regexProblems': regex_hits, 'llmReviewProblems': llm_hits,
        'generatedTokens': tokens, 'results': results,
    }
    (HERE / 'generated' / 'experiences.json').write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding='utf-8')
    print(f'{len(personas)}명 {wall:.1f}s (1명당 {wall / len(personas):.2f}s, 1,000명 환산 {wall / len(personas) * 1000 / 60:.1f}분, 동시 {CONCURRENCY}). '
          f'검수 통과 {accepted}/{len(personas)}, 총 시도 {attempts}, 정규식 지적 {regex_hits}, LLM 지적 {llm_hits}')
    for r in results[:3]:
        print(f"#{r['personaId']} 시도 {len(r['attempts'])}:")
        for e in r['experiences']:
            print(f"   [{e['topic']}] {e['text']}")


if __name__ == '__main__':
    main()
