@echo off
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul
call "C:\Users\FREDRIK\Documents\enderslicercura\.build\curaengine-host-tests-beadangle\build\Release\generators\conanrunenv-release-x86_64.bat" >nul
ninja -C "C:\Users\FREDRIK\Documents\enderslicercura\.build\curaengine-host-tests-beadangle\build\Release" 2>&1
echo BUILD_EXIT=%errorlevel%
