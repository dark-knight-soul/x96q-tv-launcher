# Скриншоты для README: запускает лаунчер напрямую (даже если главным экраном стоит другой)
# и снимает главный экран, нижние ряды и пункт управления. Запуск: sh shots.sh   Снимки: /data/local/tmp/shots/
P=io.github.darkknightsoul.vitrina
O=/data/local/tmp/shots; rm -rf $O; mkdir -p $O
key() { input keyevent $1; sleep ${2:-0.6}; }
am start -n $P/.MainActivity > /dev/null
# Первый запуск: ждём, пока скачаются и проявятся обои и погода
sleep 25
screencap -p $O/home.png
key DPAD_RIGHT; key DPAD_RIGHT 1; screencap -p $O/focus.png
key DPAD_DOWN 1; key DPAD_DOWN 1; screencap -p $O/rows.png
key DPAD_UP; key DPAD_UP 1; key DPAD_UP 1; key DPAD_CENTER 1.2; screencap -p $O/control-center.png
key BACK 1
chmod -R 755 $O; echo готово
