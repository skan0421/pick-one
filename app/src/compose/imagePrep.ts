// 고른 사진을 서버가 받는 모양으로 준비한다: 형식 확인 → 필요하면 JPEG 로 변환·축소 → 크기 확인.
//
// 실제로 파일을 읽고 변환하는 일은 기기 기능이라 여기서 직접 하지 않고 ImageTools 로 주입받는다
// (Spring 의 포트·어댑터. 실제 구현은 imageTools.ts, 테스트에서는 가짜를 넣는다).
// 그래서 "변환할지 말지, 얼마나 줄일지"의 판단을 기기 없이 단위 테스트할 수 있다 (src/__tests__/imagePrep.test.ts)

// 서버가 받는 형식과 크기 (docs/api.md 4.6)
export const ALLOWED_TYPES = ['image/jpeg', 'image/png', 'image/webp'];
export const MAX_UPLOAD_BYTES = 5 * 1024 * 1024;

// 긴 변이 이보다 크면 줄인다. 화면에는 수백 픽셀로 보이므로 휴대폰 원본(4000픽셀대)을 그대로 올릴 이유가 없다
export const MAX_LONG_SIDE = 1600;

// 줄이는 단계. 앞 단계로 5MB 를 넘으면 다음 단계로 더 줄인다
export const SHRINK_STEPS = [
  { longSide: 1600, quality: 0.8 },
  { longSide: 1280, quality: 0.7 },
  { longSide: 1024, quality: 0.6 },
  { longSide: 800, quality: 0.5 },
];

// 사진 선택 창이 알려 준 정보
export type PickedImage = {
  uri: string;
  width: number; // 알 수 없으면 0
  height: number;
  mimeType?: string | null;
  fileName?: string | null;
};

// 올릴 준비가 끝난 사진. contentType 과 size 는 발급 요청과 PUT 에 똑같이 쓰인다
export type PreparedImage = {
  uri: string; // 올릴 파일의 주소. 미리보기에도 쓴다
  contentType: string;
  size: number; // 올릴 파일(변환했다면 변환한 뒤)의 바이트 수
  // 올릴 내용 그 자체 (웹). 앱에서는 없다. 앱은 내용을 메모리로 읽지 않고 uri 의 파일을 그대로 올린다
  body?: Blob;
};

export type Resize = { width: number } | { height: number };

export type ImageTools = {
  // 파일의 크기를 돌려준다. 웹은 읽은 내용(body)도 함께 돌려준다
  read: (uri: string) => Promise<{ size: number; body?: Blob }>;
  // JPEG 로 다시 저장한다. resize 가 있으면 그 크기로 줄인다 (비율 유지)
  toJpeg: (uri: string, resize: Resize | undefined, quality: number) => Promise<{ uri: string }>;
};

export type ImagePrepFailure = 'UNREADABLE' | 'UNSUPPORTED' | 'TOO_LARGE';

const FAILURE_MESSAGES: Record<ImagePrepFailure, string> = {
  UNREADABLE: '사진을 읽을 수 없어요. 다른 사진을 골라 주세요.',
  UNSUPPORTED: '이 기기에서 변환할 수 없는 사진 형식이에요. JPEG 나 PNG 사진을 골라 주세요.',
  TOO_LARGE: '사진이 너무 커서 5MB 이하로 줄일 수 없어요. 다른 사진을 골라 주세요.',
};

export class ImagePrepError extends Error {
  readonly reason: ImagePrepFailure;

  constructor(reason: ImagePrepFailure) {
    super(FAILURE_MESSAGES[reason]);
    this.name = 'ImagePrepError';
    this.reason = reason;
  }
}

const EXTENSION_TYPES: Record<string, string> = {
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  png: 'image/png',
  webp: 'image/webp',
  heic: 'image/heic',
  heif: 'image/heif',
  gif: 'image/gif',
};

// 형식 이름을 하나로 맞춘다. 기기가 형식을 알려 주지 않으면 파일 이름의 확장자로 짐작한다.
// 그래도 모르면 undefined 이고, 그 경우 변환 대상이 된다
export function normalizeMimeType(mimeType?: string | null, fileName?: string | null): string | undefined {
  const lower = mimeType?.trim().toLowerCase();
  if (lower === 'image/jpg' || lower === 'image/pjpeg') {
    return 'image/jpeg';
  }
  if (lower) {
    return lower;
  }
  const extension = /\.([a-z0-9]+)$/i.exec(fileName ?? '')?.[1]?.toLowerCase();
  return extension ? EXTENSION_TYPES[extension] : undefined;
}

export type ConversionPlan =
  | { convert: false }
  // format 서버가 받지 않는 형식 (아이폰 HEIC 등) / size 5MB 초과 / dimension 긴 변이 너무 김
  | { convert: true; reason: 'format' | 'size' | 'dimension' };

// 그대로 올려도 되는지, 변환해야 하는지 정한다
export function planConversion(image: {
  mimeType: string | undefined;
  width: number;
  height: number;
  size: number;
}): ConversionPlan {
  if (!image.mimeType || !ALLOWED_TYPES.includes(image.mimeType)) {
    return { convert: true, reason: 'format' };
  }
  if (image.size > MAX_UPLOAD_BYTES) {
    return { convert: true, reason: 'size' };
  }
  if (Math.max(image.width, image.height) > MAX_LONG_SIDE) {
    return { convert: true, reason: 'dimension' };
  }
  return { convert: false };
}

// 긴 변을 longSide 로 맞추는 크기. 이미 그보다 작으면(또는 크기를 모르면) 줄이지 않는다.
// 한쪽만 주면 다른 쪽은 비율에 맞게 정해진다
export function resizeTarget(width: number, height: number, longSide: number): Resize | undefined {
  if (Math.max(width, height) <= longSide) {
    return undefined;
  }
  return width >= height ? { width: longSide } : { height: longSide };
}

export async function prepareImage(picked: PickedImage, tools: ImageTools): Promise<PreparedImage> {
  const mimeType = normalizeMimeType(picked.mimeType, picked.fileName);

  let original: { size: number; body?: Blob };
  try {
    original = await tools.read(picked.uri);
  } catch {
    throw new ImagePrepError('UNREADABLE');
  }
  if (original.size <= 0) {
    throw new ImagePrepError('UNREADABLE');
  }

  // 선택 창이 알려 준 크기(fileSize)가 아니라 실제 파일의 크기를 쓴다.
  // 발급 때 보낸 크기와 올리는 크기가 1바이트라도 다르면 저장소가 거절하기 때문이다
  const plan = planConversion({ mimeType, width: picked.width, height: picked.height, size: original.size });
  if (!plan.convert) {
    // planConversion 을 통과했다면 mimeType 은 허용 형식 중 하나다
    return { uri: picked.uri, contentType: mimeType as string, size: original.size, body: original.body };
  }

  for (const step of SHRINK_STEPS) {
    let converted: { uri: string };
    try {
      converted = await tools.toJpeg(picked.uri, resizeTarget(picked.width, picked.height, step.longSide), step.quality);
    } catch {
      // 기기가 이 형식을 풀지 못한다 (예: HEIC 를 모르는 브라우저)
      throw new ImagePrepError('UNSUPPORTED');
    }
    const result = await tools.read(converted.uri).catch(() => {
      throw new ImagePrepError('UNREADABLE');
    });
    if (result.size > 0 && result.size <= MAX_UPLOAD_BYTES) {
      return { uri: converted.uri, contentType: 'image/jpeg', size: result.size, body: result.body };
    }
  }
  throw new ImagePrepError('TOO_LARGE');
}
