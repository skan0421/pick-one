"""AI 페르소나 투표 벤치마크 실행기. Ollama 의 /api/chat 을 직접 부른다 (표준 라이브러리만 사용).

한 번의 실행 = 모델 1개 × 질문 1개 × 방식 1개. 페르소나 100명이 모두 답할 때까지의 시간과 품질을 잰다.

방식(method): "{묶음 크기}x{동시 수}". 예) 1x1 = 1명씩 차례로, 25x1 = 25명 묶음 차례로, 1x8 = 1명씩 8개 동시, 5x4 = 5명 묶음 4개 동시

실행 예:
  python bench.py --model exaone3.5:7.8b --question date --method 5x4
  python bench.py --model qwen3:14b --all           # 질문 3개 × 방식 4개
결과는 results/{model}__{question}__{method}.json 에 남는다 (요청·응답 원문 포함).
"""
import argparse
import json
import re
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from personas import describe, generate

HERE = Path(__file__).parent
OLLAMA = 'http://127.0.0.1:11434'
DEFAULT_METHODS = ['1x1', '25x1', '1x8', '5x4']
REASON_MAX = 40
NUM_CTX = 4096  # 모든 실행에서 같게 둔다. 바꾸면 Ollama 가 모델을 다시 올린다
SEED_DIVERSITY = None  # 시드를 고정하지 않는다. 다양성을 보려는 실험이다

SYSTEM_PROMPT = (
    '너는 주어진 가상 인물들이 되어 익명 투표 앱의 고민에 답한다. '
    '인물마다 그 사람의 나이, 성별, 연애 상태, 직업, 성격, 관심사에 맞게 선택지 하나를 고르고, '
    '그 사람 말투로 이유를 한 문장(40자 이내)으로 쓴다. 이유는 인물마다 다르게, 그 사람 상황이 드러나게 쓴다. '
    'JSON 외의 텍스트는 절대 쓰지 않고, JSON 은 줄바꿈과 들여쓰기 없이 한 줄로 쓴다.'
)


def user_prompt(question: dict, personas: list[dict], describe_fn=describe) -> str:
    """describe_fn: 인물 한 명을 프롬프트 문장으로 바꾸는 함수. 2차 실험(spikes/persona-format)이 다른 형태를 끼워 넣는다"""
    options = ' / '.join(f'"{o}"' for o in question['options'])
    lines = [
        f"고민: {question['content']}",
        f'선택지: {options}',
        '',
        f'답할 인물 {len(personas)}명:',
        *[describe_fn(p) for p in personas],
        '',
        f'{len(personas)}명 모두 하나씩, 아래 형식의 JSON 으로만 답한다. personaId 는 위 목록의 # 번호를 그대로 쓴다. '
        'choice 는 선택지 문구를 그대로 쓴다. reason 은 40자 이내 한국어 한 문장.',
        example(personas),
    ]
    return '\n'.join(lines)


def example(personas: list[dict]) -> str:
    """형식 예시. 실제 인물 번호를 넣는다. 예시에 1 을 쓰면 1명씩 물을 때 그 번호를 베낀다"""
    items = ','.join(f'{{"personaId":{p["id"]},"choice":"선택지","reason":"이유"}}' for p in personas[:2])
    tail = ',...' if len(personas) > 2 else ''
    return f'{{"answers":[{items}{tail}]}}'


def response_schema(question: dict, count: int) -> dict:
    """Ollama structured output. 선택지는 enum 으로, 답의 개수는 minItems/maxItems 로 묶어 형식 밖의 값을 막는다.
    (모델이 중간에 멈추는 것을 문법 수준에서 막는다. personaId 가 틀리거나 겹치는 것은 막지 못한다)"""
    return {
        'type': 'object',
        'properties': {
            'answers': {
                'type': 'array',
                'minItems': count,
                'maxItems': count,
                'items': {
                    'type': 'object',
                    'properties': {
                        'personaId': {'type': 'integer'},
                        'choice': {'type': 'string', 'enum': question['options']},
                        'reason': {'type': 'string'},
                    },
                    'required': ['personaId', 'choice', 'reason'],
                },
            },
        },
        'required': ['answers'],
    }


def chat(model: str, question: dict, personas: list[dict], timeout: float = 600, describe_fn=describe) -> dict:
    """요청 1건. 응답 원문과 Ollama 가 준 토큰 수·시간을 함께 돌려준다. HTTP 오류는 예외 대신 error 로 담는다"""
    body = {
        'model': model,
        'stream': False,
        'think': False,  # Qwen3 등 "생각" 모드 끄기. 켜면 답 앞에 수백 토큰이 붙는다
        'format': response_schema(question, len(personas)),
        'keep_alive': '15m',
        'options': {
            'num_ctx': NUM_CTX,
            'num_predict': 80 * len(personas) + 60,  # 인물당 약 40자 ≈ 30~60 토큰 + JSON 뼈대
            'temperature': 0.8,
        },
        'messages': [
            {'role': 'system', 'content': SYSTEM_PROMPT},
            {'role': 'user', 'content': user_prompt(question, personas, describe_fn)},
        ],
    }
    started = time.perf_counter()
    req = urllib.request.Request(f'{OLLAMA}/api/chat', data=json.dumps(body).encode('utf-8'),
                                 headers={'Content-Type': 'application/json'}, method='POST')
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            payload = json.loads(res.read().decode('utf-8'))
        error = None
    except urllib.error.HTTPError as e:
        payload, error = None, f'HTTP {e.code}: {e.read().decode("utf-8", "replace")[:300]}'
    except Exception as e:  # noqa: BLE001 - 타임아웃 등 무엇이든 실패로 기록한다
        payload, error = None, f'{type(e).__name__}: {e}'
    ended = time.perf_counter()
    return {
        'personaIds': [p['id'] for p in personas],
        'startedAt': started,
        'endedAt': ended,
        'wallSeconds': ended - started,
        'error': error,
        'content': payload['message']['content'] if payload else None,
        'promptTokens': payload.get('prompt_eval_count') if payload else None,
        'evalTokens': payload.get('eval_count') if payload else None,
        'evalSeconds': (payload.get('eval_duration') or 0) / 1e9 if payload else None,
        'promptSeconds': (payload.get('prompt_eval_duration') or 0) / 1e9 if payload else None,
        'doneReason': payload.get('done_reason') if payload else None,
    }


def parse_answers(call: dict, question: dict) -> list[dict]:
    """요청 1건의 응답을 인물별 결과로 푼다. 재시도 없이, 깨진 것은 깨진 대로 기록한다"""
    expected = set(call['personaIds'])
    results = {pid: {'personaId': pid, 'ok': False, 'failure': 'missing'} for pid in expected}
    if call['error']:
        for r in results.values():
            r['failure'] = 'request_error'
        return list(results.values())
    try:
        data = json.loads(call['content'])
        answers = data['answers']
        assert isinstance(answers, list)
    except Exception:  # noqa: BLE001
        for r in results.values():
            r['failure'] = 'invalid_json'
        return list(results.values())

    seen = set()
    for a in answers:
        pid = a.get('personaId') if isinstance(a, dict) else None
        if pid not in expected:
            continue  # 요청하지 않은 인물의 답은 버린다 (아래에서 missing 으로 남는다)
        if pid in seen:
            results[pid] = {'personaId': pid, 'ok': False, 'failure': 'duplicate'}
            continue
        seen.add(pid)
        choice = a.get('choice')
        reason = a.get('reason')
        if choice not in question['options']:
            results[pid] = {'personaId': pid, 'ok': False, 'failure': 'bad_choice', 'choice': choice, 'reason': reason}
        elif not isinstance(reason, str) or not reason.strip():
            results[pid] = {'personaId': pid, 'ok': False, 'failure': 'empty_reason', 'choice': choice, 'reason': reason}
        elif len(reason.strip()) > REASON_MAX:
            results[pid] = {'personaId': pid, 'ok': False, 'failure': 'reason_too_long', 'choice': choice, 'reason': reason.strip()}
        else:
            results[pid] = {'personaId': pid, 'ok': True, 'choice': choice, 'reason': reason.strip()}
    return list(results.values())


class VramMonitor:
    """nvidia-smi 를 1초마다 불러 최대 사용량을 기록한다"""

    def __init__(self):
        self.samples: list[int] = []
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)

    @staticmethod
    def read() -> int | None:
        try:
            out = subprocess.run(['nvidia-smi', '--query-gpu=memory.used', '--format=csv,noheader,nounits'],
                                 capture_output=True, text=True, timeout=5).stdout.strip()
            return int(out.splitlines()[0])
        except Exception:  # noqa: BLE001
            return None

    def _run(self):
        while not self._stop.is_set():
            value = self.read()
            if value is not None:
                self.samples.append(value)
            self._stop.wait(1.0)

    def __enter__(self):
        self._thread.start()
        return self

    def __exit__(self, *_):
        self._stop.set()
        self._thread.join(timeout=3)

    @property
    def peak(self) -> int | None:
        return max(self.samples) if self.samples else None


def warm_up(model: str, question: dict, personas: list[dict]) -> float:
    """모델을 올려 두고 첫 요청의 로딩 시간을 측정에서 뺀다"""
    started = time.perf_counter()
    chat(model, question, personas[:1])
    return time.perf_counter() - started


def run(model: str, question: dict, method: str, personas: list[dict], baseline_vram: int | None) -> dict:
    batch_size, concurrency = (int(x) for x in method.split('x'))
    batches = [personas[i:i + batch_size] for i in range(0, len(personas), batch_size)]

    with VramMonitor() as vram:
        started = time.perf_counter()
        with ThreadPoolExecutor(max_workers=concurrency) as pool:
            calls = list(pool.map(lambda b: chat(model, question, b), batches))
        wall = time.perf_counter() - started

    per_persona = []
    for call in calls:
        per_persona.extend(parse_answers(call, question))
    per_persona.sort(key=lambda r: r['personaId'])

    ok_calls = [c for c in calls if not c['error']]
    eval_tokens = sum(c['evalTokens'] or 0 for c in ok_calls)
    prompt_tokens = sum(c['promptTokens'] or 0 for c in ok_calls)
    first_result = min((c['endedAt'] for c in ok_calls), default=None)
    failures = [r for r in per_persona if not r['ok']]
    failure_kinds: dict[str, int] = {}
    for r in failures:
        failure_kinds[r['failure']] = failure_kinds.get(r['failure'], 0) + 1

    return {
        'model': model,
        'questionId': question['id'],
        'method': method,
        'batchSize': batch_size,
        'concurrency': concurrency,
        'personaCount': len(personas),
        'requestCount': len(batches),
        'wallSeconds': wall,
        'firstResultSeconds': (first_result - started) if first_result else None,
        'evalTokens': eval_tokens,
        'promptTokens': prompt_tokens,
        'tokensPerSecondOverall': eval_tokens / wall if wall else None,  # 전체 처리량 (생성 토큰 / 벽시계)
        'tokensPerSecondPerStream': (
            sum(c['evalTokens'] for c in ok_calls) / sum(c['evalSeconds'] for c in ok_calls)
            if ok_calls and sum(c['evalSeconds'] for c in ok_calls) > 0 else None),  # 요청 하나가 보는 생성 속도
        'failureCount': len(failures),
        'failureRate': len(failures) / len(personas),
        'failureKinds': failure_kinds,
        'requestErrors': sum(1 for c in calls if c['error']),
        'truncated': sum(1 for c in ok_calls if c['doneReason'] == 'length'),
        'vramBaselineMiB': baseline_vram,
        'vramPeakMiB': vram.peak,
        'numCtx': NUM_CTX,
        'answers': per_persona,
        'calls': calls,
    }


def load_questions() -> dict[str, dict]:
    questions = json.loads((HERE / 'questions.json').read_text(encoding='utf-8'))
    return {q['id']: q for q in questions}


def result_path(model: str, question_id: str, method: str) -> Path:
    safe_model = re.sub(r'[^A-Za-z0-9._-]+', '_', model)
    return HERE / 'results' / f'{safe_model}__{question_id}__{method}.json'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model', required=True)
    parser.add_argument('--question', help='questions.json 의 id. 생략하면 --all')
    parser.add_argument('--method', help='예 5x4. 생략하면 --all')
    parser.add_argument('--methods', default=','.join(DEFAULT_METHODS), help='--all 일 때 돌릴 방식 목록')
    parser.add_argument('--all', action='store_true')
    parser.add_argument('--skip-existing', action='store_true', help='결과 파일이 있으면 건너뛴다')
    parser.add_argument('--limit', type=int, help='페르소나 수를 줄여 파이프라인만 시험할 때 (결과 파일 이름에 붙는다)')
    parser.add_argument('--tag', default='', help='같은 조건을 다시 돌릴 때 구분 표시 (예 r2). 결과 파일 이름과 표에 붙는다')
    args = parser.parse_args()

    questions = load_questions()
    personas = generate()
    if args.limit:
        personas = personas[:args.limit]
    question_ids = [args.question] if args.question else list(questions)
    methods = [args.method] if args.method else args.methods.split(',')

    baseline = VramMonitor.read()
    load_seconds = warm_up(args.model, questions[question_ids[0]], personas)
    print(f'[{args.model}] 준비 완료 (첫 호출 {load_seconds:.1f}s, VRAM {baseline} → {VramMonitor.read()} MiB)', flush=True)

    for qid in question_ids:
        for method in methods:
            suffix = (f'_n{args.limit}' if args.limit else '') + (f'_{args.tag}' if args.tag else '')
            out = result_path(args.model, qid, method + suffix)
            if args.skip_existing and out.exists():
                print(f'  건너뜀 {out.name}')
                continue
            result = run(args.model, questions[qid], method, personas, baseline)
            result['tag'] = args.tag
            out.parent.mkdir(exist_ok=True)
            out.write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding='utf-8')
            print(f"  {qid:5s} {method:5s} 전체 {result['wallSeconds']:6.1f}s  첫 결과 {result['firstResultSeconds'] or 0:5.1f}s  "
                  f"{result['tokensPerSecondOverall'] or 0:6.1f} tok/s  실패 {result['failureCount']:3d}/{len(personas)} {result['failureKinds']}  "
                  f"VRAM 최대 {result['vramPeakMiB']} MiB", flush=True)


if __name__ == '__main__':
    sys.exit(main())
