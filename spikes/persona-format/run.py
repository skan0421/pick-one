"""형태 비교 실행기. 1차 실행기(spikes/llm-bench/bench.py)의 chat() 을 재사용하고, 페르소나 설명만 형태별로 바꿔 끼운다.

exaone3.5:7.8b, 1명씩 × 동시 8개 (1x8). 형태 4 × 질문 5 × 2회 = 40회, 회당 30명.
결과: results/{format}__{question}__r{n}.json (요청·응답 원문, 형태별 프롬프트 토큰, 시간)

실행: python run.py [--formats label,behavior] [--questions love,job] [--repeats 2]
"""
import argparse
import json
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE.parent / 'llm-bench'))

import bench  # noqa: E402  (1차 실행기)
from formats import FORMATS, render  # noqa: E402
from questions import QUESTIONS  # noqa: E402

MODEL = 'exaone3.5:7.8b'
CONCURRENCY = 8


def load_personas() -> tuple[list[dict], dict[int, list[dict]]]:
    personas = json.loads((HERE / 'generated' / 'personas.json').read_text(encoding='utf-8'))['personas']
    exp = json.loads((HERE / 'generated' / 'experiences.json').read_text(encoding='utf-8'))
    experiences = {r['personaId']: r['experiences'] for r in exp['results']}
    return personas, experiences


def run_one(fmt: str, question: dict, personas: list[dict], experiences: dict[int, list[dict]]) -> dict:
    texts = {p['id']: render(p, fmt, experiences.get(p['id']), question['topic']) for p in personas}

    def describe_fn(p: dict) -> str:
        return texts[p['id']]

    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
        calls = list(pool.map(lambda p: bench.chat(MODEL, question, [p], describe_fn=describe_fn), personas))
    wall = time.perf_counter() - started

    answers = []
    for call in calls:
        answers.extend(bench.parse_answers(call, question))
    answers.sort(key=lambda a: a['personaId'])
    ok_calls = [c for c in calls if not c['error']]
    return {
        'model': MODEL, 'format': fmt, 'questionId': question['id'], 'topic': question['topic'], 'method': f'1x{CONCURRENCY}',
        'personaCount': len(personas), 'wallSeconds': wall,
        'promptTokensPerPersona': sum(c['promptTokens'] or 0 for c in ok_calls) / max(1, len(ok_calls)),
        'evalTokensPerPersona': sum(c['evalTokens'] or 0 for c in ok_calls) / max(1, len(ok_calls)),
        'seconds100': wall / len(personas) * 100,
        'failureCount': sum(1 for a in answers if not a['ok']),
        'failureKinds': _kinds(answers),
        'personaTexts': texts,
        'answers': answers,
        'calls': calls,
    }


def _kinds(answers: list[dict]) -> dict:
    kinds: dict[str, int] = {}
    for a in answers:
        if not a['ok']:
            kinds[a['failure']] = kinds.get(a['failure'], 0) + 1
    return kinds


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--formats', default=','.join(FORMATS))
    parser.add_argument('--questions', default=','.join(q['id'] for q in QUESTIONS))
    parser.add_argument('--repeats', type=int, default=2)
    parser.add_argument('--skip-existing', action='store_true')
    args = parser.parse_args()

    personas, experiences = load_personas()
    questions = {q['id']: q for q in QUESTIONS}
    (HERE / 'results').mkdir(exist_ok=True)
    # 예열 (모델 로딩 시간을 측정에서 뺀다). 1차의 warm_up 은 1차 설명 함수를 쓰므로 직접 부른다
    bench.chat(MODEL, QUESTIONS[0], personas[:1], describe_fn=lambda p: render(p, 'label', None))

    for rep in range(1, args.repeats + 1):
        for qid in args.questions.split(','):
            for fmt in args.formats.split(','):
                out = HERE / 'results' / f'{fmt}__{qid}__r{rep}.json'
                if args.skip_existing and out.exists():
                    continue
                result = run_one(fmt, questions[qid], personas, experiences)
                result['repeat'] = rep
                out.write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding='utf-8')
                print(f"r{rep} {qid:9s} {fmt:10s} {result['wallSeconds']:5.1f}s  프롬프트 {result['promptTokensPerPersona']:6.0f} tok/명  "
                      f"실패 {result['failureCount']:2d}/30 {result['failureKinds']}", flush=True)


if __name__ == '__main__':
    main()
