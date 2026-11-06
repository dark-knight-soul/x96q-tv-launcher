# Прогон интерфейса «Витрины» пультом: скриншоты ключевых состояний + время кадров.
# Запуск: sh uitest.sh   Результаты: /data/local/tmp/ui/
O=/data/local/tmp/ui; rm -rf $O; mkdir -p $O
P=io.github.darkknightsoul.vitrina
key() { input keyevent $1; sleep ${2:-0.4}; }
key HOME 2
dumpsys gfxinfo $P reset > /dev/null
key DPAD_RIGHT; key DPAD_RIGHT 0.8; screencap -p $O/1_right.png
key DPAD_DOWN 0.9; screencap -p $O/2_down.png
key DPAD_DOWN 0.9; screencap -p $O/3_down2.png
key DPAD_UP; key DPAD_UP 0.9; screencap -p $O/4_back_top.png
key DPAD_UP 0.8; screencap -p $O/5_corner.png
key DPAD_CENTER 1; screencap -p $O/6_cc.png
key BACK 0.8
# Упор в край слева — плитка должна качнуться
key DPAD_DOWN; key DPAD_LEFT; key DPAD_LEFT; key DPAD_LEFT; key DPAD_LEFT; key DPAD_LEFT 0.6
# Быстрое листание
for k in RIGHT RIGHT RIGHT RIGHT LEFT LEFT LEFT LEFT DOWN RIGHT RIGHT RIGHT DOWN LEFT LEFT UP UP RIGHT; do key DPAD_$k 0.25; done
dumpsys gfxinfo $P > $O/gfx.txt
grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile|Number Missed Vsync|Number Slow UI thread|Number Slow bitmap|Number Slow issue draw" $O/gfx.txt > $O/result.txt
dumpsys meminfo $P | grep -E "TOTAL:" | head -1 >> $O/result.txt
key HOME 1
chmod -R 755 $O
echo готово
