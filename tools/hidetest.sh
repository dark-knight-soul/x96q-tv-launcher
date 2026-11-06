# Проверка: плитка №N (с 0) → Menu → «Скрыть»; снимок, где после этого стоит фокус. Запуск: sh hidetest.sh <N>
N=${1:-0}
O=/data/local/tmp/hide; rm -rf $O; mkdir -p $O
key() { input keyevent $1; sleep ${2:-0.6}; }
key HOME 2
i=0; while [ $i -lt $N ]; do key DPAD_RIGHT 0.5; i=$((i+1)); done
screencap -p $O/1_before.png
key MENU 1.2
key DPAD_DOWN 0.4; key DPAD_DOWN 0.4    # 3-й пункт — «Скрыть с главного экрана»
key DPAD_CENTER 3
screencap -p $O/2_after.png
chmod -R 755 $O; echo готово
