// 올리기 탭. 아직 빈 화면이다
import { StyleSheet, View } from 'react-native';
import { Text } from 'react-native-paper';

export default function PostScreen() {
  return (
    <View style={styles.center}>
      <Text variant="headlineSmall">올리기</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
});
