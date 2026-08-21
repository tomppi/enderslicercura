@echo off
rem Incremental rebuild of the bead-angle host engine (no reinstall, no clean).
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul
set "PATH=C:\Users\FREDRIK\Documents\android-sdk\cmake\3.31.6\bin;%PATH%"
cd /d C:\Users\FREDRIK\Documents\enderslicercura\.build\CuraEngine-beadangle
conan build . -pr:h default -pr:b default -of "C:\Users\FREDRIK\Documents\enderslicercura\.build\curaengine-host-tests-beadangle" --build=missing -s build_type=Release -s compiler.cppstd=20 -c tools.build:skip_test=True -o "&:enable_arcus=False" -o "&:enable_plugins=False" -o "&:enable_benchmarks=False" -o "&:enable_extensive_warnings=False" -o "&:with_cura_resources=False" -o "boost/*:header_only=True" -o "*:shared=False" -o "hwloc/*:shared=True"
if errorlevel 1 exit /b 1
echo INCREMENTAL_BUILD_OK
