# Чистый замер холодного запуска приложений: перед каждым запуском останавливаем ВСЕ тестовые приложения
# и Play Маркет, чтобы фон не искажал цифры. Запуск: sh bench3.sh <метка>  Результат: /data/local/tmp/bench3_<метка>.txt
TAG=${1:-run}
R=/data/local/tmp/bench3_$TAG.txt
APPS="com.google.android.youtube.tv ru.kinopoisk.tv com.netflix.mediaclient ru.rutube.app com.spotify.tv.android"
stopall() { for x in $APPS com.android.vending; do am force-stop $x; done; }
echo "== холодный запуск, мс (2 прохода), $TAG" > $R
for p in $APPS; do
  c=$(cmd package resolve-activity --brief -c android.intent.category.LEANBACK_LAUNCHER $p | tail -1)
  case "$c" in */*) ;; *) c=$(cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $p | tail -1);; esac
  line="$p"
  for i in 1 2; do
    stopall; input keyevent HOME; sleep 3
    t=$(am start -W -n $c | grep TotalTime | awk '{print $2}')
    line="$line $t"
    sleep 5
  done
  echo "$line" >> $R
done
stopall; input keyevent HOME
echo готово
