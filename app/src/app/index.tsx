import { StyleSheet, Text, View } from 'react-native';

// 파일 이름이 곧 주소다. index.tsx 는 "/" 에 해당한다.
export default function Index() {
  return (
    <View style={styles.container}>
      <Text>pick-one</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, alignItems: 'center', justifyContent: 'center' },
});
