"""페르소나 변수 뼈대 (docs/persona-schema.md 의 코드판).

①~③ 의 변수 15개를 고정 시드 난수로 뽑고, ④ 점수 → 행동 문장까지 만든다. LLM 은 쓰지 않는다 (⑤ 는 experiences.py).
분포의 근거와 "가정" 표시는 docs/persona-schema.md 에 있다. 여기서는 그 수치를 그대로 쓴다.

실행: python schema.py  → generated/personas.json (30명)
"""
import json
import random
from pathlib import Path

HERE = Path(__file__).parent
SEED = 20261001
COUNT = 30

# ---------------------------------------------------------------- ① 기본 정보

# 연령대 가중치: 2025.12.31 주민등록 인구통계 (15~19세는 10대의 절반으로 가정)
AGE_BANDS = [(15, 19, 4.5), (20, 29, 11.12), (30, 39, 13.06), (40, 49, 14.83), (50, 59, 16.89), (60, 69, 15.50)]
GENDERS = [('남성', 49.76), ('여성', 50.24)]  # 2025.12.31 주민등록
# 수도권 51.0% (2025 등록센서스). 시도별 배분은 못 찾아 대략의 인구 규모로 가정 (합 100)
REGIONS = [('서울', 18.0), ('경기', 27.0), ('인천', 6.0), ('부산', 6.2), ('대구', 4.5), ('광주', 2.7), ('대전', 2.8), ('울산', 2.1),
           ('세종', 0.8), ('경남', 6.2), ('경북', 4.8), ('충남', 4.2), ('전남', 3.4), ('전북', 3.3), ('충북', 3.1), ('강원', 2.9), ('제주', 1.3)]

# 취업자의 직업 대분류 구성비 (2024 경제활동인구조사 연간). 각 대분류의 구체 직업은 가정
JOB_CATEGORIES = [
    ('관리자', 1.5, ['팀장', '영업 본부장', '공장장']),
    ('전문가', 22.4, ['개발자', '간호사', '교사', '디자이너', '회계사', '연구원', '약사', '학원 강사', '물리치료사']),
    ('사무', 17.7, ['사무직 회사원', '공무원', '은행원', '인사 담당자', '경리']),
    ('서비스', 12.3, ['미용사', '요양보호사', '카페 사장', '음식점 직원', '보육교사']),
    ('판매', 8.8, ['매장 판매원', '보험 설계사', '자영업(옷가게)', '온라인 쇼핑몰 운영']),
    ('농림어업', 5.1, ['과수원 농부', '벼농사 농부', '양식장 운영']),
    ('기능원', 7.9, ['자동차 정비사', '인테리어 목수', '전기 기사', '용접공']),
    ('장치·기계 조작', 10.4, ['택배 기사', '버스 기사', '생산직(자동차 부품)', '지게차 기사']),
    ('단순노무', 13.9, ['건물 청소원', '경비원', '물류센터 작업자', '주방 보조']),
]
# 연령대별 고용률 (2024): 15~29세 46.1, 30대 80.1, 40대 79.1, 50대 77.5, 60세 이상 45.9
# 취업하지 않은 사람의 상태는 연령대별 가정 (비경활 구성비: 재학 20.2%, 가사 37.2%, 연로 15.6%, 쉬었음 15.3% 을 참고)
STATUS_BY_AGE = {
    # (취업, 학생, 구직·쉬는 중, 가사·육아, 은퇴)
    (15, 19): (8, 90, 2, 0, 0),
    (20, 24): (45, 45, 8, 2, 0),
    (25, 29): (72, 12, 12, 4, 0),
    (30, 39): (80, 2, 8, 10, 0),
    (40, 49): (79, 0, 6, 15, 0),
    (50, 59): (77, 0, 6, 14, 3),
    (60, 69): (46, 0, 4, 15, 35),
}

# 학력 (최종). 30세 이상 대학 이상 43.1% / 고졸 36.1% / 중졸 이하 17.3% (2020 인구주택총조사).
# 젊은 층은 대학 진학률이 높아 연령대별로 다르게 가정. 대학 이상의 세부(전문대/대졸/대학원)는 가정
EDU_BY_AGE = {
    # (중졸 이하, 고졸, 전문대졸, 대졸, 대학원)
    (20, 29): (1, 25, 18, 50, 6),
    (30, 39): (2, 25, 18, 45, 10),
    (40, 49): (4, 33, 16, 39, 8),
    (50, 59): (10, 42, 12, 30, 6),
    (60, 69): (25, 40, 8, 23, 4),
}

# ---------------------------------------------------------------- ② 성향 점수 (모두 가정)
TRAITS = ['extraversion', 'caution', 'risk', 'spending', 'emotion', 'tradition']
TRAIT_KO = {
    'extraversion': '외향성', 'caution': '신중함', 'risk': '위험 선호',
    'spending': '소비 성향', 'emotion': '감정 표현', 'tradition': '전통 지향',
}

# ---------------------------------------------------------------- ③ 생활 변수

# 연령대별 미혼율: 20대 96.0, 30대 54.7, 40대 21.9 (2025 등록센서스). 50대 이상은 가정.
UNMARRIED_BY_AGE = {(15, 19): 100, (20, 29): 96, (30, 39): 55, (40, 49): 22, (50, 59): 10, (60, 69): 6}
# 기혼이 아닌 이들 중 이혼·사별 비율은 가정 (18세 이상 전체 사별·이혼 14.1% 를 연령에 따라 나눔)
DIVORCED_BY_AGE = {(15, 19): 0, (20, 29): 1, (30, 39): 5, (40, 49): 12, (50, 59): 20, (60, 69): 30}
# 미혼자의 연애 상태 (가정)
SINGLE_RELATIONSHIP = [('솔로', 55), ('썸 타는 중', 12), ('연애 중', 33)]
# 가족 형태 (1인 가구 36.6% 를 참고한 조건부 가정)
FAMILY_UNMARRIED_YOUNG = [('부모와 함께', 60), ('1인 가구', 35), ('형제·친구와 함께', 5)]
FAMILY_UNMARRIED_ADULT = [('1인 가구', 62), ('부모와 함께', 33), ('형제·친구와 함께', 5)]
FAMILY_MARRIED = [('배우자·자녀와 함께', 65), ('배우자와 둘이', 30), ('부모·배우자·자녀 3세대', 5)]
FAMILY_DIVORCED = [('1인 가구', 50), ('자녀와 함께', 40), ('부모와 함께', 10)]
# 경제 상황: 2025 사회조사 계층 의식 상 3.8 / 중 61.6 / 하 34.6 → 여유 / 보통 / 빠듯. 상태별 보정은 가정
ECONOMY = [('여유 있는 편', 3.8), ('보통', 61.6), ('빠듯한 편', 34.6)]


def pick(rng: random.Random, table: list[tuple]) -> str:
    return rng.choices([t[0] for t in table], weights=[t[1] for t in table])[0]


def band_of(age: int, table: dict) -> tuple:
    for (lo, hi), value in table.items():
        if lo <= age <= hi:
            return value
    raise ValueError(age)


def score(rng: random.Random, mean: float = 50, sd: float = 18) -> int:
    return max(0, min(100, round(rng.gauss(mean, sd))))


def generate_one(rng: random.Random, persona_id: int) -> dict:
    """제약을 어기면 None 을 돌려준다. 호출자가 다시 뽑는다"""
    lo, hi = rng.choices([(b[0], b[1]) for b in AGE_BANDS], weights=[b[2] for b in AGE_BANDS])[0]
    age = rng.randint(lo, hi)
    gender = pick(rng, GENDERS)
    region = pick(rng, REGIONS)

    # 활동 상태 → 직업
    employed, student, seeking, home, retired = band_of(age, STATUS_BY_AGE)
    status = rng.choices(['취업', '학생', '구직·쉬는 중', '가사·육아', '은퇴'], weights=[employed, student, seeking, home, retired])[0]
    if status == '취업':
        category = rng.choices([c[0] for c in JOB_CATEGORIES], weights=[c[1] for c in JOB_CATEGORIES])[0]
        titles = next(c[2] for c in JOB_CATEGORIES if c[0] == category)
        job = rng.choice(titles)
    elif status == '학생':
        category = '학생'
        job = '고등학생' if age <= 19 else ('대학원생' if age >= 24 and rng.random() < 0.3 else '대학생')
    else:
        category = status
        job = status

    # 학력
    if status == '학생':
        education = {'고등학생': '고등학교 재학', '대학생': '대학교 재학', '대학원생': '대학원 재학'}[job]
    elif age <= 19:
        education = '고등학교 재학' if rng.random() < 0.8 else '고졸'
    else:
        weights = band_of(age, EDU_BY_AGE)
        education = rng.choices(['중졸 이하', '고졸', '전문대졸', '대졸', '대학원졸'], weights=weights)[0]

    # 성향 점수. 전통 지향은 나이와 약한 상관 (가정), 소비 성향은 신중함과 약한 음의 상관 (가정)
    traits = {t: score(rng) for t in TRAITS}
    traits['tradition'] = score(rng, mean=50 + (age - 40) * 0.5)
    traits['spending'] = score(rng, mean=50 - (traits['caution'] - 50) * 0.3)

    # 연애·결혼
    unmarried = rng.random() * 100 < band_of(age, UNMARRIED_BY_AGE)
    if unmarried:
        marital = '미혼'
        relationship = pick(rng, SINGLE_RELATIONSHIP)
    elif rng.random() * 100 < band_of(age, DIVORCED_BY_AGE):
        marital = '이혼·사별'
        relationship = '솔로' if rng.random() < 0.8 else '연애 중'
    else:
        marital = '기혼'
        relationship = '기혼'

    # 가족 형태
    if marital == '미혼':
        family = pick(rng, FAMILY_UNMARRIED_YOUNG if age <= 26 else FAMILY_UNMARRIED_ADULT)
    elif marital == '기혼':
        family = pick(rng, FAMILY_MARRIED)
        if age <= 27 and family == '배우자·자녀와 함께' and rng.random() < 0.5:
            family = '배우자와 둘이'
    else:
        family = pick(rng, FAMILY_DIVORCED)

    # 경제 상황 (상태별 보정: 학생·구직·쉬는 중은 빠듯한 쪽으로)
    table = ECONOMY
    if status in ('학생', '구직·쉬는 중'):
        table = [('여유 있는 편', 2), ('보통', 48), ('빠듯한 편', 50)]
    economy = pick(rng, table)

    # 직장·학교 생활 (경력 단계)
    if status == '취업':
        if category == '관리자':
            career = '관리자 (부하 직원 있음)'
        elif category in ('서비스', '판매') and '사장' in job or '자영업' in job or '운영' in job or '농부' in job:
            career = f'자영업 {min(age - 20, rng.randint(1, 20))}년째'
        else:
            years = min(age - 19, rng.choice([1, 2, 3, 5, 8, 12, 18, 25]))
            years = max(years, 1)
            career = f'경력 {years}년' + (' (신입)' if years <= 2 else ' (중견)' if years <= 10 else ' (베테랑)')
    elif status == '학생':
        career = {'고등학생': f'고등학교 {min(3, max(1, age - 15))}학년', '대학생': f'대학교 {min(4, max(1, age - 19))}학년',
                  '대학원생': '대학원 석사 과정'}[job]
    else:
        career = status

    persona = {
        'id': persona_id,
        'age': age, 'ageGroup': f'{age // 10 * 10}대', 'gender': gender, 'region': region,
        'status': status, 'jobCategory': category, 'job': job, 'education': education,
        'traits': traits,
        'marital': marital, 'relationship': relationship, 'family': family, 'economy': economy, 'career': career,
    }
    return persona if check_constraints(persona) == [] else None


def check_constraints(p: dict) -> list[str]:
    """어긋난 제약을 문장으로 돌려준다. 비어 있으면 통과 (docs/persona-schema.md 의 제약 규칙과 1:1)"""
    problems = []
    age = p['age']
    if age <= 19 and p['marital'] != '미혼':
        problems.append('10대는 미혼이어야 한다')
    if age <= 24 and p['marital'] == '이혼·사별':
        problems.append('24세 이하 이혼·사별 금지')
    if p['jobCategory'] == '관리자' and age < 35:
        problems.append('관리자는 35세 이상')
    if p['status'] == '학생' and age > 34:
        problems.append('학생은 34세 이하')
    if p['job'] == '고등학생' and age > 19:
        problems.append('고등학생은 19세 이하')
    if p['education'] in ('대학원졸', '대학원 재학') and age < 24:
        problems.append('대학원은 24세 이상')
    if p['education'] == '대졸' and age < 22:
        problems.append('대졸은 22세 이상')
    if p['marital'] == '미혼' and '배우자' in p['family']:
        problems.append('미혼은 배우자 가구 금지')
    if p['marital'] == '기혼' and p['family'] in ('부모와 함께', '형제·친구와 함께'):
        problems.append('기혼은 부모·친구 동거 가구 금지')
    if p['status'] == '은퇴' and age < 55:
        problems.append('은퇴는 55세 이상')
    if p['status'] == '가사·육아' and age < 25:
        problems.append('가사·육아는 25세 이상')
    if p['jobCategory'] == '농림어업' and p['region'] in ('서울', '인천', '부산', '대구', '광주', '대전', '울산'):
        problems.append('농림어업은 특별시·광역시 밖')
    if p['marital'] == '기혼' and p['relationship'] != '기혼':
        problems.append('기혼의 연애 상태는 기혼')
    return problems


def generate(count: int = COUNT, seed: int = SEED) -> tuple[list[dict], int]:
    """(페르소나 목록, 제약 위반으로 다시 뽑은 횟수)"""
    rng = random.Random(seed)
    personas, rejected = [], 0
    while len(personas) < count:
        p = generate_one(rng, len(personas) + 1)
        if p is None:
            rejected += 1
            continue
        personas.append(p)
    return personas, rejected


# ---------------------------------------------------------------- ④ 점수 → 행동 문장

# 구간: 낮음 ≤ 35, 중간 36~64, 높음 ≥ 65. 문장은 "그 사람이 실제로 하는 행동" 으로 쓴다. 성별·나이를 이유로 대는 표현은 쓰지 않는다
BEHAVIORS = {
    'extraversion': {
        'low': ['모임 약속이 잡히면 전날부터 빠질 핑계를 생각한다', '주말에는 연락을 꺼 두고 혼자 보내는 시간이 제일 편하다'],
        'mid': ['친한 사람 몇 명과는 자주 만나지만 새 모임에는 잘 안 나간다'],
        'high': ['처음 보는 사람에게 먼저 말을 걸고 번호를 받는다', '주말 약속이 비어 있으면 불안해서 누구든 불러낸다'],
    },
    'caution': {
        'low': ['메뉴는 1초 만에 고르고, 후회해도 금방 잊는다', '여행은 숙소만 잡고 나머지는 가서 정한다'],
        'mid': ['큰 결정은 하루 정도 생각하고, 작은 건 바로 정한다'],
        'high': ['물건 하나 사기 전에 후기를 세 번은 찾아본다', '약속 장소는 미리 지도로 동선까지 확인해 둔다'],
    },
    'risk': {
        'low': ['적금 말고는 돈을 굴려 본 적이 없다', '아는 길로만 다니고 새 식당은 남이 먼저 가 본 뒤에 간다'],
        'mid': ['남들이 하는 정도의 새 시도는 따라가지만 앞장서지는 않는다'],
        'high': ['주식·코인에 월급의 일부를 꾸준히 넣는다', '해 본 적 없는 일이면 오히려 먼저 손을 든다'],
    },
    'spending': {
        'low': ['가계부를 쓰고 한 달 예산을 넘기면 다음 달에 메운다', '커피는 집에서 내려 텀블러에 담아 다닌다'],
        'mid': ['평소엔 아끼지만 여행이나 선물엔 아끼지 않는다'],
        'high': ['마음에 들면 가격표를 보기 전에 계산대로 간다', '배달 앱을 하루에 한 번은 켠다'],
    },
    'emotion': {
        'low': ['속상해도 겉으로는 별일 없는 척한다', '고맙다는 말을 문자로는 해도 얼굴 보고는 잘 못 한다'],
        'mid': ['가까운 사람에게는 기분을 말하지만 밖에서는 잘 드러내지 않는다'],
        'high': ['기쁘면 바로 전화해서 자랑하고, 서운하면 그 자리에서 말한다', '영화 보다가 잘 운다'],
    },
    'tradition': {
        'low': ['명절에 꼭 모여야 한다는 생각이 없고 각자 편한 대로 하자는 쪽이다', '결혼이나 출산은 하고 싶은 사람만 하면 된다고 말한다'],
        'mid': ['제사나 명절 모임은 참석하지만 형식은 줄이자는 쪽이다'],
        'high': ['명절 아침에는 온 가족이 모여야 한다고 생각한다', '결혼은 때가 되면 하는 것이라고 말한다'],
    },
}

TRAIT_LABELS = {
    'extraversion': ('내향적인 편', '외향적인 편'),
    'caution': ('즉흥적인 편', '신중한 편'),
    'risk': ('안정을 찾는 편', '모험을 즐기는 편'),
    'spending': ('아끼는 편', '잘 쓰는 편'),
    'emotion': ('감정을 안 드러내는 편', '감정 표현이 큰 편'),
    'tradition': ('개방적인 편', '전통을 중시하는 편'),
}


def band(value: int) -> str:
    return 'low' if value <= 35 else 'high' if value >= 65 else 'mid'


def behaviors(p: dict, rng_seed: int | None = None, count: int = 4) -> list[str]:
    """점수가 중간에서 먼 성향부터 count 개. 같은 성향에서 문장을 고르는 것도 시드로 고정한다"""
    rng = random.Random((rng_seed if rng_seed is not None else SEED) * 1000 + p['id'])
    ordered = sorted(TRAITS, key=lambda t: -abs(p['traits'][t] - 50))
    lines = []
    for trait in ordered[:count]:
        lines.append(rng.choice(BEHAVIORS[trait][band(p['traits'][trait])]))
    return lines


def trait_labels(p: dict, count: int = 2) -> list[str]:
    """(a) 라벨 형태에 쓰는 형용사. 중간에서 먼 성향 count 개"""
    ordered = sorted(TRAITS, key=lambda t: -abs(p['traits'][t] - 50))
    labels = []
    for trait in ordered[:count]:
        low, high = TRAIT_LABELS[trait]
        labels.append(high if p['traits'][trait] >= 50 else low)
    return labels


if __name__ == '__main__':
    personas, rejected = generate()
    out = HERE / 'generated' / 'personas.json'
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps({'seed': SEED, 'rejected': rejected, 'personas': personas}, ensure_ascii=False, indent=1), encoding='utf-8')
    print(f'{len(personas)}명 생성, 제약 위반으로 다시 뽑음 {rejected}회 → {out.name}')
    for p in personas[:3]:
        print(p['id'], p['age'], p['gender'], p['region'], p['job'], p['education'], p['marital'], p['relationship'], p['family'], p['economy'], p['career'], p['traits'])
        print('  ', behaviors(p))
