"""results/*.json 을 모아 마크다운 표로 만든다. 실행: python analyze.py > results/summary.md

표
 1. 모델 × 질문 × 방식: 시간, 토큰 속도, 실패율, VRAM
 2. 다양성: 선택지 분포, 이유 문장 중복률(정확 일치), 이유 사이 평균 유사도(글자 2-gram 자카드), 속성-답 연결 지표
 3. 품질 샘플: 방식별 5명의 답 (같은 5명)
"""
import itertools
import json
import re
from collections import Counter, defaultdict
from pathlib import Path

from personas import generate

HERE = Path(__file__).parent
SAMPLE_IDS = [3, 27, 51, 78, 96]  # 샘플로 보여 줄 페르소나 (연령대·연애 상태가 다르게 섞인 5명)
METHOD_ORDER = ['1x1', '25x1', '1x8', '5x4', '10x4', '25x4', '5x8', '10x8']

# 관심사 → 어울리는 취미 선택지. 페르소나의 관심사와 답이 연결되는지 보는 기준
HOBBY_LINK = {'음악': '기타', '운동': '수영', '요리': '베이킹', '게임': '보드게임'}


def load_results() -> list[dict]:
    results = []
    for path in sorted((HERE / 'results').glob('*.json')):
        if '_n' in path.stem.split('__')[-1]:
            continue  # --limit 로 돌린 시험 파일은 뺀다
        results.append(json.loads(path.read_text(encoding='utf-8')))
    return results


def bigrams(text: str) -> set[str]:
    t = re.sub(r'\s+', '', text)
    return {t[i:i + 2] for i in range(len(t) - 1)}


def avg_pairwise_jaccard(reasons: list[str]) -> float | None:
    grams = [bigrams(r) for r in reasons if r]
    pairs = list(itertools.combinations(grams, 2))
    if not pairs:
        return None
    total = 0.0
    for a, b in pairs:
        union = a | b
        total += len(a & b) / len(union) if union else 0.0
    return total / len(pairs)


def method_key(m: str) -> tuple:
    return (METHOD_ORDER.index(m) if m in METHOD_ORDER else 99, m)


def label(r: dict) -> str:
    """표에 쓰는 방식 이름. 반복 실행은 태그를 붙인다"""
    return r['method'] + (f" ({r['tag']})" if r.get('tag') else '')


def fmt(v, digits=1, suffix=''):
    if v is None:
        return '-'
    return f'{v:.{digits}f}{suffix}'


def linkage(result: dict, personas: dict[int, dict]) -> str:
    """질문별로 페르소나 속성과 답의 연결을 한 줄 지표로"""
    ok = [a for a in result['answers'] if a['ok']]
    if not ok:
        return '-'
    qid = result['questionId']
    if qid == 'date':
        by_rel = defaultdict(Counter)
        for a in ok:
            by_rel[personas[a['personaId']]['relationship']][a['choice']] += 1
        parts = []
        for rel in ['솔로', '썸 타는 중', '연애 중', '기혼']:
            c = by_rel.get(rel)
            if c:
                n = sum(c.values())
                parts.append(f"{rel} 카페 {c['카페'] / n:.0%}")
        return ', '.join(parts)
    if qid == 'job':
        stable = [a for a in ok if '안정적인' in personas[a['personaId']]['personality']]
        bold = [a for a in ok if '새로운 도전' in personas[a['personaId']]['personality']]
        s = sum(a['choice'] == '남는다' for a in stable) / len(stable) if stable else None
        b = sum(a['choice'] == '이직한다' for a in bold) / len(bold) if bold else None
        return f"안정 지향({len(stable)}명) 남는다 {fmt(s * 100 if s is not None else None, 0, '%')}, 도전 지향({len(bold)}명) 이직 {fmt(b * 100 if b is not None else None, 0, '%')}"
    if qid == 'hobby':
        matched = total = 0
        for a in ok:
            expected = {HOBBY_LINK[i] for i in personas[a['personaId']]['interests'] if i in HOBBY_LINK}
            if expected:
                total += 1
                matched += a['choice'] in expected
        return f'관심사와 맞는 취미 {matched}/{total} ({matched / total:.0%})' if total else '-'
    return '-'


def main():
    personas = {p['id']: p for p in generate()}
    results = load_results()
    if not results:
        print('결과가 없습니다.')
        return
    models = sorted({r['model'] for r in results})
    questions = json.loads((HERE / 'questions.json').read_text(encoding='utf-8'))
    qids = [q['id'] for q in questions]
    opts = {q['id']: q['options'] for q in questions}

    print('## 1. 속도·실패율\n')
    for model in models:
        print(f'### {model}\n')
        print('| 질문 | 방식 | 요청 수 | 100명 전체 | 첫 결과 | 생성 tok/s (전체) | tok/s (요청당) | 프롬프트 토큰 | 생성 토큰 | 형식 실패 | 실패 종류 | 잘림 | VRAM 최대 |')
        print('|---|---|---|---|---|---|---|---|---|---|---|---|---|')
        for qid in qids:
            rows = sorted([r for r in results if r['model'] == model and r['questionId'] == qid], key=lambda r: (method_key(r['method']), r.get('tag', '')))
            for r in rows:
                kinds = ', '.join(f'{k} {v}' for k, v in sorted(r['failureKinds'].items())) or '-'
                print(f"| {qid} | {label(r)} | {r['requestCount']} | {fmt(r['wallSeconds'], 1, 's')} | {fmt(r['firstResultSeconds'], 1, 's')} "
                      f"| {fmt(r['tokensPerSecondOverall'])} | {fmt(r['tokensPerSecondPerStream'])} | {r['promptTokens']} | {r['evalTokens']} "
                      f"| {r['failureCount']}/{r['personaCount']} ({r['failureRate']:.0%}) | {kinds} | {r['truncated']} | {r['vramPeakMiB']} MiB (기준 {r['vramBaselineMiB']}) |")
        print()

    print('## 2. 다양성·속성 연결\n')
    for model in models:
        print(f'### {model}\n')
        print('| 질문 | 방식 | 선택지 분포 (정상 답 기준) | 이유 정확 중복 | 이유 평균 유사도 (2-gram 자카드) | 속성-답 연결 |')
        print('|---|---|---|---|---|---|')
        for qid in qids:
            rows = sorted([r for r in results if r['model'] == model and r['questionId'] == qid], key=lambda r: (method_key(r['method']), r.get('tag', '')))
            for r in rows:
                ok = [a for a in r['answers'] if a['ok']]
                dist = Counter(a['choice'] for a in ok)
                dist_text = ', '.join(f'{o} {dist.get(o, 0)}' for o in opts[qid])
                reasons = [a['reason'] for a in ok]
                dup = 1 - len(set(reasons)) / len(reasons) if reasons else None
                print(f"| {qid} | {label(r)} | {dist_text} | {fmt(dup * 100 if dup is not None else None, 0, '%')} "
                      f"| {fmt(avg_pairwise_jaccard(reasons), 3)} | {linkage(r, personas)} |")
        print()

    print('## 3. 품질 샘플 (질문 "date": 소개팅 첫 만남, 카페 / 밥집)\n')
    for pid in SAMPLE_IDS:
        p = personas[pid]
        print(f"- #{pid}: {p['ageGroup']} {p['gender']}, {p['relationship']}, {p['job']}. {p['personality']}. 관심사: {', '.join(p['interests'])}")
    print()
    for model in models:
        rows = sorted([r for r in results if r['model'] == model and r['questionId'] == 'date' and not r.get('tag')],
                      key=lambda r: method_key(r['method']))
        for r in rows:
            print(f"#### {model} / {r['method']}\n")
            print('| 페르소나 | 선택 | 이유 |')
            print('|---|---|---|')
            by_id = {a['personaId']: a for a in r['answers']}
            for pid in SAMPLE_IDS:
                a = by_id.get(pid, {})
                mark = '' if a.get('ok') else f" (형식 실패: {a.get('failure')})"
                print(f"| #{pid} | {a.get('choice', '-')} | {a.get('reason', '-')}{mark} |")
            print()


if __name__ == '__main__':
    main()
