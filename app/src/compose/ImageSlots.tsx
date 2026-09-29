// 사진형의 사진 두 칸. 칸을 누르면 사진을 고르고, 올리는 중에는 칸마다 상태를 보여 준다.
// 이 컴포넌트는 그리기만 한다. 고르기·올리기는 ComposeForm 이 한다
import MaterialCommunityIcons from '@expo/vector-icons/MaterialCommunityIcons';
import { Image, Pressable, StyleSheet, View } from 'react-native';
import { ActivityIndicator, HelperText, Text, useTheme } from 'react-native-paper';

import type { PreparedImage } from './imagePrep';
import type { SlotStatus } from './uploadFlow';

// 사진 한 칸의 상태
export type ImageSlot = {
  image?: PreparedImage; // 고른 사진. 아직 없으면 undefined
  preparing: boolean; // 형식 변환·축소 중
  uploadedUrl?: string; // 이미 저장소에 올라간 사진의 주소
  status?: SlotStatus; // 제출 중의 올리기 상태
};

export const EMPTY_SLOT: ImageSlot = { preparing: false };

type ImageSlotsProps = {
  slots: ImageSlot[];
  errors: (string | undefined)[];
  disabled: boolean;
  onPick: (index: number) => void;
};

export function ImageSlots({ slots, errors, disabled, onPick }: ImageSlotsProps) {
  const theme = useTheme();

  return (
    <View style={styles.row}>
      {slots.map((slot, index) => (
        <View key={index} style={styles.column}>
          <Pressable
            onPress={() => onPick(index)}
            disabled={disabled || slot.preparing}
            accessibilityRole="button"
            accessibilityLabel={`사진 ${index + 1} 고르기`}
            style={[
              styles.frame,
              { backgroundColor: theme.colors.surfaceVariant },
              !!errors[index] && { borderColor: theme.colors.error },
            ]}
            testID={`compose-image-${index + 1}`}>
            {slot.image ? (
              <Image source={{ uri: slot.image.uri }} style={styles.image} resizeMode="cover" />
            ) : (
              <View style={styles.placeholder}>
                <MaterialCommunityIcons name="image-plus" size={32} color={theme.colors.onSurfaceVariant} />
                <Text variant="labelLarge">사진 {index + 1} 고르기</Text>
              </View>
            )}
            {(slot.preparing || slot.status === 'uploading') && (
              <View style={styles.overlay}>
                <ActivityIndicator />
                <Text variant="labelMedium">{slot.preparing ? '사진 준비 중' : '올리는 중'}</Text>
              </View>
            )}
          </Pressable>
          <HelperText
            type={errors[index] ? 'error' : 'info'}
            visible={!!errors[index] || !!slot.image}
            testID={`compose-image-status-${index + 1}`}>
            {errors[index] ?? statusText(slot)}
          </HelperText>
        </View>
      ))}
    </View>
  );
}

function statusText(slot: ImageSlot): string {
  if (!slot.image) {
    return '';
  }
  if (slot.status === 'done' || slot.uploadedUrl) {
    return '올리기 완료';
  }
  if (slot.status === 'uploading') {
    return '올리는 중';
  }
  if (slot.status === 'waiting') {
    return '기다리는 중';
  }
  return `${formatSize(slot.image.size)} · 누르면 다시 고를 수 있어요`;
}

function formatSize(bytes: number): string {
  return bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)}MB` : `${Math.max(1, Math.round(bytes / 1024))}KB`;
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', gap: 12 },
  column: { flex: 1 },
  frame: { borderRadius: 12, borderWidth: 2, borderColor: 'transparent', overflow: 'hidden' },
  // 주소로 받는 사진은 크기를 정해 주지 않으면 0 x 0 으로 그려진다
  image: { width: '100%', aspectRatio: 1 },
  placeholder: { width: '100%', aspectRatio: 1, alignItems: 'center', justifyContent: 'center', gap: 8 },
  overlay: {
    position: 'absolute',
    top: 0,
    right: 0,
    bottom: 0,
    left: 0,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    backgroundColor: 'rgba(255, 255, 255, 0.7)',
  },
});
