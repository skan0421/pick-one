// 피드 탭. 서버 연결 확인용으로 GET /questions/feed 의 첫 쪽을 단순 목록으로 보여 준다
import { useQuery } from '@tanstack/react-query';
import { FlatList, StyleSheet, View } from 'react-native';
import { ActivityIndicator, Button, Card, Text } from 'react-native-paper';

import { errorMessage } from '../../api/errors';
import { getFeed, type FeedItem } from '../../api/questions';

export default function FeedScreen() {
  // useQuery 는 서버 데이터를 가져와 캐시에 두고, 로딩·오류·결과 상태를 알려 준다.
  // queryKey 는 캐시의 키다 (Spring @Cacheable 의 key 와 같다)
  const feed = useQuery({
    queryKey: ['feed'],
    queryFn: () => getFeed(),
  });

  if (feed.isPending) {
    return (
      <View style={styles.center}>
        <ActivityIndicator />
      </View>
    );
  }

  if (feed.isError) {
    return (
      <View style={styles.center}>
        <Text testID="feed-error">{errorMessage(feed.error)}</Text>
        <Button mode="contained" onPress={() => feed.refetch()}>
          다시 시도
        </Button>
      </View>
    );
  }

  return (
    <FlatList
      testID="feed-list"
      data={feed.data.items}
      keyExtractor={(item) => String(item.id)}
      renderItem={({ item }) => <FeedRow item={item} />}
      contentContainerStyle={styles.list}
      onRefresh={() => feed.refetch()}
      refreshing={feed.isRefetching}
      ListEmptyComponent={<Text testID="feed-empty">아직 올라온 고민이 없습니다.</Text>}
    />
  );
}

function FeedRow({ item }: { item: FeedItem }) {
  return (
    <Card style={styles.card} testID="feed-item">
      <Card.Title title={item.content} subtitle={`${item.author.nickname} · ${item.createdAt}`} />
      <Card.Content>
        {item.options.map((option) => (
          // 사진 선택지는 아직 그리지 않고 주소만 보여 준다
          <Text key={option.id}>
            {option.sortOrder}. {option.content ?? option.imageUrl}
          </Text>
        ))}
        {item.boosted && <Text variant="labelSmall">상단 노출 중</Text>}
      </Card.Content>
    </Card>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 16, padding: 24 },
  list: { padding: 16, gap: 12 },
  card: { width: '100%', maxWidth: 640, alignSelf: 'center' },
});
