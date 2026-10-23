@echo off
setlocal
set "TASK_ROOT=%~dp0"
if defined JAVA_HOME (
  set "TASK_JAVA=%JAVA_HOME%\bin\java.exe"
) else (
  set "TASK_JAVA=java.exe"
)
"%TASK_JAVA%" -classpath "%TASK_ROOT%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
