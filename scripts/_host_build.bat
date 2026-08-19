@echo off
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
set "PATH=C:\Users\FREDRIK\Documents\android-sdk\cmake\3.31.6\bin;%PATH%"
set "OUT=C:\Users\FREDRIK\Documents\enderslicercura\.build\curaengine-host-tests"
cd /d C:\Users\FREDRIK\Documents\enderslicercura\.build\CuraEngine
if exist "%OUT%" rmdir /s /q "%OUT%"
conan install . -pr:h default -pr:b default -of "%OUT%" --build=missing -s build_type=Release -s compiler.cppstd=20 -c tools.build:skip_test=True -o "&:enable_arcus=False" -o "&:enable_plugins=False" -o "&:enable_benchmarks=False" -o "&:enable_extensive_warnings=False" -o "&:with_cura_resources=False" -o "boost/*:header_only=True" -o "*:shared=False" -o "hwloc/*:shared=True"
if errorlevel 1 exit /b 1
conan build . -pr:h default -pr:b default -of "%OUT%" --build=missing -s build_type=Release -s compiler.cppstd=20 -c tools.build:skip_test=True -o "&:enable_arcus=False" -o "&:enable_plugins=False" -o "&:enable_benchmarks=False" -o "&:enable_extensive_warnings=False" -o "&:with_cura_resources=False" -o "boost/*:header_only=True" -o "*:shared=False" -o "hwloc/*:shared=True"
if errorlevel 1 exit /b 1
echo HOST_BUILD_OK
