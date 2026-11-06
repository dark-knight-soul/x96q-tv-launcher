# Дозамер: холодный старт лаунчера по журналу, запуск приложений (2-й холодный проход и повторный по WaitTime)
O=/data/local/tmp/bench2; rm -rf $O; mkdir -p $O; R=$O/result2.txt
L=$(cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN | tail -1); LP=${L%%/*}
key() { input keyevent $1; sleep ${2:-0.4}; }

echo "== холодный старт лаунчера (Displayed из журнала), 3 раза" > $R
for i in 1 2 3; do
  logcat -c
  am force-stop $LP; sleep 1; key HOME 6
  logcat -d | grep -E "Displayed $LP" | tail -1 | sed 's/.*Displayed //' >> $R
done

echo "== запуск приложений: холодный №2 / повторный (WaitTime, мс)" >> $R
for p in com.google.android.youtube.tv ru.kinopoisk.tv com.netflix.mediaclient ru.rutube.app com.spotify.tv.android; do
  c=$(cmd package resolve-activity --brief -c android.intent.category.LEANBACK_LAUNCHER $p | tail -1)
  case "$c" in */*) ;; *) c=$(cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $p | tail -1);; esac
  am force-stop $p; sleep 1
  cold=$(am start -W -n $c | grep TotalTime | awk '{print $2}')
  sleep 4; key HOME 3
  warm=$(am start -W -n $c | grep WaitTime | awk '{print $2}')
  sleep 2; key HOME 3
  echo "$p  $cold / $warm" >> $R
done
key HOME 2
chmod -R 755 $O
echo готово
