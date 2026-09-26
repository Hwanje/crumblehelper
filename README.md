# 🍪 크럼블 헬퍼 (CrumbleHelper)

쿠키런: 크럼블을 하면서 **게임을 끄지 않고** 공략을 바로 볼 수 있는 안드로이드 오버레이 앱입니다.
화면 위에 떠 있는 🍪 버튼을 누르면 일일던전 · 토벌 · 스테이지 보스 · 아레나 · 육성/시너지 공략이 뜹니다.

## 주요 기능

- **플로팅 버튼 + 공략 패널**: 게임 위에 떠 있고, 끌어서 옮길 수 있음. 패널 바깥을 누르면 게임이 그대로 조작됨
- **상황별 탭**: 🗓 일일던전 / ⚔ 토벌 / 🏰 스테이지 보스 / 🏆 아레나 / 🌱 육성·시너지 / 📝 내 메모
- **추천 덱은 항상 펼쳐서 표시**, 항목을 누르면 공략 팁·태그·영상 링크까지 펼침
- **검색**: 쿠키 이름, 보스, 스테이지 번호(예: `6-30`) 등으로 전체 검색
- **▶ 채널에서 영상 찾기**: 항목마다 [참고 채널](https://youtube.com/channel/UC8qM2iK0haNWH5Gkkyj4XVQ) 안에서 해당 주제 영상을 바로 검색
- **투명도(◐)·글자 크기(가) 조절**, 위치·설정 기억
- **앱 안에서 공략 편집/추가/삭제**, 카테고리 추가
- **URL로 공략 업데이트**: 이 저장소의 `app/src/main/assets/guides.json`을 고쳐 두면 앱에서 "공략 받아오기"로 재설치 없이 갱신
- JSON 내보내기(공유) / 클립보드에서 가져오기로 백업·공유
- **자동 업데이트**
  - 공략: 6시간마다 저장소의 `guides.json`을 확인해서 바뀌었으면 반영 (앱에서 직접 만들거나 고친 항목, 지운 항목은 그대로 유지)
  - 앱: 12시간마다 GitHub Releases를 확인해서 새 버전이 있으면 알림 → 누르면 APK를 받아 설치 화면까지 열어 줌

## 설치

1. GitHub의 **Actions → Build APK → 최신 실행 → Artifacts**에서 `CrumbleHelper-apk`를 내려받거나,
   `v1.0.0` 같은 태그를 푸시하면 **Releases**에 APK가 올라갑니다.
2. 폰에서 APK 설치 (출처를 알 수 없는 앱 설치 허용 필요)
3. 앱 실행 → **다른 앱 위에 표시 권한 허용** → **▶ 헬퍼 시작**
4. 쿠키런: 크럼블 실행 → 🍪 버튼 터치

직접 빌드: Android Studio로 열거나 `./gradlew assembleRelease` (JDK 17, Android SDK 35 필요).

## 새 버전 배포하기

```bash
git tag v1.1.0 && git push origin v1.1.0
```
태그를 푸시하면 Actions가 APK를 빌드해 Releases에 올리고, 설치된 앱이 새 버전을 알려 줍니다.
공략만 바꿀 때는 태그 없이 `app/src/main/assets/guides.json`만 고쳐서 푸시하면 됩니다.

### 서명 키 (앱 자동 업데이트에 필요)
업데이트가 기존 앱 위에 설치되려면 모든 버전이 같은 키로 서명돼야 합니다.
키는 저장소에 넣지 말고 **Settings → Secrets and variables → Actions**에 등록하세요.

1. 키 만들기 (PC에서 한 번): `keytool -genkeypair -keystore release.keystore -storetype PKCS12 -alias crumblehelper -keyalg RSA -keysize 2048 -validity 10000`
2. `base64 -w0 release.keystore` 결과를 `KEYSTORE_BASE64`로 등록
3. `KEYSTORE_PASSWORD`, `KEY_PASSWORD`(PKCS12면 같은 값), `KEY_ALIAS`(`crumblehelper`) 등록

시크릿이 없으면 CI가 임시 디버그 키로 서명하므로, 그 APK는 다음 버전으로 업데이트 설치가 되지 않습니다.
키를 처음 등록한 뒤 나온 버전은 한 번만 기존 앱을 지우고 설치하면 그다음부터는 자동 업데이트됩니다.

## 공략 데이터 (`guides.json`)

```jsonc
{
  "version": 1,
  "updated": "2026-09-26",
  "channelId": "UC8qM2iK0haNWH5Gkkyj4XVQ",   // '채널에서 찾기'가 검색할 유튜브 채널
  "notice": "패널 하단에 표시할 안내문",
  "categories": [
    {
      "id": "daily", "title": "일일던전", "emoji": "🗓",
      "entries": [
        {
          "id": "daily-gold",
          "title": "골드 던전",
          "tags": ["골드", "재화"],          // 검색용
          "summary": "한두 줄 핵심",
          "deck": ["탱커 1", "광역 딜러 2", "힐러 1"],   // 항상 표시
          "tips": ["펼쳤을 때 보이는 팁"],
          "search": "골드 던전",             // 채널 검색어 (비우면 제목)
          "links": [{ "label": "영상 제목", "url": "https://youtu.be/..." }],
          "verified": false                  // false면 '확인 필요' 뱃지
        }
      ]
    }
  ]
}
```

> ⚠ 기본 공략 데이터는 웹 검색 요약으로 만든 **초안**입니다 (빌드 환경에서 유튜브 접속이 막혀 영상 내용을 직접 확인하지 못함).
> `확인 필요` 뱃지가 붙은 항목은 채널 영상으로 확인한 뒤 앱이나 `guides.json`에서 고쳐 주세요.
