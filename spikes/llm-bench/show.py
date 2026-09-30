"""결과 파일 하나를 사람이 읽기 좋게 찍는다. 실행: python show.py results/xxx.json [--calls]"""
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
r = json.loads(path.read_text(encoding='utf-8'))
print(f"{r['model']} {r['questionId']} {r['method']}  전체 {r['wallSeconds']:.1f}s  첫 결과 {r['firstResultSeconds']:.1f}s  "
      f"{r['tokensPerSecondOverall']:.1f} tok/s 전체 / {r['tokensPerSecondPerStream'] or 0:.1f} tok/s 요청당  "
      f"실패 {r['failureCount']}/{r['personaCount']} {r['failureKinds']}  VRAM {r['vramBaselineMiB']}→{r['vramPeakMiB']} MiB  잘림 {r['truncated']}")
for a in r['answers']:
    reason = a.get('reason') or ''
    print(f"  #{a['personaId']:3d} {'OK ' if a['ok'] else 'NG '}{a.get('failure', ''):16s} {str(a.get('choice')):8s} | {reason} ({len(reason)}자)")
if '--calls' in sys.argv:
    for c in r['calls']:
        print({k: c[k] for k in ('personaIds', 'wallSeconds', 'promptTokens', 'evalTokens', 'evalSeconds', 'doneReason', 'error')})
        print('   ', (c['content'] or '')[:400])
