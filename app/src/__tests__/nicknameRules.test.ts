// 닉네임 입력 규칙 테스트. 서버(UpdateMemberRequest)와 같은 규칙인지 본다
import { ApiError, NETWORK_ERROR } from '../api/errors';
import { nicknameErrorFromServer, validateNickname } from '../my/nicknameRules';

describe('validateNickname', () => {
  it.each(['수달', '용감한수달3921', 'abc', 'A1', '가나다라마바사아자차카타파하가나다라마바사아자차카타파하가나'])(
    '"%s" 는 쓸 수 있다',
    (nickname) => {
      expect(validateNickname(nickname)).toBeUndefined();
    },
  );

  it('비어 있으면 입력하라고 안내한다', () => {
    expect(validateNickname('')).toBe('닉네임을 입력해 주세요.');
  });

  it('2자 미만, 30자 초과는 길이를 안내한다', () => {
    expect(validateNickname('가')).toBe('닉네임은 2~30자여야 합니다.');
    expect(validateNickname('a'.repeat(30))).toBeUndefined();
    expect(validateNickname('a'.repeat(31))).toBe('닉네임은 2~30자여야 합니다.');
  });

  it.each([
    ['띄어쓰기', '용감한 수달'],
    ['앞 공백', ' 수달'],
    ['뒤 공백', '수달 '],
    ['밑줄', '샘플_민지'],
    ['기호', '수달!'],
    ['자음만', 'ㅋㅋㅋ'],
    ['이모지', '수달🦦'],
    ['줄바꿈', '수달\n'],
  ])('%s 은(는) 쓸 수 없다', (_name, nickname) => {
    expect(validateNickname(nickname)).toContain('한글, 영문, 숫자만');
  });

  it('지금 닉네임과 같으면 보내지 않는다', () => {
    expect(validateNickname('수달', '수달')).toBe('지금 쓰는 닉네임과 같아요.');
    expect(validateNickname('수달2', '수달')).toBeUndefined();
  });
});

describe('nicknameErrorFromServer', () => {
  it('VALIDATION_ERROR 는 서버가 알려 준 이유를 보여 준다', () => {
    const error = new ApiError(400, 'VALIDATION_ERROR', '입력값이 올바르지 않습니다.', [
      { field: 'nickname', reason: '닉네임은 2~30자여야 합니다.' },
    ]);

    expect(nicknameErrorFromServer(error)).toBe('닉네임은 2~30자여야 합니다.');
  });

  it('MEMBER_NICKNAME_DUPLICATE 는 다른 닉네임을 쓰라고 안내한다', () => {
    const error = new ApiError(409, 'MEMBER_NICKNAME_DUPLICATE', '이미 사용 중인 닉네임입니다.');

    expect(nicknameErrorFromServer(error)).toContain('이미 사용 중인 닉네임');
  });

  it('그 밖의 실패는 받은 문구를 보여 준다', () => {
    expect(nicknameErrorFromServer(new ApiError(0, NETWORK_ERROR, '서버에 연결할 수 없습니다.'))).toBe(
      '서버에 연결할 수 없습니다.',
    );
    expect(nicknameErrorFromServer('이상한 값')).toBe('알 수 없는 오류가 발생했습니다.');
  });
});
