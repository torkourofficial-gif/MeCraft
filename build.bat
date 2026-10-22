@echo off
setlocal EnableExtensions EnableDelayedExpansion

if not defined J2ME_HOME (
  echo Define J2ME_HOME con la ruta del toolkit Java ME.
  exit /b 1
)
if exist "%J2ME_HOME%\lib\cldc_1.1.jar" (
  set CLDC_API=%J2ME_HOME%\lib\cldc_1.1.jar
) else if exist "%J2ME_HOME%\lib\cldcapi11.jar" (
  set CLDC_API=%J2ME_HOME%\lib\cldcapi11.jar
) else (
  echo No se encontro CLDC 1.1 en J2ME_HOME\lib.
  exit /b 1
)
if exist "%J2ME_HOME%\lib\midp_2.0.jar" (
  set MIDP_API=%J2ME_HOME%\lib\midp_2.0.jar
) else if exist "%J2ME_HOME%\lib\midpapi20.jar" (
  set MIDP_API=%J2ME_HOME%\lib\midpapi20.jar
) else (
  echo No se encontro MIDP 2.0 en J2ME_HOME\lib.
  exit /b 1
)

set API_CP=%CLDC_API%;%MIDP_API%
if defined M3G_API_JAR set API_CP=!API_CP!;%M3G_API_JAR%
if exist "%J2ME_HOME%\lib\jsr184_1.1.jar" set API_CP=!API_CP!;%J2ME_HOME%\lib\jsr184_1.1.jar
if exist "%J2ME_HOME%\lib\m3g.jar" set API_CP=!API_CP!;%J2ME_HOME%\lib\m3g.jar
if exist "%J2ME_HOME%\lib\jsr184.jar" set API_CP=!API_CP!;%J2ME_HOME%\lib\jsr184.jar

if not exist build mkdir build
if exist build\classes rmdir /s /q build\classes
if exist build\preverified rmdir /s /q build\preverified
mkdir build\classes
mkdir build\preverified
set SOURCES=
for /r src %%F in (*.java) do set SOURCES=!SOURCES! "%%F"

javac -encoding UTF-8 -source 1.3 -target 1.3 -bootclasspath "!API_CP!" -d build\classes !SOURCES!
if errorlevel 1 exit /b 1

if exist "%J2ME_HOME%\bin\preverify.exe" (
  "%J2ME_HOME%\bin\preverify.exe" -classpath "!API_CP!" -d build\preverified build\classes
) else if exist "%J2ME_HOME%\bin\preverify" (
  "%J2ME_HOME%\bin\preverify" -classpath "!API_CP!" -d build\preverified build\classes
) else (
  echo No se encontro preverify en J2ME_HOME\bin.
  exit /b 1
)
if errorlevel 1 exit /b 1

copy /y MANIFEST.MF build\MANIFEST.MF >nul
for %%F in (*.png) do copy /y "%%F" build\preverified\%%F >nul
jar cfm build\MeCraft.jar build\MANIFEST.MF -C build\preverified .
if errorlevel 1 exit /b 1

for %%F in (build\MeCraft.jar) do set JAR_SIZE=%%~zF
set JAD_SIZE=%JAR_SIZE%
set JAD_SIZE=!JAD_SIZE: =!
(echo MIDlet-Name: MeCraft&echo MIDlet-Version: 0.1.0&echo MIDlet-Vendor: MeCraft&echo MIDlet-1: MeCraft, /iconM.png, mecraft.MeCraft&echo MIDlet-Jar-URL: MeCraft.jar&echo MIDlet-Jar-Size: !JAD_SIZE!&echo MicroEdition-Profile: MIDP-2.0&echo MicroEdition-Configuration: CLDC-1.1) > build\MeCraft.jad
echo Creado build\MeCraft.jar y build\MeCraft.jad
endlocal
