# Проверка режима «Переместить» (меню плитки — кнопкой Menu; --longpress у input до View не доходит как долгое нажатие): YouTube вправо на одну позицию, снимок, затем обратно
O=/data/local/tmp/mv; rm -rf $O; mkdir -p $O
key() { input keyevent $1; sleep ${2:-0.6}; }
key HOME 2
input keyevent MENU; sleep 1.2
screencap -p $O/1_menu.png
key DPAD_CENTER 1        # «Переместить»
key DPAD_RIGHT 1
screencap -p $O/2_moved.png
key DPAD_CENTER 1        # готово
key DPAD_RIGHT 0.8; key DPAD_LEFT 0.8
screencap -p $O/3_done.png
# Возвращаем YouTube на первое место
key DPAD_RIGHT 0.8
input keyevent MENU; sleep 1.2
key DPAD_CENTER 1; key DPAD_LEFT 1; key DPAD_CENTER 1
screencap -p $O/4_restored.png
chmod -R 755 $O; echo готово
