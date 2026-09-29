// 서버 시각 해석 테스트. 기기의 시간대와 관계없이 KST 로 해석하는지 본다
import { formatRelativeTime, parseServerTime } from '../feed/time';

const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

describe('parseServerTime', () => {
  it('오프셋 없는 시각을 KST 로 해석한다', () => {
    // KST 09:00 = UTC 00:00
    expect(parseServerTime('2026-09-29T09:00:00')).toBe(Date.UTC(2026, 8, 29, 0, 0, 0));
  });

  it('KST 자정 직후는 UTC 로 전날이다', () => {
    expect(parseServerTime('2026-09-29T00:30:00')).toBe(Date.UTC(2026, 8, 28, 15, 30, 0));
  });

  it.each([
    ['2026-09-29T15:25:56', 0],
    ['2026-09-29T15:25:56.5', 500],
    ['2026-09-29T15:25:56.123', 123],
    ['2026-09-29T15:25:56.56359', 563],
    ['2026-09-29T15:25:56.123456', 123],
  ])('소수 초 자릿수가 달라도 읽는다: %s', (value, millis) => {
    expect(parseServerTime(value)).toBe(Date.UTC(2026, 8, 29, 6, 25, 56, millis));
  });

  it('초가 없는 시각도 읽는다', () => {
    expect(parseServerTime('2026-09-29T15:25')).toBe(Date.UTC(2026, 8, 29, 6, 25, 0));
  });

  it.each(['', '어제', '2026-09-29', '2026-09-29 15:25:56', '2026-09-29T15:25:56Z', '2026-09-29T15:25:56+09:00'])(
    '약속한 형식이 아니면 null: "%s"',
    (value) => {
      expect(parseServerTime(value)).toBeNull();
    },
  );
});

describe('formatRelativeTime', () => {
  const CREATED = '2026-09-20T12:00:00';
  const created = Date.UTC(2026, 8, 20, 3, 0, 0);

  it.each([
    [0, '방금 전'],
    [59 * SECOND, '방금 전'],
    [MINUTE, '1분 전'],
    [59 * MINUTE + 59 * SECOND, '59분 전'],
    [HOUR, '1시간 전'],
    [23 * HOUR + 59 * MINUTE, '23시간 전'],
    [DAY, '1일 전'],
    [6 * DAY + 23 * HOUR, '6일 전'],
    [7 * DAY, '9월 20일'],
    [40 * DAY, '9월 20일'],
  ])('%d 밀리초가 지났으면 "%s"', (elapsed, expected) => {
    expect(formatRelativeTime(CREATED, created + elapsed)).toBe(expected);
  });

  it('기기 시계가 느려 미래 시각으로 보여도 "방금 전"', () => {
    expect(formatRelativeTime(CREATED, created - 5 * MINUTE)).toBe('방금 전');
  });

  it('날짜는 KST 기준으로 보여 준다', () => {
    // KST 10월 1일 00:30 은 UTC 로는 9월 30일이다
    expect(formatRelativeTime('2026-10-01T00:30:00', Date.UTC(2026, 10, 1))).toBe('10월 1일');
  });

  it('읽을 수 없는 시각이면 빈 글자', () => {
    expect(formatRelativeTime('어제', created)).toBe('');
  });
});
