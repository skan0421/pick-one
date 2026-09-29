// 고민 카드 한 장. 단계(phase)에 따라 선택지 또는 결과를 그린다.
//   고르는 중·요청 중 → 선택지 (글형은 큰 버튼, 사진형은 사진 2장)
//   결과            → 선택지별 막대와 %, "다음" 버튼
// 이 컴포넌트는 서버를 호출하지 않는다. 누른 것을 onChoose / onNext 로 알리기만 한다
import { useState } from 'react';
import { Image, Pressable, StyleSheet, View } from 'react-native';
import { ActivityIndicator, Button, Card, Chip, Text, useTheme } from 'react-native-paper';

import type { FeedItem, OptionResponse } from '../api/questions';
import type { Phase, ResultView } from './feedState';
import { ResultBar } from './ResultBars';
import { canSwipeToChoose } from './swipe';
import { formatRelativeTime } from './time';

type QuestionCardProps = {
  card: FeedItem;
  phase: Phase;
  onChoose: (optionId: number) => void;
  onNext: () => void;
};

export function QuestionCard({ card, phase, onChoose, onNext }: QuestionCardProps) {
  // 카드가 처음 그려진 시각. 다시 그려질 때마다 시계를 읽으면 "3분 전"이 그릴 때마다 달라질 수 있어 한 번만 읽는다
  const [shownAt] = useState(() => Date.now());
  const options = [...card.options].sort((a, b) => a.sortOrder - b.sortOrder);
  const result = phase.kind === 'result' ? phase.result : undefined;

  return (
    <Card style={styles.card} testID="feed-card">
      <Card.Content style={styles.content}>
        <View style={styles.header}>
          <Text variant="labelLarge" testID="feed-card-author">
            {card.author.nickname} · {formatRelativeTime(card.createdAt, shownAt)}
          </Text>
          {card.boosted && (
            <Chip compact icon="arrow-up-bold" testID="feed-card-boosted">
              상단 노출
            </Chip>
          )}
        </View>

        <Text variant="headlineSmall" numberOfLines={6} testID="feed-card-content">
          {card.content}
        </Text>

        {card.questionType === 'IMAGE' ? (
          <ImageOptions options={options} phase={phase} result={result} onChoose={onChoose} />
        ) : (
          <TextOptions options={options} phase={phase} result={result} onChoose={onChoose} />
        )}

        {result ? (
          <View style={styles.footer}>
            <Text variant="labelMedium" testID="feed-result-total">
              총 {result.totalVotes}표
            </Text>
            <Button mode="contained" onPress={onNext} contentStyle={styles.bigButton} testID="feed-next">
              다음
            </Button>
            <Text variant="labelSmall" style={styles.hint}>
              위로 밀어도 다음 고민으로 넘어가요
            </Text>
          </View>
        ) : (
          canSwipeToChoose(card) && (
            <Text variant="labelSmall" style={styles.hint}>
              왼쪽으로 밀면 첫 번째, 오른쪽으로 밀면 두 번째를 골라요
            </Text>
          )
        )}
      </Card.Content>
    </Card>
  );
}

type OptionsProps = {
  options: OptionResponse[];
  phase: Phase;
  result: ResultView | undefined;
  onChoose: (optionId: number) => void;
};

function TextOptions({ options, phase, result, onChoose }: OptionsProps) {
  if (result) {
    return (
      <View style={styles.options} testID="feed-result">
        {options.map((option) => {
          const tally = tallyOf(result, option.id);
          return (
            <ResultBar
              key={option.id}
              sortOrder={option.sortOrder}
              label={option.content ?? ''}
              percent={tally.percent}
              count={tally.count}
              mine={result.myOptionId === option.id}
            />
          );
        })}
      </View>
    );
  }

  const submitting = phase.kind === 'submitting';
  return (
    <View style={styles.options}>
      {options.map((option) => (
        <Button
          key={option.id}
          mode="contained-tonal"
          onPress={() => onChoose(option.id)}
          // 요청 중에는 모든 버튼을 막고, 고른 버튼에만 진행 표시를 돌린다
          disabled={submitting}
          loading={submitting && phase.optionId === option.id}
          contentStyle={styles.bigButton}
          labelStyle={styles.bigLabel}
          testID={`feed-option-${option.sortOrder}`}>
          {option.content}
        </Button>
      ))}
    </View>
  );
}

function ImageOptions({ options, phase, result, onChoose }: OptionsProps) {
  const theme = useTheme();
  const submitting = phase.kind === 'submitting';

  return (
    <View style={styles.images} testID={result ? 'feed-result' : undefined}>
      {options.map((option) => {
        const mine = result?.myOptionId === option.id;
        const tally = result ? tallyOf(result, option.id) : undefined;
        return (
          <View key={option.id} style={styles.imageColumn}>
            <Pressable
              onPress={() => onChoose(option.id)}
              // 결과를 보는 중에는 눌러도 아무 일이 없게 한다
              disabled={submitting || !!result}
              accessibilityRole="button"
              accessibilityLabel={`${option.sortOrder}번 사진`}
              style={[styles.imageFrame, mine && { borderColor: theme.colors.primary }]}
              testID={`feed-option-${option.sortOrder}`}>
              <OptionImage url={option.imageUrl} />
              {submitting && phase.optionId === option.id && (
                <View style={styles.imageOverlay}>
                  <ActivityIndicator />
                </View>
              )}
            </Pressable>
            {tally && (
              <ResultBar sortOrder={option.sortOrder} percent={tally.percent} count={tally.count} mine={mine} />
            )}
          </View>
        );
      })}
    </View>
  );
}

function OptionImage({ url }: { url: string | undefined }) {
  const theme = useTheme();
  const [failed, setFailed] = useState(false);

  // 사진을 받지 못해도(오프라인 등) 자리는 남겨 둔다. 감싼 Pressable 이 있으므로 고르는 것은 여전히 가능하다
  if (!url || failed) {
    return (
      <View style={[styles.image, styles.imageFallback, { backgroundColor: theme.colors.surfaceVariant }]}>
        <Text variant="labelMedium">사진을 불러올 수 없어요</Text>
      </View>
    );
  }
  // 주소로 받아 오는 사진은 크기를 미리 알 수 없다. 크기를 정해 주지 않으면 0 x 0 으로 그려져 보이지 않는다
  return <Image source={{ uri: url }} style={styles.image} resizeMode="cover" onError={() => setFailed(true)} />;
}

// 결과에서 이 선택지의 득표를 찾는다. 서버는 0표인 선택지도 넣어 주지만 없을 때를 대비한다
function tallyOf(result: ResultView, optionId: number) {
  return result.options.find((o) => o.optionId === optionId) ?? { optionId, count: 0, percent: 0 };
}

const styles = StyleSheet.create({
  card: { width: '100%', maxWidth: 640, alignSelf: 'center' },
  content: { gap: 12, paddingVertical: 16 },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8, minHeight: 32 },
  options: { gap: 12 },
  bigButton: { height: 56 },
  bigLabel: { fontSize: 18 },
  images: { flexDirection: 'row', gap: 12 },
  imageColumn: { flex: 1, gap: 8 },
  imageFrame: { borderRadius: 12, borderWidth: 3, borderColor: 'transparent', overflow: 'hidden' },
  image: { width: '100%', aspectRatio: 1 },
  imageFallback: { alignItems: 'center', justifyContent: 'center', padding: 8 },
  imageOverlay: {
    position: 'absolute',
    top: 0,
    right: 0,
    bottom: 0,
    left: 0,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(255, 255, 255, 0.6)',
  },
  footer: { gap: 8 },
  hint: { textAlign: 'center', opacity: 0.6 },
});
