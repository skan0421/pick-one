"""이유가 "그 사람다운지" 를 exaone 에게 묻는 LLM 심판. 자동 판정(analyze.py 의 단어 겹침)과 나란히 놓는다.

심판에게는 형태와 무관하게 항상 (c) 전체 정보를 준다. "이 이유가 이 인물의 정보 중 무엇을 근거로 하는가" 를 뽑게 하고,
근거가 인물 정보에 실제로 있으면 specific=true 로 본다. 결과는 results/*.json 의 answers[*].judge 에 덧붙인다.

실행: python judge.py
"""
import json
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from formats import full_text
from ollama import chat_json
from questions import QUESTIONS
from run import load_personas

HERE = Path(__file__).parent
CONCURRENCY = 8

SYSTEM = '너는 채점자다. 주어진 인물 정보와 답변 이유만 보고 판단한다. JSON 만 한 줄로 쓴다.'


def schema() -> dict:
    return {
        'type': 'object',
        'properties': {
            'specific': {'type': 'boolean'},
            'evidence': {'type': 'string'},
        },
        'required': ['specific', 'evidence'],
    }


def prompt(persona_text: str, question: dict, choice: str, reason: str) -> str:
    return (
        f'인물 정보:\n{persona_text}\n\n'
        f'질문: {question["content"]}\n이 인물의 답: {choice}\n이유: "{reason}"\n\n'
        '이 이유가 위 인물 정보의 어떤 항목(직업, 경제 상황, 가족, 연애 상태, 평소 행동, 살아온 이야기)을 구체적으로 근거로 삼고 있으면 '
        'specific=true 로 하고 evidence 에 그 항목을 그대로 옮겨 적어라. '
        '"편하니까", "좋아서", "안정적이라서" 처럼 누구나 할 수 있는 일반적인 말이거나, 인물 정보에 없는 내용을 지어낸 것이면 specific=false, evidence 는 빈 문자열.'
    )


def judge_file(path: Path, personas: dict[int, dict], experiences: dict[int, list[dict]], questions: dict[str, dict]):
    result = json.loads(path.read_text(encoding='utf-8'))
    question = questions[result['questionId']]
    targets = [a for a in result['answers'] if a['ok'] and 'judge' not in a]
    if not targets:
        return 0

    def one(a: dict) -> dict:
        text = full_text(personas[a['personaId']], experiences.get(a['personaId']))
        res = chat_json(SYSTEM, prompt(text, question, a['choice'], a['reason']), schema(), num_predict=150, temperature=0.1)
        data = res['data'] or {}
        return {'specific': bool(data.get('specific', False)), 'evidence': data.get('evidence', ''), 'error': res['error']}

    with ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
        verdicts = list(pool.map(one, targets))
    for a, v in zip(targets, verdicts):
        a['judge'] = v
    path.write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding='utf-8')
    return len(targets)


def main():
    persona_list, experiences = load_personas()
    personas = {p['id']: p for p in persona_list}
    questions = {q['id']: q for q in QUESTIONS}
    for path in sorted((HERE / 'results').glob('*.json')):
        n = judge_file(path, personas, experiences, questions)
        print(f'{path.name}: {n}건 심판', flush=True)


if __name__ == '__main__':
    main()
