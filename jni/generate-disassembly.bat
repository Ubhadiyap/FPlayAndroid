rem cd D:\Android\workspace\FPlay\obj\local\armeabi\objs\SimpleVisualizerJni
rem arm-linux-androideabi-objdump -S -d SimpleVisualizerJni.o > armeabi.txt
rem move armeabi.txt D:\Android\workspace\FPlay\jni\armeabi.txt

rem cd D:\Android\workspace\FPlay\obj\local\armeabi-v7a\objs\SimpleVisualizerJni
rem arm-linux-androideabi-objdump -S -d SimpleVisualizerJni.o > armeabi-v7a.txt
rem move armeabi-v7a.txt D:\Android\workspace\FPlay\jni\armeabi-v7a.txt

cd D:\Android\workspace\FPlay\obj\local\x86_64\objs\MediaContextJni\x
D:\Android\sdk\ndk-bundle\toolchains\x86-4.8\prebuilt\windows-x86_64\i686-linux-android\bin\objdump -S -d MediaContextJni.o > x86.txt
move x86.txt D:\Android\workspace\FPlay\jni\x86.txt

pause
