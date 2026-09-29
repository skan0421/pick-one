// 올리기 화면의 입력 규칙. 화면(React)도 서버 호출도 없는 순수 함수라 단위 테스트한다
// (src/__tests__/composeRules.test.ts).
//
// 같은 규칙을 서버도 검사한다 (docs/api.md 1.8, CreateQuestionRequest 의 Bean Validation).
// 앱에서 먼저 검사하는 것은 서버에 다녀오지 않고 바로 알려 주기 위해서이고, 최종 판단은 서버가 한다.
// 그래서 서버가 돌려준 오류를 같은 모양(ComposeErrors)으로 바꾸는 함수도 여기에 둔다
import { ApiError, errorMessage } from '../api/errors';
import type { CreateQuestionRequest } from '../api/questions';

export const CONTENT_MAX = 300; // 고민 본문 글자 수
export const OPTION_MAX = 20; // 글 선택지 글자 수
export const TEXT_OPTION_MIN = 2;
export const TEXT_OPTION_MAX = 4;
export const IMAGE_COUNT = 2; // 사진형은 정확히 2장

// 입력칸별 오류 문구. 오류가 없는 칸은 undefined
export type ComposeErrors = {
  content?: string; // 본문 아래
  options: (string | undefined)[]; // 선택지(또는 사진) 칸마다 그 아래
  optionList?: string; // 선택지 전체에 대한 오류 (개수 등). 선택지 목록 아래
  form?: string; // 어느 칸에도 속하지 않는 오류 (서버 연결 실패 등). 제출 버튼 위
};

export const NO_ERRORS: ComposeErrors = { options: [] };

export function hasErrors(errors: ComposeErrors): boolean {
  return !!(errors.content || errors.optionList || errors.form || errors.options.some((e) => e !== undefined));
}

// 글자 수는 문자열의 length 로 센다. Java 의 String.length() 와 같은 기준(UTF-16 단위)이라
// 서버의 @Size(max = 300) 과 결과가 같다. 이모지는 양쪽 모두 2로 센다
export function validateContent(content: string): string | undefined {
  const trimmed = content.trim();
  if (trimmed.length === 0) {
    return '고민 내용을 입력해 주세요.';
  }
  if (trimmed.length > CONTENT_MAX) {
    return `고민 내용은 ${CONTENT_MAX}자 이하여야 합니다.`;
  }
  return undefined;
}

export function validateTextDraft(content: string, options: string[]): ComposeErrors {
  const errors: ComposeErrors = {
    content: validateContent(content),
    options: options.map((option) => {
      const trimmed = option.trim();
      if (trimmed.length === 0) {
        return '선택지를 입력해 주세요.';
      }
      if (trimmed.length > OPTION_MAX) {
        return `선택지는 ${OPTION_MAX}자 이하여야 합니다.`;
      }
      return undefined;
    }),
  };
  if (options.length < TEXT_OPTION_MIN || options.length > TEXT_OPTION_MAX) {
    errors.optionList = `선택지는 ${TEXT_OPTION_MIN}~${TEXT_OPTION_MAX}개여야 합니다.`;
  } else if (hasDuplicate(options)) {
    // 서버 규칙에는 없지만, 같은 선택지가 둘이면 투표하는 사람이 고를 수 없다
    errors.optionList = '같은 선택지가 있습니다.';
  }
  return errors;
}

// 사진형 검증. filled 는 칸마다 사진이 준비됐는지다
export function validateImageDraft(content: string, filled: boolean[]): ComposeErrors {
  const errors: ComposeErrors = {
    content: validateContent(content),
    options: filled.map((ready) => (ready ? undefined : '사진을 골라 주세요.')),
  };
  if (filled.length !== IMAGE_COUNT) {
    errors.optionList = `사진은 정확히 ${IMAGE_COUNT}장이어야 합니다.`;
  }
  return errors;
}

function hasDuplicate(options: string[]): boolean {
  const filled = options.map((option) => option.trim()).filter((option) => option.length > 0);
  return new Set(filled).size !== filled.length;
}

// 선택지 추가·삭제. 개수 범위를 벗어나는 요청은 무시하고 원래 배열을 그대로 돌려준다
export function canAddOption(options: string[]): boolean {
  return options.length < TEXT_OPTION_MAX;
}

export function canRemoveOption(options: string[]): boolean {
  return options.length > TEXT_OPTION_MIN;
}

export function addOption(options: string[]): string[] {
  return canAddOption(options) ? [...options, ''] : options;
}

export function removeOption(options: string[], index: number): string[] {
  if (!canRemoveOption(options) || index < 0 || index >= options.length) {
    return options;
  }
  return options.filter((_, i) => i !== index);
}

// 서버에 보낼 요청. 앞뒤 공백은 떼고 보낸다
export function toTextRequest(content: string, options: string[]): CreateQuestionRequest {
  return {
    questionType: 'TEXT',
    content: content.trim(),
    options: options.map((option) => ({ content: option.trim() })),
  };
}

// 서버의 선택지 오류는 field 가 "options[0].content" 같은 모양으로 온다 (docs/api.md 1.4)
const OPTION_FIELD = /^options\[(\d+)\](?:\.(?:content|imageUrl))?$/;

// 선택지 전체에 해당하는 에러 코드
const OPTION_LIST_CODES = ['QUESTION_OPTION_COUNT_INVALID', 'QUESTION_OPTION_TYPE_MISMATCH', 'IMAGE_URL_INVALID'];

// 서버가 돌려준 오류를 입력칸별 문구로 바꾼다.
// optionCount: 지금 화면에 있는 선택지 칸의 수. 서버가 없는 칸을 가리키면 선택지 전체 오류로 돌린다
export function errorsFromServer(error: unknown, optionCount: number): ComposeErrors {
  const errors: ComposeErrors = { options: new Array<string | undefined>(optionCount).fill(undefined) };

  if (!(error instanceof ApiError)) {
    errors.form = errorMessage(error);
    return errors;
  }

  if (error.code === 'VALIDATION_ERROR' && error.fieldErrors.length > 0) {
    for (const { field, reason } of error.fieldErrors) {
      const option = OPTION_FIELD.exec(field);
      if (field === 'content') {
        errors.content = join(errors.content, reason);
      } else if (option && Number(option[1]) < optionCount) {
        const index = Number(option[1]);
        errors.options[index] = join(errors.options[index], reason);
      } else if (option || field === 'options') {
        errors.optionList = join(errors.optionList, reason);
      } else {
        errors.form = join(errors.form, reason);
      }
    }
    return errors;
  }

  if (OPTION_LIST_CODES.includes(error.code)) {
    errors.optionList = error.message;
    return errors;
  }

  errors.form = error.message;
  return errors;
}

// 한 칸에 오류가 여러 개면 이어서 보여 준다
function join(previous: string | undefined, next: string): string {
  return previous ? `${previous} ${next}` : next;
}
