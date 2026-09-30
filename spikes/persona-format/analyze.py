"""results/*.json 을 모아 마크다운 표를 만든다. 실행: python analyze.py > results/summary.md ; python analyze.py --samples > ../../docs/persona-samples.md

측정
 - 속성 반영률: questions.expected() 가 기대 선택지를 주는 페르소나만 놓고, 기대대로 답한 비율
 - 고유성(자동): 이유에 그 페르소나 텍스트에만 있는 단어(2글자 어간)가 들어간 비율. 질문·선택지 단어와 흔한 말은 뺀다
 - 고유성(LLM): judge.py 가 specific=true 로 본 비율
 - 이유 정확 중복률, 선택지 분포, 설문 비교
"""
import argparse
import json
import re
from collections import Counter, defaultdict
from pathlib import Path

from formats import FORMAT_KO, FORMATS, render
from questions import QUESTIONS, expected, matches
from run import load_personas

HERE = Path(__file__).parent
SAMPLE_IDS = [3, 9, 14, 21, 27]

# 이유에서 흔히 나오는 말. 페르소나 고유 단어로 치지 않는다
GENERIC = set('''시간 생각 사람 자신 때문 정도 마음 이야기 지금 오늘 주말 함께 조금 정말 편안 좋아 좋은 중요 필요 안정 미래 현재 상황 경험 느낌 기분
행복 부담 여유 생활 일상 준비 계획 선택 결정 판단 가능 확실 자연 서로 관계 감정 표현 솔직 진심 소중 가치 우선 균형 스트레스 건강 돈이 돈을 비용
가격 무리 할부 노트북 결혼 직업 여가 고백 지켜 취미 가족 친구 연인 혼자 동호회 수입 적성 흥미 근무 환경 안정성 카페 저녁 아침 하루 매일 자주 가끔
남성 여성 세대 나이 사는 살고 있는 있어 없어 하는 하고 해서 이라 이다 였다 한다 된다 싶다 좋다 같다 많이 더욱 아직 이미 바로 항상 늘 그냥'''.split())


def stems(text: str) -> set[str]:
    """한글 단어의 앞 2글자 어간. 한 글자짜리와 흔한 말은 뺀다"""
    out = set()
    for word in re.findall(r'[가-힣]{2,}', text):
        stem = word[:2]
        if stem not in GENERIC:
            out.add(stem)
    return out


def load_results() -> list[dict]:
    return [json.loads(p.read_text(encoding='utf-8')) for p in sorted((HERE / 'results').glob('*.json'))]


def fmt(v, digits=0, suffix='%'):
    return '-' if v is None else f'{v:.{digits}f}{suffix}'


def specificity_auto(result: dict, question: dict, persona_stems_common: Counter, persona_count: int) -> tuple[int, int]:
    """(고유 단어가 들어간 답 수, 정상 답 수). 그 페르소나 텍스트에 있고 전체 페르소나의 40% 미만에서만 쓰이는 어간이 이유에 있으면 고유"""
    exclude = stems(question['content'] + ' ' + ' '.join(question['options']))
    hit = total = 0
    for a in result['answers']:
        if not a['ok']:
            continue
        total += 1
        own = stems(result['personaTexts'][str(a['personaId'])]) - exclude
        own = {s for s in own if persona_stems_common[s] < persona_count * 0.4}
        reason_stems = stems(a['reason'])
        if own & reason_stems:
            hit += 1
    return hit, total


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--samples', action='store_true', help='docs/persona-samples.md 용 출력')
    args = parser.parse_args()

    persona_list, experiences = load_personas()
    personas = {p['id']: p for p in persona_list}
    questions = {q['id']: q for q in QUESTIONS}
    results = load_results()
    if not results:
        print('결과 없음')
        return

    # (c) 전체 텍스트 기준으로 "어간이 얼마나 흔한가" 를 센다 (고유성 자동 판정용)
    common = Counter()
    for p in persona_list:
        for s in stems(render(p, 'experience', experiences.get(p['id']))):
            common[s] += 1

    if args.samples:
        print_samples(results, persona_list, personas, experiences, questions)
        return

    by = defaultdict(list)
    for r in results:
        by[(r['format'], r['questionId'])].append(r)

    # ---------------- 1. 비용·형식
    print('## 1. 형태별 비용과 형식 실패 (질문 5개 × 2회 평균)\n')
    print('| 형태 | 프롬프트 토큰/명 | 생성 토큰/명 | 30명 시간 | 100명 환산 | 형식 실패(40자 초과) |')
    print('|---|---|---|---|---|---|')
    for f in FORMATS:
        rs = [r for r in results if r['format'] == f]
        if not rs:
            continue
        n = len(rs)
        print(f"| {FORMAT_KO[f]} | {sum(r['promptTokensPerPersona'] for r in rs) / n:.0f} | {sum(r['evalTokensPerPersona'] for r in rs) / n:.0f} "
              f"| {sum(r['wallSeconds'] for r in rs) / n:.1f}s | {sum(r['seconds100'] for r in rs) / n:.1f}s "
              f"| {sum(r['failureCount'] for r in rs)}/{sum(r['personaCount'] for r in rs)} ({sum(r['failureCount'] for r in rs) / sum(r['personaCount'] for r in rs):.0%}) |")
    print()

    # ---------------- 2. 속성 반영률
    print('## 2. 속성 반영률 (기대 연결이 뚜렷한 페르소나만, 2회 합산)\n')
    header = '| 형태 | ' + ' | '.join(f"{q['id']} ({q['topic']})" for q in QUESTIONS) + ' | 평균 |'
    print(header)
    print('|---|' + '---|' * (len(QUESTIONS) + 1))
    for f in FORMATS:
        cells, tot_hit, tot_n = [], 0, 0
        for q in QUESTIONS:
            hit = n = 0
            for r in by.get((f, q['id']), []):
                for a in r['answers']:
                    exp = expected(q['id'], personas[a['personaId']])
                    if exp is None or not a['ok']:
                        continue
                    n += 1
                    hit += matches(exp, a['choice'])
            cells.append(f'{hit}/{n} ({hit / n:.0%})' if n else '-')
            tot_hit += hit
            tot_n += n
        print(f"| {FORMAT_KO[f]} | " + ' | '.join(cells) + f" | {fmt(tot_hit / tot_n * 100 if tot_n else None)} |")
    expected_counts = {q['id']: sum(1 for p in persona_list if expected(q['id'], p) is not None) for q in QUESTIONS}
    print(f"\n기대 연결이 뚜렷한 페르소나 수 (30명 중): " + ', '.join(f"{k} {v}명" for k, v in expected_counts.items()) + '\n')

    # ---------------- 3. 고유성
    print('## 3. 이유의 고유성 — 자동(단어 겹침) / LLM 심판 (정상 답 기준, 2회 합산)\n')
    print(header)
    print('|---|' + '---|' * (len(QUESTIONS) + 1))
    for f in FORMATS:
        cells = []
        sum_auto = [0, 0]
        sum_llm = [0, 0]
        for q in QUESTIONS:
            auto_hit = auto_n = llm_hit = llm_n = 0
            for r in by.get((f, q['id']), []):
                h, t = specificity_auto(r, q, common, len(persona_list))
                auto_hit += h
                auto_n += t
                for a in r['answers']:
                    if a['ok'] and a.get('judge'):
                        llm_n += 1
                        llm_hit += a['judge']['specific']
            sum_auto[0] += auto_hit
            sum_auto[1] += auto_n
            sum_llm[0] += llm_hit
            sum_llm[1] += llm_n
            cells.append(f"{fmt(auto_hit / auto_n * 100 if auto_n else None)} / {fmt(llm_hit / llm_n * 100 if llm_n else None)}")
        avg = f"{fmt(sum_auto[0] / sum_auto[1] * 100 if sum_auto[1] else None)} / {fmt(sum_llm[0] / sum_llm[1] * 100 if sum_llm[1] else None)}"
        print(f"| {FORMAT_KO[f]} | " + ' | '.join(cells) + f" | {avg} |")
    print()

    # ---------------- 4. 중복·분포
    print('## 4. 이유 정확 중복률과 선택지 분포 (2회 합산, 정상 답 기준)\n')
    for q in QUESTIONS:
        print(f"### {q['id']}: {q['content']}\n")
        print('| 형태 | 이유 중복 | ' + ' | '.join(q['options']) + ' |')
        print('|---|---|' + '---|' * len(q['options']))
        for f in FORMATS:
            reasons, dist = [], Counter()
            for r in by.get((f, q['id']), []):
                for a in r['answers']:
                    if a['ok']:
                        reasons.append(a['reason'])
                        dist[a['choice']] += 1
            if not reasons:
                continue
            dup = 1 - len(set(reasons)) / len(reasons)
            n = sum(dist.values())
            print(f"| {FORMAT_KO[f]} | {dup:.0%} | " + ' | '.join(f"{dist[o] / n:.0%}" for o in q['options']) + ' |')
        print()

    # ---------------- 5. 설문 비교
    print('## 5. 설문 비교 (AI 30명 × 2회 = 60답 vs 실제 설문 %)\n')
    for q in QUESTIONS:
        if not q['survey']:
            continue
        s = q['survey']
        total = sum(s['raw'].values())
        norm = {k: v / total * 100 for k, v in s['raw'].items()}
        print(f"### {q['id']}: {q['content']} — {s['name']}\n")
        print(f"{s['note']}. 출처: {s['url']}\n")
        print('| | ' + ' | '.join(q['options']) + ' | 설문과의 차이 합(절대값) |')
        print('|---|' + '---|' * (len(q['options']) + 1))
        print('| **실제 설문** | ' + ' | '.join(f"{norm[o]:.0f}%" for o in q['options']) + ' | - |')
        for f in FORMATS:
            dist = Counter()
            for r in by.get((f, q['id']), []):
                for a in r['answers']:
                    if a['ok']:
                        dist[a['choice']] += 1
            n = sum(dist.values())
            if not n:
                continue
            diff = sum(abs(dist[o] / n * 100 - norm[o]) for o in q['options'])
            print(f"| {FORMAT_KO[f]} | " + ' | '.join(f"{dist[o] / n:.0%}" for o in q['options']) + f" | {diff:.0f}p |")
        # 집단별 (성별, 미혼/기혼) — 페르소나 수가 적어 참고만
        if s.get('byGroup'):
            print()
            print('집단별 (실제 설문 → AI, 형태 (c) 기준). 페르소나가 집단당 10명 안팎이라 참고만.\n')
            print('| 집단 | ' + ' | '.join(q['options']) + ' | AI 인원 |')
            print('|---|' + '---|' * (len(q['options']) + 1))
            groups = {'남자': lambda p: p['gender'] == '남성', '여자': lambda p: p['gender'] == '여성',
                      '20대': lambda p: p['ageGroup'] == '20대', '30대': lambda p: p['ageGroup'] == '30대',
                      '미혼': lambda p: p['marital'] == '미혼', '배우자 있음': lambda p: p['marital'] == '기혼'}
            for gname, pred in groups.items():
                if gname not in s['byGroup']:
                    continue
                g = s['byGroup'][gname]
                gt = sum(g.values())
                dist, n = Counter(), 0
                for r in by.get(('experience', q['id']), []):
                    for a in r['answers']:
                        if a['ok'] and pred(personas[a['personaId']]):
                            dist[a['choice']] += 1
                            n += 1
                if not n:
                    continue
                print(f"| {gname} | " + ' | '.join(f"{g[o] / gt:.0%} → {dist[o] / n:.0%}" for o in q['options']) + f" | {n // 2}명 |")
        print()


def print_samples(results, persona_list, personas, experiences, questions):
    by = {}
    for r in results:
        if r.get('repeat', 1) == 1:
            by[(r['format'], r['questionId'])] = r
    print('# 페르소나 샘플 5명 — 직접 읽고 검수하기 위한 자료\n')
    print('`spikes/persona-format` 이 만든 30명 중 5명. 형태 (a)(b)(c) 전문과, 형태별로 각 질문에 한 답(1회차)을 나란히 둔다. '
          '(d) 는 (b) 에 질문 주제와 같은 태그의 이야기만 더한 것이라 따로 싣지 않는다. 검수 포인트: 이야기가 설정과 어긋나지 않는가, '
          '성별·나이를 이유로 대는 문장이 있는가, 답의 이유가 그 사람의 정보에서 나왔는가.\n')
    print('페르소나 전체 30명과 이야기는 `spikes/persona-format/generated/personas.json`, `experiences.json` 에 있다.\n')
    for pid in SAMPLE_IDS:
        p = personas[pid]
        exp = experiences.get(pid, [])
        print(f"## 페르소나 #{pid}\n")
        print('**성향 점수 (내부용, 프롬프트에는 넣지 않음)**: ' + ', '.join(f"{k} {v}" for k, v in p['traits'].items()) + '\n')
        print('**(a) 라벨만**\n')
        print('```\n' + render(p, 'label', None) + '\n```\n')
        print('**(b) 라벨 + 행동 문장**\n')
        print('```\n' + render(p, 'behavior', None) + '\n```\n')
        print('**(c) 라벨 + 행동 문장 + 경험 서술**\n')
        print('```\n' + render(p, 'experience', exp) + '\n```\n')
        print('**질문별 답 (1회차)**\n')
        print('| 질문 | 기대 | ' + ' | '.join(FORMAT_KO[f] for f in FORMATS) + ' |')
        print('|---|---|' + '---|' * len(FORMATS))
        for q in QUESTIONS:
            e = expected(q['id'], p)
            e_text = '/'.join(sorted(e)) if isinstance(e, set) else (e or '-')
            cells = []
            for f in FORMATS:
                r = by.get((f, q['id']))
                a = next((x for x in r['answers'] if x['personaId'] == pid), None) if r else None
                if not a:
                    cells.append('-')
                elif not a['ok']:
                    cells.append(f"{a.get('choice', '-')} — {a.get('reason', '')} (40자 초과)")
                else:
                    mark = ' ✔' if e is not None and matches(e, a['choice']) else (' ✘' if e is not None else '')
                    cells.append(f"**{a['choice']}**{mark} — {a['reason']}")
            print(f"| {q['topic']}: {q['content'][:18]}… | {e_text} | " + ' | '.join(cells) + ' |')
        print()


if __name__ == '__main__':
    main()
