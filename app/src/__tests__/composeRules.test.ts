// 올리기 입력 규칙 테스트
import { ApiError, NETWORK_ERROR } from '../api/errors';
import {
  addOption,
  canAddOption,
  canRemoveOption,
  errorsFromServer,
  hasErrors,
  NO_ERRORS,
  removeOption,
  toTextRequest,
  validateContent,
  validateImageDraft,
  validateTextDraft,
} from '../compose/composeRules';

describe('본문 검증', () => {
  it('1~300자는 통과한다', () => {
    expect(validateContent('가')).toBeUndefined();
    expect(validateContent('가'.repeat(300))).toBeUndefined();
  });

  it('비어 있거나 공백뿐이면 오류', () => {
    expect(validateContent('')).toBe('고민 내용을 입력해 주세요.');
    expect(validateContent('   \n ')).toBe('고민 내용을 입력해 주세요.');
  });

  it('301자면 오류', () => {
    expect(validateContent('가'.repeat(301))).toBe('고민 내용은 300자 이하여야 합니다.');
  });

  it('앞뒤 공백은 글자 수에 넣지 않는다 (보낼 때 떼기 때문)', () => {
    expect(validateContent(`  ${'가'.repeat(300)}  `)).toBeUndefined();
  });

  it('이모지는 서버(Java String.length)와 같이 2로 센다', () => {
    expect(validateContent('😀'.repeat(150))).toBeUndefined();
    expect(validateContent('😀'.repeat(151))).toBeDefined();
  });
});

describe('글형 검증', () => {
  it('본문과 선택지 2~4개가 맞으면 오류가 없다', () => {
    expect(hasErrors(validateTextDraft('뭐 먹지', ['치킨', '피자']))).toBe(false);
    expect(hasErrors(validateTextDraft('뭐 먹지', ['가', '나', '다', '라']))).toBe(false);
  });

  it('빈 선택지와 긴 선택지는 그 칸에 오류를 단다', () => {
    const errors = validateTextDraft('뭐 먹지', ['치킨', ' ', '가'.repeat(21)]);

    expect(errors.options).toEqual([undefined, '선택지를 입력해 주세요.', '선택지는 20자 이하여야 합니다.']);
    expect(errors.content).toBeUndefined();
    expect(hasErrors(errors)).toBe(true);
  });

  it('선택지 20자는 통과한다', () => {
    expect(hasErrors(validateTextDraft('뭐 먹지', ['가'.repeat(20), '나']))).toBe(false);
  });

  it('선택지가 1개거나 5개면 선택지 전체 오류', () => {
    expect(validateTextDraft('뭐 먹지', ['치킨']).optionList).toBe('선택지는 2~4개여야 합니다.');
    expect(validateTextDraft('뭐 먹지', ['1', '2', '3', '4', '5']).optionList).toBe('선택지는 2~4개여야 합니다.');
  });

  it('같은 선택지가 있으면 오류 (공백 차이는 같은 것으로 본다)', () => {
    expect(validateTextDraft('뭐 먹지', ['치킨', ' 치킨 ']).optionList).toBe('같은 선택지가 있습니다.');
  });

  it('빈 칸끼리는 같은 선택지로 보지 않는다 (칸마다 입력하라는 오류만 단다)', () => {
    const errors = validateTextDraft('뭐 먹지', ['', '']);
    expect(errors.optionList).toBeUndefined();
    expect(errors.options).toEqual(['선택지를 입력해 주세요.', '선택지를 입력해 주세요.']);
  });

  it('본문과 선택지 오류를 함께 돌려준다', () => {
    const errors = validateTextDraft('', ['', '피자']);
    expect(errors.content).toBeDefined();
    expect(errors.options[0]).toBeDefined();
    expect(errors.options[1]).toBeUndefined();
  });

  it('오류 없음 상수는 오류가 없다', () => {
    expect(hasErrors(NO_ERRORS)).toBe(false);
  });
});

describe('선택지 추가와 삭제', () => {
  it('4개까지만 늘어난다', () => {
    let options = ['가', '나'];
    options = addOption(options);
    options = addOption(options);
    expect(options).toEqual(['가', '나', '', '']);
    expect(canAddOption(options)).toBe(false);
    expect(addOption(options)).toBe(options);
  });

  it('2개 아래로는 줄지 않는다', () => {
    const options = ['가', '나'];
    expect(canRemoveOption(options)).toBe(false);
    expect(removeOption(options, 0)).toBe(options);
  });

  it('고른 칸만 지우고 나머지 순서는 유지한다', () => {
    expect(removeOption(['가', '나', '다'], 1)).toEqual(['가', '다']);
  });

  it('없는 칸을 지우라고 하면 그대로 둔다', () => {
    const options = ['가', '나', '다'];
    expect(removeOption(options, 3)).toBe(options);
    expect(removeOption(options, -1)).toBe(options);
  });

  it('원본 배열을 고치지 않는다', () => {
    const options = ['가', '나', '다'];
    removeOption(options, 0);
    addOption(options);
    expect(options).toEqual(['가', '나', '다']);
  });
});

describe('요청 만들기', () => {
  it('앞뒤 공백을 떼고 입력한 순서대로 보낸다', () => {
    expect(toTextRequest('  뭐 먹지 ', [' 치킨', '피자 '])).toEqual({
      questionType: 'TEXT',
      content: '뭐 먹지',
      options: [{ content: '치킨' }, { content: '피자' }],
    });
  });
});

describe('서버 오류를 입력칸에 나누기', () => {
  function validation(fields: [string, string][]): ApiError {
    return new ApiError(
      400,
      'VALIDATION_ERROR',
      '입력값이 올바르지 않습니다.',
      fields.map(([field, reason]) => ({ field, reason })),
    );
  }

  it('content 오류는 본문 아래에', () => {
    const errors = errorsFromServer(validation([['content', '고민 내용은 300자 이하여야 합니다.']]), 2);
    expect(errors.content).toBe('고민 내용은 300자 이하여야 합니다.');
    expect(errors.options).toEqual([undefined, undefined]);
    expect(errors.form).toBeUndefined();
  });

  it('options[1].content 오류는 두 번째 선택지 아래에', () => {
    const errors = errorsFromServer(validation([['options[1].content', '선택지는 20자 이하여야 합니다.']]), 3);
    expect(errors.options).toEqual([undefined, '선택지는 20자 이하여야 합니다.', undefined]);
  });

  it('options[0].imageUrl 오류는 첫 번째 사진 아래에', () => {
    const errors = errorsFromServer(validation([['options[0].imageUrl', '이미지 URL 은 500자 이하여야 합니다.']]), 2);
    expect(errors.options[0]).toBe('이미지 URL 은 500자 이하여야 합니다.');
  });

  it('화면에 없는 칸을 가리키면 선택지 전체 오류로 돌린다', () => {
    const errors = errorsFromServer(validation([['options[5].content', '선택지는 20자 이하여야 합니다.']]), 2);
    expect(errors.options).toEqual([undefined, undefined]);
    expect(errors.optionList).toBe('선택지는 20자 이하여야 합니다.');
  });

  it('options 자체의 오류는 선택지 전체 오류', () => {
    expect(errorsFromServer(validation([['options', '선택지를 입력해 주세요.']]), 2).optionList).toBe(
      '선택지를 입력해 주세요.',
    );
  });

  it('여러 칸의 오류를 한 번에 나눈다. 같은 칸의 오류는 이어 붙인다', () => {
    const errors = errorsFromServer(
      validation([
        ['content', '가'],
        ['content', '나'],
        ['options[0].content', '다'],
        ['questionType', '라'],
      ]),
      2,
    );
    expect(errors.content).toBe('가 나');
    expect(errors.options).toEqual(['다', undefined]);
    expect(errors.form).toBe('라');
  });

  it.each(['QUESTION_OPTION_COUNT_INVALID', 'QUESTION_OPTION_TYPE_MISMATCH', 'IMAGE_URL_INVALID'])(
    '%s 는 선택지 전체 오류',
    (code) => {
      const errors = errorsFromServer(new ApiError(400, code, '서버 문구'), 2);
      expect(errors.optionList).toBe('서버 문구');
      expect(errors.form).toBeUndefined();
    },
  );

  it('필드 정보가 없는 VALIDATION_ERROR 는 버튼 위에', () => {
    expect(errorsFromServer(new ApiError(400, 'VALIDATION_ERROR', '요청 본문을 읽을 수 없습니다.'), 2).form).toBe(
      '요청 본문을 읽을 수 없습니다.',
    );
  });

  it('그 밖의 오류는 버튼 위에', () => {
    expect(errorsFromServer(new ApiError(0, NETWORK_ERROR, '서버에 연결할 수 없습니다.'), 2).form).toBe(
      '서버에 연결할 수 없습니다.',
    );
    expect(errorsFromServer(new TypeError('예상 못 한 오류'), 2).form).toBe('예상 못 한 오류');
  });

  it('어떤 오류든 결과에는 오류가 있다', () => {
    expect(hasErrors(errorsFromServer(new ApiError(500, 'INTERNAL_ERROR', '서버 오류'), 2))).toBe(true);
  });
});

describe('사진형 검증', () => {
  it('본문과 사진 2장이 있으면 오류가 없다', () => {
    expect(hasErrors(validateImageDraft('뭐 입지', [true, true]))).toBe(false);
  });

  it('비어 있는 칸에 오류를 단다', () => {
    const errors = validateImageDraft('뭐 입지', [true, false]);
    expect(errors.options).toEqual([undefined, '사진을 골라 주세요.']);
  });

  it('본문 규칙은 글형과 같다', () => {
    expect(validateImageDraft('', [true, true]).content).toBe('고민 내용을 입력해 주세요.');
    expect(validateImageDraft('가'.repeat(301), [true, true]).content).toBe('고민 내용은 300자 이하여야 합니다.');
  });

  it('칸이 2개가 아니면 선택지 전체 오류', () => {
    expect(validateImageDraft('뭐 입지', [true]).optionList).toBe('사진은 정확히 2장이어야 합니다.');
    expect(validateImageDraft('뭐 입지', [true, true, true]).optionList).toBe('사진은 정확히 2장이어야 합니다.');
  });
});
