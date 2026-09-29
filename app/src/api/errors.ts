// 서버 에러 응답(docs/api.md 1.4)을 담는 예외 클래스.
// 백엔드의 BusinessException + ErrorResponse 에 대응한다.
//
//   { "code": "VOTE_ALREADY_VOTED", "message": "이미 투표한 고민입니다." }
//   { "code": "VALIDATION_ERROR", "message": "...", "errors": [ { "field": "email", "reason": "..." } ] }

export type FieldError = {
  field: string;
  reason: string;
};

// 서버가 아니라 앱이 만들어 내는 코드
export const NETWORK_ERROR = 'NETWORK_ERROR'; // 서버에 닿지 못함 (주소 오류, 서버 꺼짐, CORS 차단 등)
export const UNKNOWN_ERROR = 'UNKNOWN_ERROR'; // 응답이 약속된 에러 형식이 아님

export class ApiError extends Error {
  readonly status: number; // HTTP 상태 코드. 서버에 닿지 못했으면 0
  readonly code: string; // 서버 ErrorCode 이름. 화면은 message 가 아니라 이 값으로 분기한다
  readonly fieldErrors: FieldError[]; // VALIDATION_ERROR 일 때만 채워진다

  constructor(status: number, code: string, message: string, fieldErrors: FieldError[] = []) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.fieldErrors = fieldErrors;
  }

  // 특정 입력칸의 오류 문구를 찾는다. 없으면 undefined.
  // 서버는 한 입력칸에 오류를 여러 개 줄 수 있다 (비밀번호가 짧으면서 숫자도 없는 경우 등). 모두 이어서 보여 준다
  fieldError(field: string): string | undefined {
    const reasons = this.fieldErrors.filter((e) => e.field === field).map((e) => e.reason);
    return reasons.length > 0 ? reasons.join(' ') : undefined;
  }
}

// 2xx 가 아닌 응답을 ApiError 로 바꾼다.
// 서버가 죽었거나 프록시가 HTML 을 돌려주는 경우처럼 JSON 이 아닐 수도 있으므로 파싱 실패를 견뎌야 한다.
export async function parseErrorResponse(response: Response): Promise<ApiError> {
  let body: unknown;
  try {
    body = await response.json();
  } catch {
    body = null;
  }

  if (isErrorBody(body)) {
    const fieldErrors = Array.isArray(body.errors) ? body.errors.filter(isFieldError) : [];
    return new ApiError(response.status, body.code, body.message, fieldErrors);
  }
  return new ApiError(response.status, UNKNOWN_ERROR, `서버 오류가 발생했습니다. (HTTP ${response.status})`);
}

// 화면에 보여 줄 문구를 꺼낸다. catch 로 잡은 값은 타입이 unknown 이라 확인이 필요하다
export function errorMessage(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }
  return '알 수 없는 오류가 발생했습니다.';
}

// 아래 두 함수는 "타입 가드"다. JSON 은 런타임에 어떤 모양이든 올 수 있으므로
// 필드를 직접 검사하고, 통과하면 TypeScript 가 그 타입으로 취급하게 한다. (Java 의 instanceof 패턴 매칭과 비슷)
function isErrorBody(value: unknown): value is { code: string; message: string; errors?: unknown } {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const body = value as Record<string, unknown>;
  return typeof body.code === 'string' && typeof body.message === 'string';
}

function isFieldError(value: unknown): value is FieldError {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const item = value as Record<string, unknown>;
  return typeof item.field === 'string' && typeof item.reason === 'string';
}
