# 마중 (도착 모드) · Pleos Connect 앱

차량 내비에 찍힌 목적지를 읽어 **도착 전에 주차장을 먼저 말해 주고**(입구·무료 시간, 근처의 더 넓은 주차장 제안), **기어 P에서 주차 위치와 층·구역을 기억해 두었다가**, 다음에 D로 출발할 때 주차 시간과 요금 기준을 알려 준다. 운전자는 화면을 보지 않고 말로 답한다.

> 앱 이름 **마중** (패키지 `com.arrival.mode`, 화면 안 일부 문구는 아직 "도착 모드").

## APK

**[`apk/붕붕이_마중_release.apk`](apk/붕붕이_마중_release.apk)** · release 빌드(약 28MB)

```bash
adb install apk/붕붕이_마중_release.apk
```

설치 후 권한 허용과 실행 방법은 아래 [에뮬레이터에서 실행·시험](#에뮬레이터에서-실행시험)을 따른다. 공모전 제출용이라 디버그 키로 서명했다(스토어 배포용 아님).

## 테스트 환경

- Pleos Connect 에뮬레이터 v2.1.0 (Android 14, API 34), 디스플레이 2560×1440
- 앱 영역: 1:1 모드 841×1210, 2:0 모드 1682×1210
- 필요한 설정: 에뮬레이터 실행 옵션 `-dns-server 8.8.8.8,1.1.1.1`, 앱 권한(위치·마이크·알림, 사용자 10)

## 동작 흐름

내비·기어·위치 신호로 화면이 넘어간다. 탭으로 넘기는 시연 모드는 없다.

| 순서 | 무엇이 일어나면 | 앱이 하는 일 | 화면 |
|---|---|---|---|
| 1 | 내비에 목적지를 설정 | 10초마다 목적지·남은 거리를 읽음 | 01 대기 |
| 2 | 남은 거리 2km 이하 **또는** 도착 10분 전, 또는 GPS가 목적지 1.5km 안 | 목적지 반경 700m 주차장을 찾아 음성으로 안내: "○분 뒤 도착이에요. ○○은 ○분까지 무료예요. ○미터 옆 ○○도 있어요. 그쪽으로 갈까요?" → 대답 듣기 | 02 브리핑 |
| 3 | "응"(또는 카드 탭) | 내비 목적지를 대안 주차장으로 바꿈. "아니" → "그대로 안내할게요" | 03 목적지 변경 |
| 4 | 기어 P | GPS 위치와 주차장(브리핑에서 고른 곳, 없으면 300m 안 가장 가까운 곳)을 저장 → "몇 층 어디에 세우셨어요?" | 04-1 층·구역 묻기 |
| 5 | "지하 2층 C구역" | 층·구역 저장 → "지하 2층 C구역으로 기억해 둘게요" | 04-2 저장 완료 |
| 6 | 다음 출발 때 기어 D | 주차 시간·무료 시간·요금 안내 | 05 주차 요약 |
| 7 | 05에서 '집'을 고르고 '안내 시작' | 내비 목적지를 집으로 바꿔 길안내 시작(시연용 고정 주소: 화서역) | 01 대기 ("경로를 찾고 있어요") |

함께 동작하는 것
- **내비 음성 양보**: 내비 길안내가 말하는 중이면 기다렸다가 말하고, 앱이 말하는 도중 내비가 시작하면 멈췄다가 다시 말한다.
- **음성 상태 카드**: 안내하는 중 / 듣고 있어요(목소리 크기 파형) / 내비 안내 대기 / 잘 못 들었어요.
- **포그라운드 서비스**: 내비가 앞에 있어도 신호를 받는다. 재부팅 후 자동 시작.
- **주행 중 표시**: D단에서도 화면이 뜬다(`distractionOptimized`).
- **권한 안내**: 권한을 거부하면 화면 위 주황색 띠, 누르면 설정으로 이동.

## 화면

Pleos Connect 디스플레이 **1:1 모드 앱 영역 841×1210**(1dp=1px, 에뮬레이터 실측) 기준. 2:0 모드(1682×1210)에서는 900dp 폭으로 가운데 정렬.

| # | 화면 | 내용 |
|---|---|---|
| 01 | 대기 | 목적지·남은 시간, 도착 시간대 만차율 그래프 |
| 02 | 주차 브리핑 | 대안 주차장 카드 + 목적지 주차장 카드, 음성 상태 카드 |
| 03 | 목적지 변경 | "○○으로 목적지를 바꿨어요", 되돌리기 |
| 04-1 | 층·구역 묻기 | P 헤더, "몇 층 어디에 세우셨어요?" |
| 04-2 | 저장 완료 | "지하 2층 C구역", "주차 위치와 함께 기억해 둘게요" |
| 05 | 주차 요약 | 무료 시간 남은 양, 집/회사 카드, 안내 시작(집 → 내비 길안내) |

화면 순서는 `Screen` enum(`MainActivity.kt`)과 `activity_main.xml`의 `ViewFlipper` 자식 순서가 같다.

## 파일

| 파일 | 역할 |
|---|---|
| `ArrivalService.kt` | 본체(포그라운드 서비스). 내비·기어·위치 이벤트를 받아 엔진을 돌리고 화면 상태를 보냄 |
| `ArrivalEngine.kt` | 상태 기계: 브리핑 → 대안 수락 → 주차 기록 → 다음 출발 요약. 안내 문장 생성 |
| `ArrivalBus.kt` | 서비스 → 화면 상태 전달, 음성 상태(`VoiceState`) |
| `MainActivity.kt` | 화면 그리기(`Screen`), 권한 요청 |
| `NaviGateway.kt` | NaviHelper 3.0.5. `getDestinationInfo` 10초 폴링, `requestRoute`로 목적지 변경 |
| `CarGateway.kt`, `VehicleAccess.java` | Vehicle 2.3.2 기어 P/D 감지 |
| `Announcer.kt` | TextToSpeech 3.3.1 ON_DEVICE(`Locale.KOREA`). 내비 음성 양보. 실패한 문장만 건너뜀 |
| `Listener.kt` | SpeechToText 3.3.1 ON_DEVICE. 대답 해석(`Reply`: 응/아니, 층·구역) |
| `Catalog.kt`, `assets/parking.json` | 주차장 13곳(서울 10, 판교 3). 반경 검색, 주말 전용 주차장은 평일 제외 |
| `ParkingMemory.kt` | 마지막 주차 기록(SharedPreferences, 차 안에만 저장) |
| `BootReceiver.kt` | 부팅 후 서비스 자동 시작 |
| `Permissions.kt` | 요청할 권한 목록 |
| `BarChartView.kt` | 만차율 막대 그래프 |
| `app/src/test/` | `CatalogTest`(평일·주말 필터), `ReplyTest`(대답 해석) |

권한: `NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`, `CAR_DRIVE`, `TTS_SERVICE`, `STT_SERVICE`, `FUSED_LOCATION`, 위치·마이크·알림, 포그라운드 서비스(location, specialUse), 부팅 완료 수신.

## 빌드

- minSdk 28, target/compile 36, Java 17 (JDK 21로 빌드 확인), Gradle 8.14.3
- JDK 17 이상(`JAVA_HOME`)과 Android SDK(`ANDROID_HOME` 또는 `local.properties`의 `sdk.dir`)가 필요하다.

```bash
./gradlew :app:assembleRelease :app:testReleaseUnitTest
# 결과: app/build/outputs/apk/release/app-release.apk → apk/붕붕이_마중_release.apk 로 복사
```

## 에뮬레이터에서 실행·시험

에뮬레이터는 `-dns-server 8.8.8.8,1.1.1.1` 옵션으로 실행한다(없으면 내비 경로 요청 실패). 앱은 사용자 10에서 돈다.

```bash
adb install -r apk/붕붕이_마중_release.apk
# 권한 (사용자 10)
for p in android.permission.POST_NOTIFICATIONS android.permission.ACCESS_FINE_LOCATION \
         android.permission.ACCESS_COARSE_LOCATION pleos.car.permission.FUSED_LOCATION \
         android.permission.RECORD_AUDIO; do adb shell pm grant --user 10 com.arrival.mode $p; done
adb shell am start --user 10 -n com.arrival.mode/.MainActivity
```

| 시험할 것 | 방법 |
|---|---|
| 브리핑 | 내비에서 목적지 설정 → `adb emu geo fix <경도> <위도>`로 GPS를 목적지 1.5km 안으로. 에뮬레이터 내비 위치는 판교 고정이라 남은 거리로는 브리핑이 안 뜬다 |
| 기어 P/D | 에뮬레이터 웹 콘솔의 차량 제어(REST)로 기어 변경 |
| 음성 대답 | 마이크 대신 16kHz 모노 16bit PCM 파일을 앱 `files/`에 `demo_reply.pcm`(브리핑 대답), `demo_spot.pcm`(층·구역)으로 넣으면 한 번 쓰고 지운다 |
| 1:1 모드 | 앱 오른쪽 끝 손잡이(⋮)를 왼쪽으로 끌기: `adb shell input swipe 2547 720 1500 720 600` |

## 시연 영상

에뮬레이터에서 실제 동작을 녹화한 영상: [`video/`](video/) (실제 동작 1부·2부, 음성 포함)

## 에뮬레이터에서 확인한 것

- 2026-11-09: 목적지 → 브리핑 → 음성 "응" → 목적지 변경 → P → 층·구역 음성 저장 → D → 주차 요약까지 한 흐름 동작. 음성 대답은 합성 음성 파일로 확인(층은 정확, 구역 알파벳은 가끔 흔들림)
- 2026-11-09: 원본 버그 2건 수정. TTS가 `Locale.KOREAN`을 거부해 무음 → `Locale.KOREA`. 기어 리스너가 등록 직후 예전 값을 보내 가짜 주차 기록 → 연결 후 2초 무시
- 2026-11-12: main 병합 후 빌드·단위 테스트 5개 통과, 앱 영역 841×1210(1:1)·1682×1210(2:0) 실측
- 2026-11-14: release APK(`apk/붕붕이_마중_release.apk`)를 새로 설치해 실행, 서비스·음성·내비·차량 연결 확인

## 한계

- **폰 전송 없음**: 서드파티용 차↔폰 SDK가 없어 차 안에만 저장한다.
- **집·회사 길안내**(05): '집'은 시연용 고정 주소(화서역)로 길안내한다. 사용자별 집 설정과 '회사' 길안내는 아직 없다.
- **01 만차율 그래프·"혼잡 예상"**: 데이터 없는 고정 값이다.
- **주차장 데이터**: 샘플 13곳. 판교는 평일에 1곳만 열려 대안 주차장 제안이 나오지 않는다.
- **실제 마이크** 음성 인식은 아직 확인하지 않았다(합성 음성 파일로만 시험).
- 공휴일은 주말로 보지 않는다(주말만 판단).
