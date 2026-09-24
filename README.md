# Kyubey

- 나 혼자 쓰려 만든 위젯이라 많이 불친절함
- 원하는 디렉토리만 등록하여 씀
- Made by 99% 클로드 코드, 1% 구글링
- 채팅 프롬프트 기본은 애니 [마마마](https://namu.wiki/w/%EB%A7%88%EB%B2%95%EC%86%8C%EB%85%80%20%EB%A7%88%EB%8F%84%EC%B9%B4%E2%98%86%EB%A7%88%EA%B8%B0%EC%B9%B4)의 마도카
- 저작권 이슈로 일러스트를 같이 배포하진 못함
- 난 `src\main\resources\img\마도카.png`에 넣어서 씀
- 세팅하기 불친절할거임

## 윈도우 전용

- `Win+Alt+Space` 로 불러서 한 줄 명령어
  - Powertoys랑 같이 쓸 수 없음!
- Gemini 한테 묻고, 화면을 잘라 검색하고, 폰이랑 파일을 주고받는다. 말로 시킬 수도 있다.

## 할 수 있는 것

| 기능                                     | 부르는 법                                                                                |
| ---------------------------------------- | ---------------------------------------------------------------------------------------- |
| 위젯 부르기 / 내리기                     | `Win+Alt+Space` / `Esc`                                                                  |
| 앱·폴더·사이트·명령 열기                 | 이름 앞글자만 치고 `Enter` (`Tab` 자동완성, `↑↓` 고르기)                                 |
| 사이트에서 검색                          | `유튜브 고양이`, `나무위키 마도카` 처럼 사이트 이름 + 띄우고 검색어                      |
| 구글 바로 검색                           | 글자 치고 `Alt+→`                                                                        |
| 예전에 쓴 명령                           | 빈 입력칸에서 `↑` 최근 순, `↓` 많이 쓴 순                                                |
| LLM 모드 (여러 줄로 Gemini 에 묻기)      | `Alt+←`, 또는 빈 입력칸에서 `←`                                                          |
| 마도카랑 수다 (대화가 파일에 남는다)     | 빈 입력칸에서 `→`                                                                        |
| ~~받아쓰기 (푸시 투 토크)~~ 성능 개 구림 | ~~빈 입력칸에서 `Space` 를 누르고 있는 동안 말하기~~                                     |
| 화면 자르기                              | `Win+Alt+Z` 또는 `캡쳐` — 드래그해서 고르고, `X` 이미지/텍스트(OCR), `C` 복사만/검색까지 |
| 폰으로 파일 보내기 / 받기                | `파일 송신` / `파일 수신` — 뜨는 QR 을 폰으로 찍는다 (같은 와이파이)                     |
| 등록 / 고치기                            | `new` / `edit`                                                                           |
| Gemini 키 넣고 빼기 (`.env`)             | `keys` — 키마다 마지막 결과가 🟢 성공 🟡 서버 오류 🔴 한도 초과 ⚫ 못 쓰는 키 로 붙는다  |
| 폴더를 훑을 곳 다시 고르기               | `setup` 또는 `설정` — 처음 켤 때 한 번 저절로 뜬다                                       |
| 설치된 앱 다시 훑기                      | `scan` 또는 `순회`                                                                       |
| 새로 빌드해서 다시 켜기 / 끄기           | `restart` / `exit`                                                                       |
| 대화 지우기                              | `채팅 삭제` (되돌릴 수 없어서 이름을 끝까지 쳐야 뜬다)                                   |

> 처음 켜면 설정 창이 떠서, 폴더 이름만 쳐도 열 수 있게 훑어둘 폴더를 고른다. 앱은 시작 메뉴에서 알아서 찾고, 빠진 건 `new` 로 넣는다.

> 한글 키보드에서 오른쪽 Alt 가 한/영 키로 먹히면 왼쪽 Alt 를 쓴다.

## 필요한 것

- Windows 10/11
- JDK 21 이상
- Maven 3.8 이상 (`mvn` 이 PATH 에 있어야 한다)
- (선택) Gemini API 키 — LLM 모드, 수다, 캡쳐 OCR 에 쓴다. 없어도 런처 기능은 돈다
- (선택) whisper.cpp — 받아쓰기에 쓴다. 없으면 받아쓰기만 꺼진다

## 설치

```bat
git clone <저장소 주소> Kyubey
cd Kyubey
mvn clean javafx:run
```

처음 켤 때 설치된 앱 목록(`installed.json`)을 한 번 훑어서 만든다. 기록 파일들
(`chat.json`, `history.json` 등)은 없으면 알아서 새로 생긴다.

### 1. Gemini 키 (`.env`)

프로젝트 루트에 `.env` 를 만들고 [Google AI Studio](https://aistudio.google.com/apikey) 에서
받은 키를 적는다. **`WIZ_` 로 시작하는 이름만 읽는다**
키를 여러 개 적으면 번호순으로 돌려 쓰면서, 한도가 찬 키는 건너뛴다.
위젯에서 `keys` 를 치면 창에서 바로 넣고 뺄 수 있다 (저장하면 껐다 켜지 않아도 먹는다).

```env
WIZ_1=발급받은키
WIZ_2=두번째키

# 선택: 모델 바꾸기 (재빌드 없이 여기만 고치면 된다)
# GEMINI_MODEL=gemini-3.8-flash
# GEMINI_FAST_MODEL=
```

`.env` 는 `.gitignore` 에 들어 있다. 저장소에 올리지 말 것.

### 2. 받아쓰기 (whisper.cpp, 선택)

음성은 인터넷으로 안 나간다 — 이 컴퓨터의 whisper.cpp 로 받아 적는다.

1. whisper.cpp 를 설치한다. msys2 로 깔면 `C:/msys64/mingw64/bin` 이나 `ucrt64/bin` 에서
   알아서 찾고, 아니면 PATH 에서 찾는다. `whisper-server.exe` 가 있으면 서버로 띄워둬서
   한 마디에 1초쯤, `whisper-cli.exe` 만 있으면 3초쯤 걸린다.
2. 모델을 `models\` 에 넣는다. `ggml-*.bin` 중 제일 큰 걸 쓴다.
   - [ggml-small.bin](https://huggingface.co/ggerganov/whisper.cpp/tree/main) (약 487MB) 정도면 한국어 한 마디는 충분하다
   - 선택: `ggml-silero-*.bin` (말 골라내기, VAD) 을 같이 넣으면 앞뒤 잡음을 걸러낸다
3. 다른 자리에 뒀으면 `.env` 에 적는다.

```env
# WHISPER_SERVER=C:/path/to/whisper-server.exe
# WHISPER_CLI=C:/path/to/whisper-cli.exe
# WHISPER_MODEL=C:/path/to/ggml-small.bin
# WHISPER_LANG=ko
```

`models\` 도 크기 때문에 저장소에 올리지 않는다.

### 3. 콘솔 창 없이 켜기

`run.vbs` 를 더블클릭하면 콘솔 창을 숨긴 채로 `run.bat` 을 돌린다. 윈도우와 같이 켜려면
`Win+R` → `shell:startup` 폴더에 `run.vbs` 바로가기를 넣는다.

> `run.bat` 은 자기가 있는 폴더로 옮겨가서 돌리니 어디에 받아도 된다.

## 내 것으로 채우기

등록한 것들은 `src/main/resources/json/` 에 있다. `new` / `edit` 로 위젯 안에서 고치면 되고,
파일을 직접 고쳐도 된다.

| 파일                       | 담는 것                                        | 저장소    |
| -------------------------- | ---------------------------------------------- | --------- |
| `apps.json`                | 이름 → 실행 파일/바로가기 경로                 | 안 올라감 |
| `sites.json`               | 이름 → 검색도 되는 사이트 주소                 | 올라감    |
| `bookmarks.json`           | 이름 → 열기만 하는 사이트 주소 (`search` 없음) | 안 올라감 |
| `aliases.json`             | 딴 이름 → 등록해둔 것 (`yt` → 유튜브)          | 안 올라감 |
| `cmds.json`                | 이름 → 폴더에서 돌릴 명령                      | 안 올라감 |
| `editors.json`             | 쓰는 에디터                                    | 올라감    |
| `installed.json`           | 이 컴퓨터에 깔린 앱 (`scan` 으로 새로 훑는다)  | 안 올라감 |
| `dirs.json`                | 폴더 이름 → 경로                               | 안 올라감 |
| `history.json`, `top.json` | 검색 기록, 많이 쓴 것                          | 안 올라감 |
| `chat.json`, `memo.json`   | 마도카랑 나눈 대화, 추려둔 기억                | 안 올라감 |

`search` 주소를 적어둔 사이트만 검색이 된다 (`new` / `edit` 로 저장하면 `search` 가 없는 사이트는
알아서 `bookmarks.json` 으로 간다). 검색어는 주소 끝에 붙고,
주소에 `%s` 가 있으면 그 자리에 들어간다 (`https://namu.wiki/w/%s`). 지금 되는 것:

`유튜브`, `나무위키`, `구글`, `네이버`, `깃허브`, `쿼라`.

`yt`, `namu` 같은 짧은 이름은 `aliases.json` 에 적는 거라 처음엔 비어 있다. `new` 로 딴 이름을 붙이면 된다.
`apps.json`·`cmds.json`·`aliases.json` 은 쓰는 사람마다 달라서 저장소에 올리지 않는다. 없으면 빈 채로 시작한다.

위젯은 이 파일들을 **실행 위치 기준 상대 경로**로 읽고 쓴다. 그래서 항상 프로젝트 루트에서
`mvn javafx:run` 으로 띄운다 (`run.bat` 이 그렇게 한다).

## 알아둘 것

- **파일 주고받기**는 창이 떠 있는 동안만 같은 네트워크에 임시 웹서버를 연다. 주소에 매번 새로
  뽑은 토큰이 붙어서, 토큰이 안 맞으면 404 다. 창을 닫으면 서버도 내려간다.
  Windows 방화벽이 처음에 Java 를 허용할지 물으면 "개인 네트워크" 만 허용하면 된다.
- **구글 렌즈 검색**은 공식 API 가 아니라 브라우저로 폼을 넘기는 방식이라 언젠가 막힐 수 있다.
  막히면 그림은 이미 클립보드에 있으니 렌즈 페이지에서 `Ctrl+V` 하면 된다.
- **캡쳐**는 위젯 창을 숨기지 않는다. 위젯이 안 찍히게 하려면 `Esc` 로 내리고 부른다.
- 받아쓰기 서버는 5분 동안 말을 안 시키면 스스로 내려가서 메모리(약 700MB)를 돌려준다.

## 폰트 라이선스

글꼴은 [Pretendard](https://github.com/orioncactus/pretendard) v1.3.9 (OTF 9굵기) 를
`src/main/resources/style/fonts/` 에 같이 넣어 배포한다.

> Copyright (c) 2021, Kil Hyung-jin (https://github.com/orioncactus/pretendard), with Reserved Font Name 'Pretendard'.
>
> This Font Software is licensed under the SIL Open Font License, Version 1.1.

전문은 [`src/main/resources/style/fonts/Pretendard-OFL.txt`](src/main/resources/style/fonts/Pretendard-OFL.txt) 에 있다.
