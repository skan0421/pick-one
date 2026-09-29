// 투표 결과 막대. 서버가 준 percent 를 그대로 막대 길이로 쓴다
import { StyleSheet, View } from 'react-native';
import { Text, useTheme } from 'react-native-paper';

type ResultBarProps = {
  sortOrder: number; // testID 에 쓴다
  label?: string; // 선택지 글. 사진형은 사진이 따로 있으므로 없다
  percent: number;
  count: number;
  mine: boolean; // 내가 고른 선택지
};

export function ResultBar({ sortOrder, label, percent, count, mine }: ResultBarProps) {
  const theme = useTheme();
  // 서버 값이 범위를 벗어나도 막대가 틀 밖으로 나가지 않게 한다
  const width = Math.min(100, Math.max(0, percent));

  return (
    <View
      style={[styles.track, { backgroundColor: theme.colors.surfaceVariant }, mine && { borderColor: theme.colors.primary }]}
      testID={`feed-result-${sortOrder}`}>
      {/* 색이 칠해진 부분. 글자 아래에 깔린다 */}
      <View
        style={[
          styles.fill,
          { width: `${width}%`, backgroundColor: mine ? theme.colors.primary : theme.colors.outlineVariant },
          { opacity: mine ? 0.35 : 0.6 },
        ]}
        testID={`feed-result-bar-${sortOrder}`}
      />
      <View style={styles.row}>
        <View style={styles.label}>
          {label !== undefined && (
            <Text variant="titleMedium" numberOfLines={1}>
              {label}
            </Text>
          )}
          {mine && (
            <Text variant="labelMedium" style={{ color: theme.colors.primary }} testID={`feed-result-mine-${sortOrder}`}>
              내 선택
            </Text>
          )}
        </View>
        <View style={styles.numbers}>
          <Text variant="titleMedium" style={mine && styles.bold} testID={`feed-result-percent-${sortOrder}`}>
            {percent}%
          </Text>
          <Text variant="labelSmall">{count}표</Text>
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  track: {
    minHeight: 52,
    borderRadius: 12,
    borderWidth: 2,
    borderColor: 'transparent',
    overflow: 'hidden',
    justifyContent: 'center',
  },
  fill: { position: 'absolute', left: 0, top: 0, bottom: 0 },
  row: { flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 6, gap: 12 },
  label: { flex: 1, gap: 2 },
  numbers: { alignItems: 'flex-end' },
  bold: { fontWeight: '700' },
});
