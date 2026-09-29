// 내가 투표한 고민 (GET /members/me/votes, docs/api.md 5.3). 투표한 시각의 최신순.
// 고민 본문, 내가 고른 선택지 강조, 지금의 결과 막대. 막대는 피드와 같은 컴포넌트(ResultBar)를 쓴다
import { Image, StyleSheet, View } from 'react-native';
import { Card, Chip, Text } from 'react-native-paper';

import { getMyVotes, type MyVoteItem } from '../api/votes';
import { ResultBar } from '../feed/ResultBars';
import { formatRelativeTime } from '../feed/time';
import { statusLabel } from '../mine/myQuestions';
import { votedOptions } from './myVotes';
import { PagedList } from './PagedList';
import { MY_VOTES_KEY } from './queryKeys';

export function MyVotesList() {
  return (
    <PagedList
      queryKey={MY_VOTES_KEY}
      fetchPage={(cursor) => getMyVotes(cursor)}
      // 같은 고민에는 한 번만 투표할 수 있으므로 투표 id 로 구분하면 충분하다
      keyOf={(item: MyVoteItem) => item.voteId}
      renderItem={(item, now) => <MyVoteCard item={item} now={now} />}
      emptyText="아직 투표한 고민이 없어요"
      testID="votes"
    />
  );
}

function MyVoteCard({ item, now }: { item: MyVoteItem; now: number }) {
  const { question } = item;
  const options = votedOptions(item);
  // 목록에는 같은 막대가 여러 개 있으므로 testID 에 고민 id 를 넣어 구분한다
  const testIDPrefix = `votes-result-${question.id}`;

  return (
    <Card style={styles.card} testID="votes-item">
      <Card.Content style={styles.content}>
        <View style={styles.header}>
          <Text variant="labelLarge" style={styles.grow} testID="votes-item-meta">
            {question.author.nickname} · {formatRelativeTime(item.votedAt, now)} 투표
          </Text>
          {/* 진행 중인 고민이 대부분이라, 그렇지 않을 때만 상태를 보여 준다 */}
          {question.status !== 'ACTIVE' && (
            <Chip compact testID="votes-item-status">
              {statusLabel(question.status)}
            </Chip>
          )}
        </View>

        <Text variant="titleMedium" testID="votes-item-content">
          {question.content}
        </Text>

        {question.questionType === 'IMAGE' ? (
          <View style={styles.images}>
            {options.map((option) => (
              <View key={option.id} style={styles.imageColumn}>
                {option.imageUrl ? (
                  <Image source={{ uri: option.imageUrl }} style={styles.image} resizeMode="cover" />
                ) : null}
                <ResultBar
                  sortOrder={option.sortOrder}
                  percent={option.percent}
                  count={option.count}
                  mine={option.mine}
                  testIDPrefix={testIDPrefix}
                />
              </View>
            ))}
          </View>
        ) : (
          <View style={styles.options}>
            {options.map((option) => (
              <ResultBar
                key={option.id}
                sortOrder={option.sortOrder}
                label={option.content ?? ''}
                percent={option.percent}
                count={option.count}
                mine={option.mine}
                testIDPrefix={testIDPrefix}
              />
            ))}
          </View>
        )}

        <Text variant="labelMedium" testID="votes-item-total">
          총 {item.result.totalVotes}표
        </Text>
      </Card.Content>
    </Card>
  );
}

const styles = StyleSheet.create({
  card: { width: '100%', maxWidth: 640, alignSelf: 'center' },
  content: { gap: 10 },
  header: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  grow: { flex: 1 },
  options: { gap: 8 },
  images: { flexDirection: 'row', gap: 12 },
  imageColumn: { flex: 1, gap: 8 },
  // 주소로 받는 사진은 크기를 정해 주지 않으면 0 x 0 으로 그려진다
  image: { width: '100%', aspectRatio: 1, borderRadius: 12 },
});
