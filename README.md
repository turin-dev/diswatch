# DisWatch

Galaxy Watch 5를 우선 대상으로 한 독립 실행 Wear OS Discord 클라이언트 프로토타입.
Kotlin + Compose for Wear OS. 워치에서 Discord REST/Gateway에 직접 연결하며 별도 서버,
WebView, 폰 컴패니언 앱을 사용하지 않습니다.

> **개발 상태: 검증 전 alpha.** 소스 구현은 포함되어 있으나 이 작성 환경에서 Gradle
> 다운로드가 `Network is unreachable`로 실패했고 Android SDK/워치가 없어 컴파일, APK,
> 실제 Discord 로그인, 기기 UX/메모리 검증은 완료되지 않았습니다. 완성된 배포판이 아닙니다.
> 60–80 MB는 목표이지 측정 결과가 아닙니다. [검증 기록](docs/VALIDATION.md)을 확인하세요.

## 로그인과 지원 범위

QR은 Discord 모바일 앱의 기존 로그인 세션으로 **최초 한 번 스캔·승인**해야 합니다.
로그인 후에는 워치 인터넷 연결만 필요합니다. QR의 설계상 최초 승인까지 휴대폰 없이
수행한다는 의미의 완전한 phone-free onboarding은 지원하지 않습니다.

비공식 사용자 계정 프로토콜은 Discord 변경에 의해 중단될 수 있습니다. Discord가
추가 인증/CAPTCHA를 요구하면 로그인은 실패하며 이를 우회하지 않습니다.
Discord의 [일반 사용자 계정 자동화 안내](https://support.discord.com/hc/en-us/articles/115002192352-Automated-User-Accounts-Self-Bots)
등 사용 정책과 계정 제한 가능성을 확인하세요. Discord와 제휴한 앱이 아닙니다.

| 기능 | 소스 구현 / 현재 제한 |
| --- | --- |
| QR 로그인 | ephemeral RSA-2048/OAEP SHA-256, base64url nonce proof, fingerprint 검증, 티켓 교환; 실서비스 미검증 |
| 로그인 저장 | Android Keystore AES-GCM, no-backup private 파일, atomic write |
| 서버 목록 | REST 100개씩 pagination, 최대 1,000개 |
| 채널 목록 | 일반 텍스트/공지 채널, position 정렬; 채널 권한 최종 판정은 REST 403 |
| 메시지 | 최초 25개, 이전 25개씩; 표시 창은 50개로 제한 |
| 전송/답장 | optimistic UI, nonce 중복 방지, 실패 표시/수동 재시도, 기본 멘션 알림 억제 |
| 내 메시지 수정/삭제 | optimistic UI, 오류 rollback 및 재동기화, 삭제 확인 화면 |
| 이모지 반응 | 자주 쓰는 유니코드 6개 + 기존 반응, 자신의 반응 추가/제거 |
| 사진 | thumbnail/뷰어 분리, CDN 크기 요청 + 로컬 다운샘플링, 정지 이미지 |
| 답장 표시 | 작성자와 원문 240자 미리보기 |
| 읽지 않음 | 워치 로컬 read cursor/채널 점/채팅 경계, 다른 기기와 동기화 안 됨 |
| 로컬 캐시 | 최근 4채널 × 50개 메시지, 필요한 모델만 암호화 저장 |
| 실시간 | create/update/delete/reaction add/remove 증분 처리, heartbeat ACK, RESUME, backoff |
| 수명주기 | Activity onResume 시작, onPause 연결 취소. AOD 상주/백그라운드 서비스 없음 |

음성/영상/화면공유/GIF·스티커 검색/관리/포럼/스테이지/고급 스레드/복잡한 프로필은 제외합니다.
DM 탐색, Markdown/멘션 이름 변환, 커스텀 이모지 이미지 렌더링, 첨부 업로드, 사진 확대 제스처도
현재 포함하지 않습니다. 긴 텍스트는 원문을 줄바꿈해 표시합니다.

## Android Studio에서 빌드

- JDK **17**
- Android SDK Platform **35**, Build Tools **35.0.0**
- AGP **8.9.2**, Gradle **8.11.1**, Kotlin **2.1.20** (버전 고정)
- 최소 Android API **30**, watch 하드웨어 필수

이 폴더를 Android Studio에서 Open 후 SDK 설정 및 Sync를 실행합니다. `local.properties`는
로컬 SDK 경로만 포함하며 커밋하지 않습니다. 공식 Gradle Wrapper JAR/스크립트가 포함되어
있고 배포 ZIP SHA-256이 고정되어 있습니다. 최초 빌드에는 Google Maven/Maven Central/
Gradle 배포 서버 연결이 필요합니다.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

Windows에서는 `gradlew.bat`을 사용합니다. Debug APK는
`app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다. Release는 R8와 resource shrinking을
사용하며 **서명되지 않은 APK**입니다. 설치할 release는 Android Studio의 Generate Signed
Bundle / APK에서 개인 키로 서명하세요. 서명키/토큰을 저장소에 넣지 마세요.

GitHub Actions는 같은 테스트·lint·debug/release 빌드를 실행하고 APK와 보고서를 7일 보관하도록
구성했습니다. 워크플로 실행/성공은 아직 확인되지 않았습니다.

## 사용

1. QR 생성 → Discord 앱에서 QR 스캔 및 로그인 승인.
2. 서버 → 채널. 캐시가 있으면 먼저 표시하고 REST를 갱신합니다.
3. 채팅 아래쪽의 ‘메시지 보내기’를 누르고 워치 키보드로 입력합니다.
4. 메시지를 길게 눌러 반응/답장/복사 및 본인 메시지 수정/삭제를 선택합니다.
5. 사진 썸네일을 누르면 뷰어가 열립니다. Back 또는 닫기로 돌아옵니다.
6. 오래된 메시지를 읽으면 최신 일부는 표시 창에서 제외됩니다. ‘최신 메시지’로 복귀합니다.
7. 설정에서 이미지 캐시 삭제/로그아웃을 할 수 있습니다. 로그아웃은 토큰과 텍스트 캐시도 지웁니다.

## 작은 아키텍처

```text
app/src/main/java/dev/turin/diswatch/
  MainActivity.kt          활동 화면에서만 네트워크 수명 유지
  WatchModel.kt            간단한 StateFlow 조정, foreground 작업 scope
  protocol/               교체 가능한 비공식 Discord 경계
    DiscordApi.kt         OkHttp REST, streaming decode, 429 대기
    Gateway.kt            필터링된 스트리밍 envelope, heartbeat/RESUME
    RemoteAuth.kt         QR 인증, ephemeral RSA, 티켓 교환
    Wire.kt               wire DTO → 작은 UI 모델
  data/
    Models.kt             immutable 모델과 순수 메시지 reducer
    Repository.kt         bounded 캐시, optimistic mutation, read cursor
    SecureStore.kt        AES-GCM/Keystore/AtomicFile
    Images.kt             bounded download, 다운샘플, 크기별 LRU
  ui/WatchApp.kt           Wear Compose 화면, 수동 화면 enum/BackHandler
```

Room, Hilt, Navigation 프레임워크, 이미지 로더 프레임워크, Discord SDK는 사용하지 않습니다.
ZXing core는 QR 생성만 담당하며 R8가 사용하지 않는 코드를 제거합니다.
REST JSON은 IO에서 typed streaming decoding합니다. Gateway는 OkHttp가 수신한 프레임 문자열을
bounded queue에 넣고 IO에서 `JsonReader`로 불필요한 필드를 건너뜁니다. 필요한 일부 필드에만
작은 JSON 객체를 만들며 원본을 장기 보관하지 않습니다. 프레임은 2백만 문자/큐 2개 상한이며
초과 시 끊고 RESUME합니다. 따라서 대형 READY를 보내는 계정에서 반복 실패 가능성이 있습니다.

Compose LazyColumn은 message ID key/contentType, immutable Message 인스턴스를 사용합니다.
메시지 하나가 바뀌면 나머지 모델 인스턴스를 재사용하여 행 skipping이 가능하도록 했습니다.
컨테이너의 목록 StateFlow는 바뀌므로 컨테이너 recomposition 자체가 0이라는 보장은 없습니다.
Layout Inspector 및 실제 프레임 계측으로 검증해야 합니다.

## 메모리/전력 예산

| 대상 | 상한/정책 |
| --- | --- |
| 텍스트 | 최신 4채널 × 50개; 활성 history window 별도 최대 50개, 원본 JSON 미보관 |
| Avatar | 0.5 MiB / 64px — 예산만 준비, 기본 UI는 아바타를 로드하지 않음 |
| Icon | 0.5 MiB / 80px — 예산만 준비, 기본 UI는 아이콘을 로드하지 않음 |
| Thumbnail | 2 MiB / 최대 변 180px |
| Viewer | 3 MiB / 최대 변 640px, 닫으면 우선 비움 |
| 이미지 decode | 동시 2개, 다운로드당 6 MiB 제한, 임시 파일 경유, decode 전 bounds 검사 |
| 메모리 압박 | full → thumbnail/avatar → 전체 LRU 순서로 제거 |
| 네트워크 | OkHttp 공유, background polling/wake lock/work manager/상주 service 없음 |
| 이미지 형식 | RGB_565 소프트웨어 bitmap; GIF 등은 애니메이션 없이 첫 프레임 |

LRU 제한은 **전체 앱 PSS 제한이 아닙니다.** 표시 중인 bitmap, decode 작업, ART/Compose,
네이티브 및 그래픽 메모리를 별도로 고려해야 합니다. 60–80 MB 정상 사용 및 뷰어 100 MB 이내
목표는 [실기기 성능 절차](docs/PERFORMANCE.md)로 측정 후 조정합니다.

## 보안

- 토큰/메시지 암호문은 `noBackupFilesDir`, 키는 Android Keystore. 평문 SharedPreferences 없음.
- 로그 인터셉터/토큰 Logcat 없음, API 예외에 응답 본문/토큰/요청 URL을 넣지 않음.
- APK backup/cleartext 네트워크 비활성화, FLAG_SECURE로 인증 화면 캡처 방지.
- 이미지 요청은 허용된 Discord CDN에만 보내고 Authorization을 붙이지 않음.
- 인증 오류/추가 보안 챌린지는 우회하지 않음. 삭제는 사용자 확인 후 수행.
- 로그아웃은 이 앱의 로컬 세션 삭제이며 Discord 계정 전체 세션을 서버에서 철회하지 않음.

## 알려진 제한 / 다음 검증

- 실제 QR/Gateway 사용자 계정 호환성 미검증. 사용자 계정 Identify/길드 subscription 변화는
  `protocol`에서 대응해야 합니다. 일부 길드 이벤트가 수신되지 않을 수 있으며 수동 새로고침이 있습니다.
- 신규 방문 채널은 마지막 메시지를 로컬 읽음 기준으로 잡습니다. 기존 Discord unread를 가져오지 않습니다.
- 메시지 캐시는 최근 읽기용입니다. 실패한 전송/입력 초안은 process death 후 복원하지 않습니다.
- REST 목록에 권한 없는 채널이 포함될 수 있습니다. 권한 overwrite 계산/숨김은 아직 없습니다.
- 일시 중지와 동시에 실패한 edit/delete/reaction은 롤백되고, 다음 foreground REST로 조정됩니다.
- Emoji burst/super reactions, reaction remove-all/emoji-all, bulk delete, 채널 변경 이벤트는 미지원.
- 네트워크 해제는 exponential backoff로 복구하며 백그라운드 알림을 수신하지 않습니다.
- CI 통과와 Galaxy Watch 5 실기기 계측 이전에는 daily-driver로 권장하지 않습니다.

프로토콜 참고: [Discord Gateway](https://docs.discord.com/developers/events/gateway),
[Remote Auth reference implementation](https://github.com/malmeloo/Discord-QR-Auth-Client),
[Compose for Wear OS](https://developer.android.com/training/wearables/compose).
