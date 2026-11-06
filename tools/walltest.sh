# Проверка читаемости текста на разных обоях: N раз заставляет «Витрину» взять новые обои и снимает экран.
# Запуск: su 0 sh walltest.sh [N]   Снимки: /data/local/tmp/wall/
N=${1:-4}
O=/data/local/tmp/wall; rm -rf $O; mkdir -p $O
D=/data/data/io.github.darkknightsoul.vitrina/files
for i in $(seq 1 $N); do
  am force-stop io.github.darkknightsoul.vitrina
  rm -f $D/home_bg_v2.jpg
  am start -a android.intent.action.MAIN -c android.intent.category.HOME > /dev/null
  # Ждём, пока новые обои скачаются, сохранятся и проявятся (до 40 с)
  for t in $(seq 1 40); do [ -f $D/home_bg_v2.jpg ] && break; sleep 1; done
  sleep 3
  screencap -p $O/wall_$i.png
done
chmod -R 755 $O; echo готово
