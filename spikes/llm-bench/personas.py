"""가짜 페르소나 100명 생성. LLM 없이 고정 시드 난수로 만들어 매번 같은 결과가 나온다.

속성: 연령대, 성별, 연애 상태, 직업군, 한 줄 성격, 관심사 2개.
성격 문구와 관심사는 다른 속성에서 파생되게 해서, 모델이 속성과 답을 연결할 여지를 남긴다.

실행: python personas.py  → personas.json
"""
import json
import random
from pathlib import Path

SEED = 20260930
COUNT = 100

AGE_GROUPS = ['10대', '20대', '30대', '40대', '50대 이상']
AGE_WEIGHTS = [10, 35, 30, 15, 10]  # 앱의 주 사용층(20~30대)이 많게
GENDERS = ['남성', '여성']
RELATIONSHIPS = ['솔로', '썸 타는 중', '연애 중', '기혼']
JOBS = ['학생', '개발자', '디자이너', '영업직', '공무원', '간호사', '자영업', '서비스직', '프리랜서', '교사', '금융권', '제조업']

# 연령대별로 어울리는 직업·연애 상태만 고른다
JOBS_BY_AGE = {
    '10대': ['학생'],
    '20대': ['학생', '개발자', '디자이너', '영업직', '간호사', '서비스직', '프리랜서', '공무원'],
    '30대': ['개발자', '디자이너', '영업직', '공무원', '간호사', '자영업', '서비스직', '프리랜서', '교사', '금융권', '제조업'],
    '40대': ['개발자', '영업직', '공무원', '자영업', '교사', '금융권', '제조업', '간호사'],
    '50대 이상': ['자영업', '공무원', '교사', '제조업', '영업직'],
}
RELATIONSHIPS_BY_AGE = {
    '10대': ['솔로', '썸 타는 중', '연애 중'],
    '20대': ['솔로', '썸 타는 중', '연애 중', '기혼'],
    '30대': ['솔로', '썸 타는 중', '연애 중', '기혼'],
    '40대': ['솔로', '연애 중', '기혼'],
    '50대 이상': ['솔로', '기혼'],
}

INTERESTS = ['운동', '요리', '여행', '게임', '독서', '음악', '영화', '카페 탐방', '캠핑', '재테크', '반려동물', '사진', '패션', '등산', '드라마']

# 성격은 "기질 + 태도" 두 조각을 붙인다. 조각마다 연애·돈·모험에 대한 성향이 다르다
TEMPERAMENTS = [
    '계획을 세워야 마음이 놓이는 편',
    '즉흥적으로 움직이는 걸 좋아하는 편',
    '돈 쓰는 데 신중한 편',
    '분위기와 경험에 아낌없이 쓰는 편',
    '낯을 가리지만 친해지면 말이 많은 편',
    '처음 보는 사람과도 금방 친해지는 편',
    '조용한 곳에서 오래 이야기하는 걸 좋아하는 편',
    '새로운 도전을 즐기는 편',
    '안정적인 걸 최우선으로 두는 편',
    '남 눈치를 잘 보는 편',
]
ATTITUDES = [
    '현실적인 조언을 좋아한다',
    '감성적인 이유에 잘 흔들린다',
    '효율을 따진다',
    '남들이 안 하는 선택을 해 보고 싶어 한다',
    '가족과 주변 사람을 먼저 생각한다',
    '건강을 챙기기 시작했다',
    '요즘 일에 지쳐 있다',
    '취미에 진심이다',
]


def generate(count: int = COUNT, seed: int = SEED) -> list[dict]:
    rng = random.Random(seed)
    personas = []
    for persona_id in range(1, count + 1):
        age = rng.choices(AGE_GROUPS, weights=AGE_WEIGHTS)[0]
        gender = rng.choice(GENDERS)
        relationship = rng.choice(RELATIONSHIPS_BY_AGE[age])
        job = rng.choice(JOBS_BY_AGE[age])
        personality = f'{rng.choice(TEMPERAMENTS)}이고 {rng.choice(ATTITUDES)}'
        interests = rng.sample(INTERESTS, 2)
        personas.append({
            'id': persona_id,
            'ageGroup': age,
            'gender': gender,
            'relationship': relationship,
            'job': job,
            'personality': personality,
            'interests': interests,
        })
    return personas


def describe(p: dict) -> str:
    """프롬프트에 넣는 한 줄 설명"""
    return (f"#{p['id']}: {p['ageGroup']} {p['gender']}, {p['relationship']}, {p['job']}. "
            f"{p['personality']}. 관심사: {', '.join(p['interests'])}")


if __name__ == '__main__':
    personas = generate()
    out = Path(__file__).with_name('personas.json')
    out.write_text(json.dumps(personas, ensure_ascii=False, indent=1), encoding='utf-8')
    print(f'{len(personas)}명 → {out.name}')
    for p in personas[:5]:
        print(describe(p))
