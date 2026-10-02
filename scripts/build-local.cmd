@echo off
setlocal
cd /d "%~dp0\.."
if not exist build\classes mkdir build\classes
if not exist build\test-classes mkdir build\test-classes
(for /r src\main\java %%f in (*.java) do @echo "%%f") > build\main-sources.txt
javac --release 21 -encoding UTF-8 -d build\classes @build\main-sources.txt
if errorlevel 1 exit /b 1
xcopy /e /i /y src\main\resources build\classes >nul
(for /r src\test\java %%f in (*.java) do @echo "%%f") > build\test-sources.txt
javac --release 21 -encoding UTF-8 -cp build\classes -d build\test-classes @build\test-sources.txt
if errorlevel 1 exit /b 1
java -cp "build\classes;build\test-classes" org.eljah.busradio.SystemTest
if errorlevel 1 exit /b 1
jar --create --file build\busradio.jar --main-class org.eljah.busradio.Main -C build\classes .
if errorlevel 1 exit /b 1
echo Built build\busradio.jar; use Maven -Ppi for Pi4J.
