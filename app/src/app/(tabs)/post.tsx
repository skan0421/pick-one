// 올리기 탭. 화면 내용은 src/compose/ComposeForm.tsx 에 있다.
// src/app 아래에는 화면의 주소를 정하는 파일만 두고, 나머지 코드는 밖에 둔다
import { ComposeForm } from '../../compose/ComposeForm';

export default function PostScreen() {
  return <ComposeForm />;
}
