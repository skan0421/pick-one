// 사진을 다루는 기기 기능 모음: 고르기, 읽기, JPEG 변환, 저장소에 올리기.
// 판단은 imagePrep.ts 와 uploadFlow.ts 가 하고, 여기는 시키는 일만 한다 (포트·어댑터의 어댑터)
import { ImageManipulator, SaveFormat } from 'expo-image-manipulator';
import * as ImagePicker from 'expo-image-picker';

import { ApiError, NETWORK_ERROR } from '../api/errors';
import { ImagePrepError, type ImageTools, type PickedImage } from './imagePrep';

// 앨범에서 사진 한 장을 고른다. 웹에서는 파일 선택 창이 뜬다. 고르지 않고 닫으면 null.
// 웹에서는 버튼을 누른 그 순간에 호출해야 한다. 다른 작업을 기다린 뒤에 부르면 브라우저가 창을 막는다
export async function pickImage(): Promise<PickedImage | null> {
  let result: ImagePicker.ImagePickerResult;
  try {
    result = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: ['images'],
      // 1 은 "다시 압축하지 않음"이다. 줄이는 일은 imagePrep.ts 의 규칙대로 직접 한다
      quality: 1,
    });
  } catch {
    // 웹에서는 브라우저가 형식을 모르는 파일을 고르면 선택 창이 예외를 던진다
    // (예: Windows 의 Chrome 에서 .heic 파일). 잡지 않으면 아무 안내 없이 끝난다
    throw new ImagePrepError('UNSUPPORTED');
  }
  if (result.canceled || result.assets.length === 0) {
    return null;
  }
  const asset = result.assets[0];
  return {
    uri: asset.uri,
    width: asset.width,
    height: asset.height,
    mimeType: asset.mimeType,
    fileName: asset.fileName,
  };
}

export const imageTools: ImageTools = {
  // 기기 안의 파일 주소(file://, 웹에서는 blob: 또는 data:)도 fetch 로 읽을 수 있다.
  // Blob 은 파일 내용 덩어리다 (Java 의 byte[] 에 형식 정보가 붙은 것)
  async read(uri) {
    const response = await fetch(uri);
    const body = await response.blob();
    return { size: body.size, body };
  },

  async toJpeg(uri, resize, quality) {
    const context = ImageManipulator.manipulate(uri);
    if (resize) {
      context.resize(resize);
    }
    const rendered = await context.renderAsync();
    const saved = await rendered.saveAsync({ format: SaveFormat.JPEG, compress: quality });
    return { uri: saved.uri };
  },
};

// 발급받은 주소로 파일을 올린다. 우리 서버가 아니라 저장소(S3, 로컬은 MinIO)로 직접 간다.
// 그래서 공통 클라이언트(api/client.ts)를 쓰지 않는다. 로그인 토큰을 붙이면 안 되고, 서명이 인증을 대신한다
export async function putFile(uploadUrl: string, contentType: string, body: Blob): Promise<void> {
  let response: Response;
  try {
    response = await fetch(uploadUrl, {
      method: 'PUT',
      // 발급 때 보낸 형식과 같아야 한다. Content-Length 는 body 의 크기로 자동으로 붙는다
      headers: { 'Content-Type': contentType },
      body,
    });
  } catch {
    throw new ApiError(0, NETWORK_ERROR, '사진 저장소에 연결할 수 없습니다.');
  }
  if (!response.ok) {
    // 403 은 서명과 다르게 올렸거나(형식·크기) 주소가 만료된 경우다
    throw new ApiError(response.status, 'UPLOAD_FAILED', `사진을 올리지 못했습니다. (HTTP ${response.status})`);
  }
}
