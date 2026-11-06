# Удаление приложения с главного экрана: плитка №N (с 0) → Menu → «Удалить приложение».
# Запуск: sh deltest.sh <номер плитки> [confirm]   Снимки: /data/local/tmp/del/
# Без второго аргумента останавливается на системном окне удаления (ничего не удаляет).
N=${1:-0}
O=/data/local/tmp/del; rm -rf $O; mkdir -p $O
key() { input keyevent $1; sleep ${2:-0.6}; }
logcat -c
key HOME 2
i=0; while [ $i -lt $N ]; do key DPAD_RIGHT 0.5; i=$((i+1)); done
key MENU 1.2
screencap -p $O/1_menu.png
# В меню плитки «Удалить приложение» — последний пункт (4-й)
key DPAD_DOWN 0.4; key DPAD_DOWN 0.4; key DPAD_DOWN 0.4
screencap -p $O/2_item.png
key DPAD_CENTER 2.5
screencap -p $O/3_after.png
if [ "$2" = "confirm" ]; then
  # Системное окно «Удалить приложение?» — фокус на «ОК»/«Удалить»
  key DPAD_CENTER 6
  screencap -p $O/4_done.png
fi
logcat -d | grep -iE "REQUEST_DELETE|UninstallerActivity|PackageInstaller|ACTION_DELETE|START u0.*DELETE|Permission Denial" | tail -n 15 > $O/log.txt
chmod -R 755 $O; echo готово
