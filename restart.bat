@echo off
rem 앱 안에서 restart 를 쳤을 때 도는 스크립트.
rem 자바에서 따옴표를 겹겹이 쌓는 대신 여기로 빼뒀다 - 읽기도 고치기도 쉽게.
rem
rem 부르는 쪽은 restart.vbs 고, 이 창은 숨겨져 있다. 보이는 창이 없으니
rem pause 로 붙잡아 봐야 아무도 못 보는 채로 영영 멈출 뿐이고, start 로
rem 새 창을 꺼내도 숨김을 물려받아 같이 숨는다. 그래서 실패를 알리는 일은
rem 여기서 하지 않고, 끝난 값만 그대로 넘겨서 vbs 가 띄우게 둔다.
cd /d "%~dp0"

rem 방금 죽은 프로세스가 target\ 을 놓을 때까지 잠깐 기다린다.
rem timeout 은 콘솔이 없으면 토라져서 고전적인 ping 으로 센다.
ping -n 4 127.0.0.1 >nul

call "%~dp0run.bat" > "%~dp0restart.log" 2>&1
