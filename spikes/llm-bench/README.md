# AI 페르소나 LLM 벤치마크 (spike)

로컬 GPU 의 Ollama 로 "페르소나 100명이 고민에 투표하고 한 줄 이유를 쓰는" 작업을 얼마나 빨리, 얼마나 쓸 만하게 처리하는지 잰다.
결과와 추천 설정은 `docs/ai-persona-bench.md` 에 있다. 본 코드(`src`, `app`)와는 무관한 실험 코드다.

## 파일

| 파일 | 역할 |
|---|---|
| `personas.py` | 페르소나 100명 생성 (고정 시드 난수, LLM 없음) → `personas.json` |
| `questions.json` | 질문 3개 (`scripts/local/sample-data.sql` 의 글형 고민에서) |
| `bench.py` | 실행기. 모델 × 질문 × 방식 하나를 돌려 `results/*.json` 에 남긴다 |
| `analyze.py` | `results/*.json` 을 모아 마크다운 표로 (`python analyze.py > results/summary.md`) |
| `show.py` | 결과 파일 하나를 읽기 좋게 출력 |
| `results/` | 원본 결과 (요청·응답 원문, 토큰 수, 시간, VRAM) |

## 실행

```bash
# Ollama 가 떠 있고 모델이 받아져 있어야 한다. 동시 요청 방식(1x8, 5x4)은 OLLAMA_NUM_PARALLEL 이 동시 수 이상이어야 한다
python personas.py
python bench.py --model exaone3.5:7.8b --all            # 질문 3개 × 방식 1x1, 25x1, 1x8, 5x4
python bench.py --model qwen3:14b --question hobby --method 10x4
python analyze.py > results/summary.md
```

방식은 `{묶음 크기}x{동시 수}` 로 쓴다. 표준 라이브러리만 쓰므로 설치할 것이 없다 (Python 3.11+).

## 측정 방법

- 시간: 요청을 보내기 시작해서 마지막 응답이 올 때까지의 벽시계 시간. 모델 로딩은 실행 전 예열 호출로 빼 둔다
- 생성 tok/s (전체) = 생성 토큰 합 / 벽시계 시간. tok/s (요청당) = Ollama 가 준 `eval_count / eval_duration` 의 합계 비율 (요청 하나가 보는 속도)
- 형식 실패: 재시도 없이 센다. 요청 오류, JSON 파싱 실패, personaId 누락·중복, 선택지 밖의 choice, 빈 이유, 40자 초과
- 출력은 Ollama 의 structured output(JSON 스키마, choice 는 enum) 으로 강제한다. 실제 기능도 같은 방식을 쓸 것이므로 그 조건에서 잰다
- VRAM: `nvidia-smi` 를 1초마다 읽은 최대값. 기준값은 실행 직전의 사용량
- Qwen3 는 `think: false` 로 "생각" 모드를 끈다
