# Galaxy Watch 5 측정 계획

## 환경

실제 Watch 5의 40/44mm 모델, OS/빌드 번호, RAM 여유, Wi-Fi/LTE 상태, 배터리 잔량을 기록한다.
Debug 수치와 release 수치를 혼합하지 않는다. 최적화 release APK를 서명해 설치한다.
개인 토큰/메시지/QR을 보고서나 공개 trace에 넣지 않는다.

## 기본 시나리오

1. force-stop 후 cold start, 서버 → 캐시 있는 채널 → 최근 25개 표시 시간.
2. 4개 채널을 10회 교대로 열고 스크롤. 변경 없는 행의 Compose recomposition count 측정.
3. 동일 메시지 수정/반응/삭제 이벤트를 받으며 스크롤. 다른 행 인스턴스 유지 확인.
4. 큰 사진 10장을 순차로 열고 닫기. 180px/640px downsample과 뷰어 종료 후 메모리 회수 확인.
5. 워치의 다른 앱으로 가거나 화면 끄기/AOD 전환. Gateway/QR socket이 즉시 닫히는지 확인.
6. airplane mode → 복구, Wi-Fi 전환, Gateway resume 실패/expired session을 각각 검증.
7. 앱 프로세스 종료 후 텍스트 캐시/로그인 복원. logout 후 다시 시작하여 정보가 남지 않는지 확인.
8. REST 401/403/429, 전송 중 단절, Gateway 먼저/REST 먼저 도착 순서로 optimistic reconciliation 확인.

## 명령

```sh
adb shell dumpsys meminfo dev.turin.diswatch
adb shell dumpsys gfxinfo dev.turin.diswatch reset
# 시나리오 수행
adb shell dumpsys gfxinfo dev.turin.diswatch framestats
adb shell am send-trim-memory dev.turin.diswatch RUNNING_LOW
adb shell am send-trim-memory dev.turin.diswatch RUNNING_CRITICAL
adb shell am send-trim-memory dev.turin.diswatch COMPLETE
```

Android Studio Memory Profiler, Layout Inspector, Perfetto를 병행한다.
Release에서 instrumentation이 필요하면 별도 benchmark buildType과 profileable manifest를
추가한다. 운영 release에 debuggable을 켜지 않는다.

## 합격 목표 (아직 측정하지 않음)

| 항목 | 목표 |
| --- | --- |
| 정상 텍스트 사용 PSS | 대체로 60–80 MB |
| 사진 뷰어 | 100 MB 근처 장기 유지 없음 |
| 60Hz scroll | 주된 경로에서 frame 16.7ms 이내, p95/p99와 jank 비율 별도 기록 |
| cached channel | 네트워크 완료를 기다리지 않고 첫 프레임에 모델 제공 |
| background | 유지 WebSocket 0, polling/wake lock 0 |
| 메모리 압박 | 이미지 캐시 먼저 감소, 텍스트 캐시 유지, crash 없음 |

Bitmap LRU 한도만으로 총 PSS를 판단하지 않는다. 낮은 여유 메모리 상태에서 LMK로 종료되어도
다음 실행 시 안전한 세션/캐시 복원을 확인한다. `onTrimMemory` 호출 시점은 OS에 따라 다르므로
평소 상한과 lifecycle 정리를 우선한다.

## Baseline Profile 추가 경로

향후 `:baselineprofile`의 `com.android.test` 모듈과 `androidx.baselineprofile` 플러그인을
추가하고 release variant 대상으로 cold start/서버/캐시 채널/스크롤/사진 시나리오를 수집한다.
실계정 QR·토큰을 프로필 테스트에 포함하지 말고 테스트 fixture 주입용 protocol interface를
분리한다. 첫 측정 전에는 효과를 추정 수치로 적지 않는다.
