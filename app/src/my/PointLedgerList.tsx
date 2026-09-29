// 포인트 내역 (GET /points/ledger, docs/api.md 6.2). 최신순.
// 한 줄에: 무엇으로(투표 참여·상단 노출), 얼마가(+1P·-100P), 그 뒤 잔액, 언제
import { StyleSheet, View } from 'react-native';
import { Card, Text, useTheme } from 'react-native-paper';

import { getLedger, type LedgerItem } from '../api/points';
import { formatDateTime } from '../feed/time';
import { formatAmount, isEarning, txLabel } from './ledger';
import { PagedList } from './PagedList';
import { POINT_LEDGER_KEY } from './queryKeys';

export function PointLedgerList() {
  return (
    <PagedList
      queryKey={POINT_LEDGER_KEY}
      fetchPage={(cursor) => getLedger(cursor)}
      keyOf={(item: LedgerItem) => item.id}
      renderItem={(item) => <LedgerRow item={item} />}
      emptyText="아직 포인트 내역이 없어요. 피드에서 투표하면 1P 씩 쌓여요"
      testID="ledger"
    />
  );
}

function LedgerRow({ item }: { item: LedgerItem }) {
  const theme = useTheme();
  return (
    <Card style={styles.card} testID="ledger-item">
      <Card.Content style={styles.row}>
        <View style={styles.grow}>
          <Text variant="titleMedium" testID="ledger-item-type">
            {txLabel(item.txType)}
          </Text>
          <Text variant="labelMedium" testID="ledger-item-time">
            {formatDateTime(item.createdAt)}
          </Text>
        </View>
        <View style={styles.numbers}>
          <Text
            variant="titleMedium"
            style={{ color: isEarning(item) ? theme.colors.primary : theme.colors.error }}
            testID="ledger-item-amount">
            {formatAmount(item.amount)}
          </Text>
          <Text variant="labelMedium" testID="ledger-item-balance">
            잔액 {item.balanceAfter}P
          </Text>
        </View>
      </Card.Content>
    </Card>
  );
}

const styles = StyleSheet.create({
  card: { width: '100%', maxWidth: 640, alignSelf: 'center' },
  row: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  grow: { flex: 1, gap: 2 },
  numbers: { alignItems: 'flex-end', gap: 2 },
});
