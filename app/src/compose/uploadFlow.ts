// 사진형 고민을 올리는 흐름 (docs/api.md 4.6 → 4.1).
//
//   1. 사진마다: POST /uploads/images 로 주소 발급 → 받은 주소로 파일을 직접 PUT
//   2. 두 장 모두 올라갔을 때만 POST /questions
//
// 서버 호출 함수를 인자로 받는다 (생성자 주입과 같은 이유). 테스트에서 가짜를 넣어
// "중간에 실패하면 등록하지 않는다"를 서버 없이 검증한다 (src/__tests__/uploadFlow.test.ts)
import { ApiError, errorMessage } from '../api/errors';
import type { CreateQuestionRequest, QuestionResponse } from '../api/questions';
import type { IssueUploadRequest, IssueUploadResponse } from '../api/uploads';
import type { PreparedImage } from './imagePrep';

export type UploadApi = {
  issueUploadUrl: (body: IssueUploadRequest) => Promise<IssueUploadResponse>;
  // uploadUrl 로 내용을 PUT 한다. 실패하면 예외를 던진다
  putFile: (uploadUrl: string, contentType: string, body: Blob) => Promise<void>;
  createQuestion: (body: CreateQuestionRequest) => Promise<QuestionResponse>;
};

// 사진 한 칸. uploadedUrl 이 있으면 이미 올라간 사진이라 다시 올리지 않는다
export type UploadSlot = {
  image: PreparedImage;
  uploadedUrl?: string;
};

export type SlotStatus = 'waiting' | 'uploading' | 'done' | 'failed';

export type SubmitOutcome =
  // 등록 성공
  | { kind: 'created'; question: QuestionResponse }
  // 올리지 못한 사진이 있어 등록하지 않았다. urls 에는 올라간 사진의 주소가 들어 있어 다시 시도할 때 건너뛴다
  | { kind: 'uploadFailed'; urls: (string | undefined)[]; failures: { index: number; message: string }[] }
  // 사진은 모두 올라갔지만 서버가 등록을 거절했다
  | { kind: 'rejected'; urls: (string | undefined)[]; error: unknown };

// 예외를 던지지 않는다. 어떤 실패든 SubmitOutcome 으로 돌려준다
export async function submitImageQuestion(
  api: UploadApi,
  content: string,
  slots: UploadSlot[],
  onStatus: (index: number, status: SlotStatus) => void = () => {},
): Promise<SubmitOutcome> {
  const urls: (string | undefined)[] = slots.map((slot) => slot.uploadedUrl);
  const failures: { index: number; message: string }[] = [];

  // 한 장씩 차례로 올린다. 한 장이 실패해도 나머지는 올려 둔다 (다시 시도할 때 실패한 것만 올리면 된다)
  for (let index = 0; index < slots.length; index += 1) {
    if (urls[index]) {
      onStatus(index, 'done');
      continue;
    }
    onStatus(index, 'uploading');
    try {
      urls[index] = await uploadOne(api, slots[index].image);
      onStatus(index, 'done');
    } catch (error) {
      failures.push({ index, message: errorMessage(error) });
      onStatus(index, 'failed');
    }
  }

  if (failures.length > 0) {
    return { kind: 'uploadFailed', urls, failures };
  }

  try {
    const question = await api.createQuestion({
      questionType: 'IMAGE',
      content: content.trim(),
      options: urls.map((imageUrl) => ({ imageUrl })),
    });
    return { kind: 'created', question };
  } catch (error) {
    if (error instanceof ApiError && error.code === 'IMAGE_URL_INVALID') {
      // 서버가 올린 사진을 찾지 못했다. 올라간 것으로 기억하던 주소를 버려 다음 시도에서 처음부터 다시 올린다
      return { kind: 'rejected', urls: slots.map(() => undefined), error };
    }
    return { kind: 'rejected', urls, error };
  }
}

async function uploadOne(api: UploadApi, image: PreparedImage): Promise<string> {
  // 발급 때 보낸 형식·크기가 서명에 들어간다. 아래 PUT 도 같은 값으로 보내야 한다
  const issued = await api.issueUploadUrl({ contentType: image.contentType, size: image.size });
  await api.putFile(issued.uploadUrl, image.contentType, image.body);
  return issued.imageUrl;
}
