# Makeit 운영 노트

Makeit 은 Kyubey 의 `makeit` 브랜치다. `main` 의 업데이트를 merge 해서 따라간다.

## Kyubey 업데이트를 Makeit 에 반영

```sh
git switch makeit
git merge main
# 충돌은 Makeit 쪽 이름, 경로, 캐릭터를 지키는 방향으로 푼다
# pom.xml 의 version 만 올린다 (예: 1.0.0 → 1.0.1)
mvn -q -DskipTests package
git commit -am "makeit 1.0.1"
git tag makeit-v1.0.1
git push origin makeit makeit-v1.0.1
```

태그가 올라가면 `.github/workflows/makeit-release.yml` 이 MSI 를 빌드해 Release 에 올린다.
설치된 앱은 다음에 켤 때 새 버전을 찾아 업데이트를 묻는다.

## 지켜야 할 것

- `pom.xml` 의 version 과 태그는 같아야 한다. 형식은 `1.2.3` 이고 앞 두 자리는 255 이하다.
- `packaging/build.ps1` 의 `--win-upgrade-uuid` 는 절대 바꾸지 않는다.
- 사용자 데이터는 `%APPDATA%\Makeit` 에 둔다. `main` 에서 `src/main/resources/json/` 경로가
  새로 들어오면 merge 때 `AppPaths.JSON` 으로 바꾼다.
- 이 PC 의 위젯 단축키만 바꾸려면 `%APPDATA%\Makeit\hotkey.txt` 에 A~Y, 0~9 중 한 글자를 적는다.
- 시험 실행은 `.\packaging\build.ps1 -Type app-image` 뒤 `target\dist\Makeit\Makeit.exe`.
