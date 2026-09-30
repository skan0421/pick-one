# 페르소나 형태 비교 (spike, 2차)

"변수 뼈대 + 경험 살" 로 만든 페르소나를 얼마나 풍부하게 프롬프트에 넣어야 답이 그 사람답게 갈리는지 잰다.
결과와 추천은 `docs/persona-format-bench.md`, 변수 정의는 `docs/persona-schema.md`, 읽어 볼 샘플은 `docs/persona-samples.md`.
1차 실험(`spikes/llm-bench`)의 실행기 `bench.py` 를 재사용한다.

| 파일 | 역할 |
|---|---|
| `schema.py` | 변수 15개 생성 (①~③, 고정 시드) + 제약 검사 + 점수 → 행동 문장 (④) |
| `experiences.py` | 경험 서술 생성 (⑤, exaone) + 검수 (⑥: 정규식 + LLM 추출 → 코드 판정) |
| `formats.py` | 형태 (a)(b)(c)(d) 렌더링 |
| `questions.py` | 질문 5개, 기대 연결 규칙, 설문 자료(출처) |
| `run.py` | 형태 × 질문 × 회차 실행 (1x8) → `results/` |
| `judge.py` | 이유의 고유성 LLM 심판 (결과 파일에 덧붙임) |
| `analyze.py` | 집계 표 (`--samples` 로 샘플 문서) |
| `ollama.py` | Ollama 구조화 출력 래퍼 |
| `generated/` | 페르소나 30명, 경험 서술(시도 기록 포함) |
| `results/` | 원본 결과 40건 + `summary.md` |

실행 순서는 `docs/persona-format-bench.md` 9장. 표준 라이브러리만 쓴다.
