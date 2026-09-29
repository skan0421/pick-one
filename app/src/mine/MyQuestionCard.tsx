// 내 고민 한 건. 본문, 유형, 상태, 참여 수, 선택지별 결과 막대, 상단 노출 버튼, 삭제 버튼.
// 결과 막대는 피드와 같은 컴포넌트(ResultBar)를 쓴다
import { Image, StyleSheet, View } from 'react-native';
import { Button, Card, Chip, Text } from 'react-native-paper';

import type { MyQuestion } from '../api/questions';
import { ResultBar } from '../feed/ResultBars';
import { formatRelativeTime, formatRemaining } from '../feed/time';
import { BOOST_COST, BOOST_HOURS } from './boostFlow';
import { isBoosted, statusLabel, typeLabel } from './myQuestions';

type MyQuestionCardProps = {
  question: MyQuestion;
  now: number; // 시각 표시의 기준. 목록 전체가 같은 값을 쓴다
  onDelete: (question: MyQuestion) => void;
  onBoost: (question: MyQuestion) => void;
};

export function MyQuestionCard({ question, now, onDelete, onBoost }: MyQuestionCardProps) {
  const boosted = isBoosted(question, now);
  const options = [...question.options].sort((a, b) => a.sortOrder - b.sortOrder);
  // 목록에는 같은 막대가 여러 개 있으므로 testID 에 고민 id 를 넣어 구분한다
  const testIDPrefix = `mine-result-${question.id}`;

  return (
    <Card style={styles.card} testID="mine-item">
      <Card.Content style={styles.content}>
        <View style={styles.chips}>
          <Chip compact testID="mine-item-type">
            {typeLabel(question.questionType)}
          </Chip>
          <Chip compact testID="mine-item-status">
            {statusLabel(question.status)}
          </Chip>
          {boosted && (
            <Chip compact icon="arrow-up-bold" testID="mine-item-boosted">
              상단 노출 중 · {formatRemaining(question.boostedUntil ?? '', now)}
            </Chip>
          )}
        </View>

        <Text variant="titleMedium" testID="mine-item-content">
          {question.content}
        </Text>
        <Text variant="labelMedium" testID="mine-item-meta">
          {formatRelativeTime(question.createdAt, now)} · 참여 {question.totalVotes}명
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
                  mine={false}
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
                // 작성자는 자기 고민에 투표할 수 없으므로 "내 선택"이 없다
                mine={false}
                testIDPrefix={testIDPrefix}
              />
            ))}
          </View>
        )}
      </Card.Content>
      <Card.Actions>
        {/* 종료되었거나 숨겨진 고민은 서버가 거절하므로(QUESTION_CLOSED) 버튼을 보여 주지 않는다 */}
        {question.status === 'ACTIVE' && (
          <Button icon="arrow-up-bold" onPress={() => onBoost(question)} testID={`mine-boost-${question.id}`}>
            {boosted ? `${BOOST_COST}P로 ${BOOST_HOURS}시간 연장` : `${BOOST_COST}P로 ${BOOST_HOURS}시간 상단 노출`}
          </Button>
        )}
        <Button icon="delete-outline" onPress={() => onDelete(question)} testID={`mine-delete-${question.id}`}>
          삭제
        </Button>
      </Card.Actions>
    </Card>
  );
}

const styles = StyleSheet.create({
  card: { width: '100%', maxWidth: 640, alignSelf: 'center' },
  content: { gap: 10 },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  options: { gap: 8 },
  images: { flexDirection: 'row', gap: 12 },
  imageColumn: { flex: 1, gap: 8 },
  // 주소로 받는 사진은 크기를 정해 주지 않으면 0 x 0 으로 그려진다
  image: { width: '100%', aspectRatio: 1, borderRadius: 12 },
});
