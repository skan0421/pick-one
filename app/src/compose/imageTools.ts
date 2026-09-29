// 사진을 다루는 기기 기능 모음: 고르기, 읽기, JPEG 변환.
// 읽기와 저장소에 올리기는 앱과 웹이 달라 fileTransfer.ts / fileTransfer.web.ts 에 있다.
// 판단은 imagePrep.ts 와 uploadFlow.ts 가 하고, 여기는 시키는 일만 한다 (포트·어댑터의 어댑터)
import { ImageManipulator, SaveFormat } from 'expo-image-manipulator';
import * as ImagePicker from 'expo-image-picker';

import { readFile } from './fileTransfer';
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
  read: readFile,

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
