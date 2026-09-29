// 스와이프를 알아채는 영역. 감싼 내용 위에서 손가락(웹에서는 마우스)으로 밀면 onSwipe 를 부른다.
// 스와이프는 보조 수단이다. 같은 동작을 하는 버튼이 항상 화면에 있다.
//
// 주의: 공용 타입과 판정 함수는 './swipe' 에 있다. 웹 전용 파일(SwipeArea.web.tsx)을 만들게 되면
// 거기서 './SwipeArea' 를 import 하지 말 것 (docs/troubleshooting.md 18)
import { StyleSheet, View } from 'react-native';
import { Gesture, GestureDetector } from 'react-native-gesture-handler';

import { classifySwipe, type SwipeAreaProps, type SwipeAxis, type SwipeDirection } from './swipe';

// 이만큼 움직여야 스와이프가 시작된 것으로 본다. 그 전까지는 탭으로 취급되어 버튼이 눌린다
const ACTIVATE_DISTANCE = 15;
// 받는 축과 다른 방향으로 이만큼 움직이면 스와이프가 아닌 것으로 본다
const FAIL_DISTANCE = 30;

export function SwipeArea({ axis, onSwipe, onActiveChange, children }: SwipeAreaProps) {
  const pan = buildPan(axis, onSwipe, onActiveChange);

  return (
    // touchAction 은 웹 전용 설정이다. 받지 않는 축의 움직임은 브라우저가 평소대로(스크롤) 처리하게 둔다.
    // 지정하지 않으면 이 영역 안에서는 브라우저의 터치 스크롤이 모두 막힌다
    <GestureDetector gesture={pan} touchAction={touchActionFor(axis)}>
      {/* collapsable={false}: 안드로이드가 이 View 를 최적화로 없애 버리면 제스처를 붙일 대상이 사라진다 */}
      <View style={styles.area} collapsable={false}>
        {children}
      </View>
    </GestureDetector>
  );
}

function buildPan(
  axis: SwipeAxis,
  onSwipe: (direction: SwipeDirection) => void,
  onActiveChange: ((active: boolean) => void) | undefined,
) {
  // Gesture.Pan() 은 빌더다. 메서드를 이어 붙여 설정한다.
  // runOnJS(true): 콜백을 일반 자바스크립트 스레드에서 실행한다.
  // 기본값은 UI 스레드(worklet)인데, 거기서는 상태를 바꾸는 일반 함수를 바로 부를 수 없다
  const pan = Gesture.Pan()
    .runOnJS(true)
    .enabled(axis !== 'none')
    .onStart(() => onActiveChange?.(true))
    .onEnd((event) => {
      const direction = classifySwipe({
        dx: event.translationX,
        dy: event.translationY,
        vx: event.velocityX,
        vy: event.velocityY,
      });
      if (direction && axisOf(direction) === axis) {
        onSwipe(direction);
      }
    })
    .onFinalize(() => {
      // 바로 끄지 않고 한 박자 늦춘다. 손을 떼는 순간 눌린 것으로 처리되는 버튼이 있을 수 있어서,
      // 그 처리가 끝난 뒤에 "스와이프 끝"을 알린다
      setTimeout(() => onActiveChange?.(false), 0);
    });

  if (axis === 'vertical') {
    return pan.activeOffsetY([-ACTIVATE_DISTANCE, ACTIVATE_DISTANCE]).failOffsetX([-FAIL_DISTANCE, FAIL_DISTANCE]);
  }
  return pan.activeOffsetX([-ACTIVATE_DISTANCE, ACTIVATE_DISTANCE]).failOffsetY([-FAIL_DISTANCE, FAIL_DISTANCE]);
}

function axisOf(direction: SwipeDirection): SwipeAxis {
  return direction === 'up' ? 'vertical' : 'horizontal';
}

function touchActionFor(axis: SwipeAxis) {
  switch (axis) {
    case 'horizontal':
      return 'pan-y';
    case 'vertical':
      return 'pan-x';
    default:
      return 'auto';
  }
}

const styles = StyleSheet.create({
  area: { flex: 1 },
});
