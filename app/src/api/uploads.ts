// 사진 업로드 URL 발급 API (docs/api.md 4.6). ACTIVE 회원만 호출할 수 있다.
//
// 서버는 사진 파일을 받지 않는다. "이 주소로 직접 올려라"는 주소(presigned URL)만 만들어 준다.
// 그 주소에는 서명이 붙어 있어, 발급 때 말한 형식·크기 그대로 올릴 때만 저장소가 받아 준다
import { request } from './client';

export type IssueUploadRequest = {
  contentType: string; // image/jpeg, image/png, image/webp
  size: number; // 바이트. 실제로 올릴 파일의 크기와 정확히 같아야 한다
};

export type IssueUploadResponse = {
  uploadUrl: string; // 여기로 PUT 한다. 5분 동안만 쓸 수 있다
  imageUrl: string; // 올린 뒤 사진을 볼 수 있는 주소. 고민 등록에 이 값을 넣는다
  expiresAt: string;
};

export function issueUploadUrl(body: IssueUploadRequest): Promise<IssueUploadResponse> {
  return request<IssueUploadResponse>('/uploads/images', { method: 'POST', body });
}
