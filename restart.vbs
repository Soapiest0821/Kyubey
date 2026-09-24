' 앱 안에서 restart 를 쳤을 때 Java 가 깨우는 껍데기.
' 하는 일은 restart.bat 을 부르는 것뿐인데, 창을 숨기려고(0) 한 겹 씌웠다.
' run.vbs 와 같은 수법 - cmd /c start 로 띄우면 빌드하는 내내, 이어서 새 앱이
' 살아 있는 내내 콘솔 창이 같이 떠 있어서.
'
' 여기선 기다린다(True). 숨긴 창 안에서 새로 띄운 창은 숨김을 물려받아 같이 숨어서
' (start 로 꺼내도 소용없다), 실패했을 때 보여줄 사람이 이 스크립트밖에 없다.
' mvn 은 앱이 꺼질 때까지 안 끝나니 이 wscript 도 그때까지 같이 산다 - 그게 값이다.
'
' 이 파일은 UTF-8 인데 WSH 는 BOM 없는 UTF-8 을 ANSI 로 읽는다. 주석이야 깨져도
' 그만이지만 화면에 찍는 말은 아니라서, restart.bat 과 같이 영문으로 둔다.
Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
here = fso.GetParentFolderName(WScript.ScriptFullName)

code = sh.Run("""" & here & "\restart.bat""", 0, True)

If code <> 0 Then
  MsgBox "Restart failed (exit " & code & ")." & vbCrLf & _
         "See " & here & "\restart.log", vbExclamation, "Kyubey restart"
End If
