import { Stack } from 'expo-router';

// 최상위 레이아웃. src/app 아래의 모든 화면을 감싸는 틀이다.
// Stack 은 화면을 위로 쌓아 가며 이동하는 기본 내비게이션 방식이다.
export default function RootLayout() {
  return <Stack />;
}
