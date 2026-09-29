// 닉네임 변경 창 (PATCH /members/me, docs/api.md 2.8).
// 입력 규칙은 nicknameRules.ts 에 있고, 여기는 입력을 받아 보내기만 한다
import { useRef, useState } from 'react';
import { StyleSheet } from 'react-native';
import { Button, Dialog, HelperText, Portal, TextInput } from 'react-native-paper';

import type { MemberSummary } from '../api/auth';
import { updateNickname } from '../api/members';
import { NICKNAME_MAX, nicknameErrorFromServer, validateNickname } from './nicknameRules';

type NicknameDialogProps = {
  current: string;
  onDismiss: () => void;
  onChanged: (member: MemberSummary) => void;
};

// 창이 열릴 때마다 새로 만들어진다 (MyHome 이 열려 있을 때만 그린다). 그래서 입력칸은 항상 지금 닉네임으로 시작한다
export function NicknameDialog({ current, onDismiss, onChanged }: NicknameDialogProps) {
  const [nickname, setNickname] = useState(current);
  const [error, setError] = useState<string | undefined>(undefined);
  const [saving, setSaving] = useState(false);
  // 저장 잠금. saving 은 화면이 다시 그려진 뒤에야 버튼을 막는다 (docs/troubleshooting.md 20)
  const locked = useRef(false);

  async function save() {
    if (locked.current) {
      return;
    }
    const invalid = validateNickname(nickname, current);
    if (invalid) {
      setError(invalid);
      return;
    }
    locked.current = true;
    setSaving(true);
    setError(undefined);
    try {
      const me = await updateNickname(nickname);
      onChanged({ id: me.id, nickname: me.nickname, signupStatus: me.signupStatus });
    } catch (failure) {
      setError(nicknameErrorFromServer(failure));
      setSaving(false);
      locked.current = false;
    }
    // 성공하면 onChanged 가 창을 닫으므로 잠금을 풀 필요가 없다
  }

  return (
    <Portal>
      <Dialog visible onDismiss={() => !saving && onDismiss()} testID="my-nickname-dialog">
        <Dialog.Title>닉네임 변경</Dialog.Title>
        <Dialog.Content>
          <TextInput
            label="닉네임"
            value={nickname}
            onChangeText={(text) => {
              setNickname(text);
              setError(undefined);
            }}
            maxLength={NICKNAME_MAX}
            autoCapitalize="none"
            autoCorrect={false}
            disabled={saving}
            error={!!error}
            onSubmitEditing={save}
            testID="my-nickname-input"
          />
          <HelperText type={error ? 'error' : 'info'} visible style={styles.helper} testID="my-nickname-error">
            {error ?? '2~30자, 한글·영문·숫자'}
          </HelperText>
        </Dialog.Content>
        <Dialog.Actions>
          <Button onPress={onDismiss} disabled={saving} testID="my-nickname-cancel">
            취소
          </Button>
          <Button onPress={save} loading={saving} disabled={saving} testID="my-nickname-save">
            변경
          </Button>
        </Dialog.Actions>
      </Dialog>
    </Portal>
  );
}

const styles = StyleSheet.create({
  helper: { minHeight: 24 },
});
