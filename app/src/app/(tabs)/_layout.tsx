// 로그인 후 화면 묶음: 하단 탭 4개.
// Tabs.Screen 의 name 은 같은 폴더의 파일 이름이다 (index.tsx, post.tsx, ...)
import MaterialCommunityIcons from '@expo/vector-icons/MaterialCommunityIcons';
import { Tabs } from 'expo-router/js-tabs';

export default function TabsLayout() {
  return (
    <Tabs>
      <Tabs.Screen
        name="index"
        options={{
          title: '피드',
          tabBarIcon: ({ color, size }) => <MaterialCommunityIcons name="home-outline" color={color} size={size} />,
        }}
      />
      <Tabs.Screen
        name="post"
        options={{
          title: '올리기',
          tabBarIcon: ({ color, size }) => (
            <MaterialCommunityIcons name="plus-circle-outline" color={color} size={size} />
          ),
        }}
      />
      <Tabs.Screen
        name="my-questions"
        options={{
          title: '내 고민',
          tabBarIcon: ({ color, size }) => (
            <MaterialCommunityIcons name="format-list-bulleted" color={color} size={size} />
          ),
        }}
      />
      <Tabs.Screen
        name="my"
        options={{
          title: '마이',
          tabBarIcon: ({ color, size }) => <MaterialCommunityIcons name="account-outline" color={color} size={size} />,
        }}
      />
    </Tabs>
  );
}
