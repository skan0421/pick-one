// 상단 노출 확인 창. 차감 금액, 현재 잔액, 차감 후 잔액을 보여 주고 확인을 받는다.
// 이 컴포넌트는 서버를 호출하지 않는다. 누른 것을 onConfirm 으로 알리기만 한다
import { StyleSheet, View } from 'react-native';
import { Button, Dialog, Portal, Text, useTheme } from 'react-native-paper';

import type { MyQuestion } from '../api/questions';
import { formatRemaining } from '../feed/time';
import { BOOST_HOURS, type BoostPreview } from './boostFlow';

type BoostDialogProps = {
  question: MyQuestion | null; // null 이면 창이 닫혀 있다
  preview: BoostPreview | null;
  now: number;
  busy: boolean; // 요청을 보내는 중
  error: string | undefined; // 앞선 시도가 실패했을 때의 안내
  onConfirm: () => void;
  onDismiss: () => void;
};

export function BoostDialog({ question, preview, now, busy, error, onConfirm, onDismiss }: BoostDialogProps) {
  const theme = useTheme();
  const remaining = question?.boostedUntil ? formatRemaining(question.boostedUntil, now) : '';

  return (
    // Portal 은 안의 내용을 화면 맨 위 층에 그린다. 확인 창이 목록에 가려지지 않는다
    <Portal>
      <Dialog visible={!!question} onDismiss={() => !busy && onDismiss()} testID="boost-dialog">
        <Dialog.Title>{preview?.extending ? '상단 노출을 연장할까요?' : '상단에 노출할까요?'}</Dialog.Title>
        <Dialog.Content style={styles.content}>
          <Text variant="bodyMedium" numberOfLines={2}>
            {question?.content}
          </Text>

          <Text variant="bodySmall" testID="boost-guide">
            {preview?.extending
              ? `지금 노출 중이에요 (${remaining}). 끝나는 시각에 ${BOOST_HOURS}시간이 이어 붙어요.`
              : `${BOOST_HOURS}시간 동안 다른 사람의 피드 위쪽에 보여요.`}
          </Text>

          {preview && (
            <View style={styles.table}>
              <Row label="차감" value={`-${preview.cost}P`} testID="boost-cost" />
              <Row
                label="현재 잔액"
                value={preview.balance === undefined ? '-' : `${preview.balance}P`}
                testID="boost-balance"
              />
              <Row
                label="차감 후 잔액"
                // 모자랄 때 음수를 보여 주면 "빠진다"로 읽힐 수 있어 표시하지 않는다
                value={preview.balanceAfter === undefined || !preview.affordable ? '-' : `${preview.balanceAfter}P`}
                testID="boost-balance-after"
              />
            </View>
          )}

          {preview && !preview.affordable && (
            <Text variant="bodySmall" style={{ color: theme.colors.error }} testID="boost-insufficient">
              포인트가 {preview.cost - (preview.balance ?? 0)}P 모자라요. 피드에서 투표하면 1P 씩 모을 수 있어요.
            </Text>
          )}
          {error !== undefined && (
            <Text variant="bodySmall" style={{ color: theme.colors.error }} testID="boost-error">
              {error}
            </Text>
          )}
        </Dialog.Content>
        <Dialog.Actions>
          <Button onPress={onDismiss} disabled={busy} testID="boost-cancel">
            취소
          </Button>
          <Button
            onPress={onConfirm}
            loading={busy}
            disabled={busy || !preview?.affordable}
            testID="boost-confirm">
            {error !== undefined ? '다시 시도' : `${preview?.cost ?? ''}P 사용`}
          </Button>
        </Dialog.Actions>
      </Dialog>
    </Portal>
  );
}

function Row({ label, value, testID }: { label: string; value: string; testID: string }) {
  return (
    <View style={styles.row}>
      <Text variant="bodyMedium">{label}</Text>
      <Text variant="titleMedium" testID={testID}>
        {value}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  content: { gap: 12 },
  table: { gap: 6 },
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
});
