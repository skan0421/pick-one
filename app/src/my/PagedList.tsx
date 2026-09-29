// 커서로 이어 받는 목록의 공통 틀: 받는 중, 실패, 빈 목록, 새로고침, 이어 받기.
// 포인트 내역과 내가 투표한 고민이 함께 쓴다. 한 줄을 어떻게 그릴지만 각 화면이 정한다
// (Java 의 템플릿 메서드 패턴: 틀은 여기, 달라지는 부분은 renderItem)
import { useInfiniteQuery } from '@tanstack/react-query';
import { useState, type ReactElement } from 'react';
import { FlatList, StyleSheet, View } from 'react-native';
import { ActivityIndicator, Button, IconButton, Text } from 'react-native-paper';

import { errorMessage } from '../api/errors';
import { flattenPages, nextCursorOf } from '../api/paging';
import type { CursorPage } from '../api/questions';

type PagedListProps<T> = {
  queryKey: string[];
  fetchPage: (cursor?: string) => Promise<CursorPage<T>>;
  keyOf: (item: T) => number;
  // now 는 시각 표시의 기준이다. 목록 전체가 같은 값을 쓴다
  renderItem: (item: T, now: number) => ReactElement;
  emptyText: string;
  testID: string; // testID 의 앞부분
};

export function PagedList<T>({ queryKey, fetchPage, keyOf, renderItem, emptyText, testID }: PagedListProps<T>) {
  const list = useInfiniteQuery({
    queryKey,
    // pageParam 은 이번에 받을 쪽의 커서다. 첫 쪽은 undefined
    queryFn: ({ pageParam }) => fetchPage(pageParam),
    initialPageParam: undefined as string | undefined,
    // 방금 받은 쪽을 보고 다음 커서를 정한다. undefined 를 돌려주면 "더 없음"이다
    getNextPageParam: (page: CursorPage<T>) => nextCursorOf(page),
  });
  const [now, setNow] = useState(() => Date.now());

  const items = flattenPages(list.data, keyOf);
  // isRefetching 은 다음 쪽을 받을 때도 true 라서, 새로고침 표시에는 그 경우를 뺀다
  const refreshing = list.isRefetching && !list.isFetchingNextPage;

  function refresh() {
    setNow(Date.now());
    void list.refetch();
  }

  function loadMore() {
    // 목록이 짧으면 화면에 그려지자마자 "끝에 닿음"이 불린다. 받을 것이 있고 받는 중이 아닐 때만 요청한다
    if (list.hasNextPage && !list.isFetchingNextPage) {
      void list.fetchNextPage();
    }
  }

  if (list.isPending) {
    return (
      <View style={styles.center}>
        <ActivityIndicator testID={`${testID}-loading`} />
      </View>
    );
  }

  if (list.isError && items.length === 0) {
    return (
      <View style={styles.center}>
        <Text testID={`${testID}-error`}>{errorMessage(list.error)}</Text>
        <Button mode="contained" onPress={refresh} testID={`${testID}-retry`}>
          다시 시도
        </Button>
      </View>
    );
  }

  return (
    <FlatList
      testID={`${testID}-list`}
      data={items}
      keyExtractor={(item) => String(keyOf(item))}
      renderItem={({ item }) => renderItem(item, now)}
      contentContainerStyle={styles.list}
      // 당겨서 새로고침. 웹 브라우저에는 이 동작이 없어서 위쪽에 새로고침 버튼을 함께 둔다
      onRefresh={refresh}
      refreshing={refreshing}
      onEndReached={loadMore}
      onEndReachedThreshold={0.5}
      ListHeaderComponent={
        <View style={styles.header}>
          <Text variant="labelLarge" testID={`${testID}-count`}>
            {items.length}건{list.hasNextPage ? ' 이상' : ''}
          </Text>
          <IconButton
            icon="refresh"
            onPress={refresh}
            disabled={refreshing}
            accessibilityLabel="새로고침"
            testID={`${testID}-refresh`}
          />
        </View>
      }
      ListEmptyComponent={
        <Text style={styles.empty} testID={`${testID}-empty`}>
          {emptyText}
        </Text>
      }
      ListFooterComponent={
        list.isFetchingNextPage ? (
          <ActivityIndicator style={styles.footer} testID={`${testID}-loading-more`} />
        ) : list.isFetchNextPageError ? (
          <Button onPress={loadMore} testID={`${testID}-more-retry`}>
            더 불러오지 못했어요. 다시 시도
          </Button>
        ) : null
      }
    />
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 16, padding: 24 },
  list: { padding: 16, gap: 12 },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    width: '100%',
    maxWidth: 640,
    alignSelf: 'center',
  },
  empty: { textAlign: 'center', paddingVertical: 48 },
  footer: { paddingVertical: 16 },
});
