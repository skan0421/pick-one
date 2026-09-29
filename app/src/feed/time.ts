// 서버 시각을 화면에 보여 줄 글로 바꾼다.
//
// 서버는 시각을 "2026-09-29T15:25:56.56359" 처럼 시간대 표시 없이 준다 (Java 의 LocalDateTime, docs/api.md 1.1).
// 이 값은 한국 시각(KST)이다. new Date(문자열) 은 기기의 시간대로 해석하므로
// 한국이 아닌 곳의 기기에서는 시각이 어긋난다. 그래서 직접 분해해서 KST 로 계산한다

const KST_OFFSET_HOURS = 9;
const MINUTE = 60 * 1000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

// 초와 소수 초는 없을 수도 있다. 소수 초는 자릿수가 일정하지 않다
const LOCAL_DATE_TIME = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d+))?)?$/;

// 서버 시각을 epoch 밀리초로 바꾼다 (Java 의 Instant.toEpochMilli). 형식이 다르면 null
export function parseServerTime(value: string): number | null {
  const match = LOCAL_DATE_TIME.exec(value);
  if (!match) {
    return null;
  }
  const [, year, month, day, hour, minute, second = '0', fraction = ''] = match;
  // 소수 초는 앞 3자리(밀리초)만 쓴다. "5" 는 500 밀리초이므로 뒤를 0 으로 채운다
  const millis = Number(fraction.slice(0, 3).padEnd(3, '0'));
  // KST 는 UTC 보다 9시간 빠르다. 시에서 9를 빼면 UTC 시각이 된다 (음수가 되어도 Date.UTC 가 전날로 넘겨 준다)
  return Date.UTC(
    Number(year),
    Number(month) - 1, // 자바스크립트의 월은 0부터 시작한다
    Number(day),
    Number(hour) - KST_OFFSET_HOURS,
    Number(minute),
    Number(second),
    millis,
  );
}

// "방금 전", "5분 전", "3시간 전", "2일 전", 일주일이 넘으면 "9월 20일".
// 현재 시각을 인자로 받는다. 안에서 시계를 읽으면 테스트할 때마다 결과가 달라지기 때문이다 (Java 의 Clock 주입과 같다)
export function formatRelativeTime(value: string, nowMs: number): string {
  const time = parseServerTime(value);
  if (time === null) {
    return '';
  }
  const diff = nowMs - time;
  // 기기 시계가 서버보다 느리면 음수가 나온다. "방금 전" 으로 본다
  if (diff < MINUTE) {
    return '방금 전';
  }
  if (diff < HOUR) {
    return `${Math.floor(diff / MINUTE)}분 전`;
  }
  if (diff < DAY) {
    return `${Math.floor(diff / HOUR)}시간 전`;
  }
  if (diff < 7 * DAY) {
    return `${Math.floor(diff / DAY)}일 전`;
  }
  // 날짜도 KST 기준으로 보여 준다. 9시간을 더한 뒤 UTC 로 읽으면 KST 의 월·일이 나온다
  const kst = new Date(time + KST_OFFSET_HOURS * HOUR);
  return `${kst.getUTCMonth() + 1}월 ${kst.getUTCDate()}일`;
}
