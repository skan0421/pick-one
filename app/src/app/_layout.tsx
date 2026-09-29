// 최상위 레이아웃. src/app 아래 모든 화면을 감싼다.
//
// Expo Router 는 파일 경로가 곧 화면 주소다 (Spring 의 @RequestMapping 을 폴더 구조로 대신한다고 보면 된다).
//   src/app/(auth)/login.tsx   →  /login
//   src/app/verify-phone.tsx   →  /verify-phone
//   src/app/(tabs)/index.tsx   →  /
// 괄호로 감싼 폴더 (auth), (tabs) 는 화면을 묶기만 하고 주소에는 나타나지 않는다.
// _layout.tsx 는 같은 폴더의 화면들을 감싸는 틀이다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Stack } from 'expo-router';
import { StyleSheet, View } from 'react-native';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { ActivityIndicator, Button, PaperProvider, Text } from 'react-native-paper';

import { AuthProvider, useAuth } from '../auth/AuthContext';

// 서버에서 받은 데이터의 캐시. 앱 전체에서 하나만 만든다
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 재시도는 API 클라이언트가 필요한 만큼(재발급 후 1회) 이미 한다
      retry: false,
    },
  },
});

export default function RootLayout() {
  // Provider 는 안쪽 화면 전체에 기능을 제공한다 (Spring 의 빈 등록과 비슷하다)
  return (
    // GestureHandlerRootView 는 스와이프 같은 제스처를 쓰는 화면의 가장 바깥에 있어야 한다.
    // 없으면 피드의 스와이프 영역(GestureDetector)이 오류를 낸다
    <GestureHandlerRootView style={styles.root}>
      <QueryClientProvider client={queryClient}>
        <PaperProvider>
          <AuthProvider>
            <RootNavigator />
          </AuthProvider>
        </PaperProvider>
      </QueryClientProvider>
    </GestureHandlerRootView>
  );
}

function RootNavigator() {
  const { status, startupError, retryStartup } = useAuth();

  if (startupError) {
    return (
      <View style={styles.center}>
        <Text>{startupError}</Text>
        <Button mode="contained" onPress={retryStartup}>
          다시 시도
        </Button>
      </View>
    );
  }

  if (status === 'loading') {
    return (
      <View style={styles.center}>
        <ActivityIndicator />
      </View>
    );
  }

  // Stack.Protected 는 guard 가 true 인 화면만 열어 준다 (Spring Security 의 requestMatchers 와 같은 역할).
  // 상태가 바뀌어 guard 가 false 가 되면 그 화면에서 자동으로 쫓겨나 열려 있는 화면으로 이동한다.
  // 그래서 로그인 성공 후 "탭 화면으로 이동" 같은 코드를 따로 쓰지 않는다
  return (
    <Stack screenOptions={{ headerShown: false }}>
      <Stack.Protected guard={status === 'signedOut'}>
        <Stack.Screen name="(auth)" />
      </Stack.Protected>

      <Stack.Protected guard={status === 'pendingPhone'}>
        <Stack.Screen name="verify-phone" options={{ headerShown: true, title: '휴대폰 인증' }} />
      </Stack.Protected>

      <Stack.Protected guard={status === 'active'}>
        <Stack.Screen name="(tabs)" />
      </Stack.Protected>
    </Stack>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 16, padding: 24 },
});
