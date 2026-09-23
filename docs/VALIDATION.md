# 검증 기록

## 2026-09-22 작성 환경

- JDK 17 존재, Android SDK 및 설치된 Gradle 없음.
- 공식 `gradle/gradle` v8.11.1의 wrapper JAR/Unix/Windows launcher 확보.
- wrapper JAR SHA-256: `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`.
  공식 checksum과 일치. ZIP 배포 체크섬도 wrapper properties에 고정.
- 실행한 명령:
  `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug`
- 결과: **Gradle 배포 다운로드 단계에서 실패** — `java.net.SocketException: Network is unreachable`.
- 따라서 Kotlin 컴파일, unit tests 실행, lint, R8/release 빌드 모두 **실행되지 않음**.
- 8개 reducer/partial update unit test 소스 포함. 통과한 테스트로 집계하지 않음.
- Android manifest/resources XML 파싱, wrapper archive 검사, 소스의 credential logging/
  background permission 정적 점검은 별도 로컬 도구로 수행.
- Android emulator/실기기/실계정이 없어 QR·Gateway·실제 API mutation, 화면 rendering,
  accessibility, 메모리 PSS, 프레임 성능은 **미검증**.

## 배포 전 필수

1. Android CI 전체 통과. 실패 시 API/컴파일/lint 문제를 해결한다.
2. Watch 5에 설치해 QR 로그인부터 사진 보기까지 전체 흐름을 수행한다.
3. 네트워크 단절/재연결, 실패 mutation, 로그아웃, process death 복원을 확인한다.
4. PERFORMANCE.md 절차로 최적화 release의 PSS/jank를 측정한다.

이 문서는 빌드 성공이나 지원 성능의 인증서가 아니다.
