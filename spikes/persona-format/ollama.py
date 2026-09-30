"""Ollama /api/chat 얇은 래퍼 (구조화 출력, think 끔). experiences.py 와 judge.py 가 쓴다"""
import json
import time
import urllib.request

OLLAMA = 'http://127.0.0.1:11434'
MODEL = 'exaone3.5:7.8b'


def chat_json(system: str, user: str, schema: dict, num_predict: int = 600, temperature: float = 0.8,
              model: str = MODEL, num_ctx: int = 4096, timeout: float = 600) -> dict:
    """구조화 출력으로 JSON 을 받는다. 돌려주는 값: {'data': 파싱된 JSON 또는 None, 'error', 'promptTokens', 'evalTokens', 'wallSeconds', 'raw'}"""
    body = {
        'model': model, 'stream': False, 'think': False, 'format': schema, 'keep_alive': '15m',
        'options': {'num_ctx': num_ctx, 'num_predict': num_predict, 'temperature': temperature},
        'messages': [{'role': 'system', 'content': system}, {'role': 'user', 'content': user}],
    }
    started = time.perf_counter()
    req = urllib.request.Request(f'{OLLAMA}/api/chat', data=json.dumps(body).encode('utf-8'),
                                 headers={'Content-Type': 'application/json'}, method='POST')
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            payload = json.loads(res.read().decode('utf-8'))
        raw = payload['message']['content']
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            data = None
        return {'data': data, 'error': None if data is not None else 'invalid_json', 'raw': raw,
                'promptTokens': payload.get('prompt_eval_count'), 'evalTokens': payload.get('eval_count'),
                'wallSeconds': time.perf_counter() - started}
    except Exception as e:  # noqa: BLE001
        return {'data': None, 'error': f'{type(e).__name__}: {e}', 'raw': None,
                'promptTokens': None, 'evalTokens': None, 'wallSeconds': time.perf_counter() - started}
