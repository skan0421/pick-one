// 로그인 전 화면 묶음 (로그인, 가입)
import { Stack } from 'expo-router';

// 이 묶음에 처음 들어왔을 때 보여 줄 화면
export const unstable_settings = {
  anchor: 'login',
};

export default function AuthLayout() {
  return (
    <Stack>
      <Stack.Screen name="login" options={{ title: '로그인' }} />
      <Stack.Screen name="signup" options={{ title: '이메일 가입' }} />
    </Stack>
  );
}
