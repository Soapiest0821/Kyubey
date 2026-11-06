# Makeit 패키지를 만든다. 로컬과 GitHub Actions 가 같은 스크립트를 쓴다.
#   .\packaging\build.ps1 -Type app-image   # WiX 없이 시험용 폴더만
#   .\packaging\build.ps1 -Type msi         # 배포용 (WiX 3 필요)
param(
  [ValidateSet('app-image', 'msi')]
  [string]$Type = 'msi'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

[xml]$pom = Get-Content -Raw -Encoding UTF8 pom.xml
$version = $pom.project.version
if ($version -notmatch '^\d{1,3}\.\d{1,3}\.\d{1,5}$') {
  throw "pom.xml 버전이 MSI 형식(1.2.3)이 아니야: $version"
}

mvn -q -B clean package
if ($LASTEXITCODE -ne 0) { throw 'mvn package 실패' }

$inputDir = Join-Path $root 'target\package-input'
$outputDir = Join-Path $root 'target\dist'
New-Item -ItemType Directory -Force $inputDir, $outputDir | Out-Null
Copy-Item (Join-Path $root 'target\makeit.jar') $inputDir

# 클래스패스로 도는 fat jar 라 런타임 모듈을 직접 적는다. 새 JDK API 를 쓰면 여기도 늘려야 한다.
$modules = @(
  'java.base', 'java.datatransfer', 'java.desktop', 'java.logging', 'java.naming',
  'java.net.http', 'java.prefs', 'java.scripting', 'java.sql', 'java.xml',
  'jdk.charsets', 'jdk.crypto.ec', 'jdk.httpserver', 'jdk.localedata', 'jdk.jfr', 'jdk.unsupported', 'jdk.unsupported.desktop', 'jdk.zipfs'
) -join ','

$arguments = @(
  '--type', $Type,
  '--name', 'Makeit',
  '--app-version', $version,
  '--vendor', 'Soapiest0821',
  '--description', 'Makeit desktop widget',
  '--input', $inputDir,
  '--main-jar', 'makeit.jar',
  '--main-class', 'kyubey.Launcher',
  '--add-modules', $modules,
  '--jlink-options', '--strip-debug --no-header-files --no-man-pages',
  '--dest', $outputDir
)

$icon = Join-Path $root 'src\main\resources\img\images.ico'
if (Test-Path $icon) { $arguments += @('--icon', $icon) }

if ($Type -eq 'msi') {
  # 이 UUID 가 바뀌면 업그레이드가 아니라 따로 설치된다. 절대 바꾸지 말 것.
  $arguments += @(
    '--win-upgrade-uuid', '4e2f6a1c-7b3d-4c58-9a06-2d8f1e5b7c34',
    '--win-per-user-install',
    '--win-menu',
    '--win-shortcut',
    '--win-shortcut-prompt',
    '--install-dir', 'Makeit'
  )
}

& jpackage @arguments
if ($LASTEXITCODE -ne 0) { throw 'jpackage 실패' }

if ($Type -eq 'msi') {
  $msi = Join-Path $outputDir "Makeit-$version.msi"
  $hash = (Get-FileHash -Algorithm SHA256 $msi).Hash.ToLowerInvariant()
  [IO.File]::WriteAllText("$msi.sha256", "$hash  Makeit-$version.msi`n", [Text.Encoding]::ASCII)
}
Write-Host "완료: $outputDir"
