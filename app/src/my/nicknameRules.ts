// 닉네임 입력 규칙. 화면도 서버 호출도 없는 순수 함수다 (src/__tests__/nicknameRules.test.ts).
//
// 서버의 UpdateMemberRequest 와 같은 규칙이다 (docs/api.md 1.8: 2~30자, 한글/영문/숫자).
// 서버가 최종 판정을 하고, 여기서는 보내기 전에 미리 알려 줄 뿐이다 (Bean Validation 을 화면에서 한 번 더 하는 것)
import { ApiError, errorMessage } from '../api/errors';

export const NICKNAME_MIN = 2;
export const NICKNAME_MAX = 30;

// 완성된 한글 글자(가~힣), 영문, 숫자만. 자음·모음만 있는 글자(ㅋㅋ)와 공백, 기호는 서버가 받지 않는다
const NICKNAME_PATTERN = /^[가-힣A-Za-z0-9]+$/;

// 문제가 없으면 undefined, 있으면 입력칸 아래에 보여 줄 문구
export function validateNickname(nickname: string, current?: string): string | undefined {
  if (nickname.length === 0) {
    return '닉네임을 입력해 주세요.';
  }
  if (nickname.length < NICKNAME_MIN || nickname.length > NICKNAME_MAX) {
    return `닉네임은 ${NICKNAME_MIN}~${NICKNAME_MAX}자여야 합니다.`;
  }
  if (!NICKNAME_PATTERN.test(nickname)) {
    return '닉네임은 한글, 영문, 숫자만 사용할 수 있습니다. (띄어쓰기와 기호는 쓸 수 없어요)';
  }
  if (current !== undefined && nickname === current) {
    return '지금 쓰는 닉네임과 같아요.';
  }
  return undefined;
}

// 서버가 거절했을 때 입력칸 아래에 보여 줄 문구
export function nicknameErrorFromServer(error: unknown): string {
  if (error instanceof ApiError) {
    // VALIDATION_ERROR 는 어느 입력칸이 왜 틀렸는지 함께 온다
    const field = error.fieldError('nickname');
    if (field) {
      return field;
    }
    if (error.code === 'MEMBER_NICKNAME_DUPLICATE') {
      return '이미 사용 중인 닉네임이에요. 다른 닉네임을 입력해 주세요.';
    }
  }
  return errorMessage(error);
}
