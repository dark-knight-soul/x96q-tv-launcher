# Замеры «до» (старый лаунчер). Запуск: sh bench.sh <метка>   Результаты: /data/local/tmp/bench/
TAG=${1:-before}
O=/data/local/tmp/bench
rm -rf $O; mkdir -p $O
L=$(cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN | tail -1)
LP=${L%%/*}
echo "launcher=$L" > $O/result.txt

key() { input keyevent $1; sleep ${2:-0.4}; }

# 1. Главный экран и скриншоты
key HOME 2; screencap -p $O/${TAG}_home.png
key DPAD_RIGHT; key DPAD_RIGHT 1; screencap -p $O/${TAG}_focus.png
key DPAD_DOWN 1; screencap -p $O/${TAG}_down.png
key HOME 2

# 2. Плавность: время кадров при листании пультом
dumpsys gfxinfo $LP reset > /dev/null
for k in RIGHT RIGHT RIGHT RIGHT RIGHT LEFT LEFT LEFT DOWN RIGHT RIGHT RIGHT UP LEFT LEFT DOWN DOWN UP UP RIGHT; do key DPAD_$k 0.35; done
dumpsys gfxinfo $LP > $O/gfx.txt
echo "== кадры лаунчера" >> $O/result.txt
grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile|Number Missed Vsync|Number Slow UI thread|Number Slow bitmap|Number Slow issue draw" $O/gfx.txt >> $O/result.txt

# 3. Память
echo "== память" >> $O/result.txt
grep -E "MemTotal|MemAvailable|SwapFree" /proc/meminfo >> $O/result.txt
dumpsys meminfo $LP | grep -E "TOTAL PSS|TOTAL:" | head -1 | sed "s/^/launcher /" >> $O/result.txt
echo "процессов приложений: $(ps -A -o USER | grep -c u0_a)" >> $O/result.txt

# 4. Холодный старт лаунчера
echo "== старт лаунчера (холодный)" >> $O/result.txt
am force-stop $LP; sleep 2
am start -W -a android.intent.action.MAIN -c android.intent.category.HOME | grep -E "TotalTime|WaitTime" >> $O/result.txt
sleep 3

# 5. Запуск приложений: холодный (после force-stop) и повторный (из фона)
echo "== запуск приложений, мс (холодный / повторный)" >> $O/result.txt
for p in com.google.android.youtube.tv ru.kinopoisk.tv com.netflix.mediaclient ru.rutube.app com.spotify.tv.android; do
  c=$(cmd package resolve-activity --brief -c android.intent.category.LEANBACK_LAUNCHER $p | tail -1)
  case "$c" in */*) ;; *) c=$(cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $p | tail -1);; esac
  am force-stop $p; sleep 1
  cold=$(am start -W -n $c | grep TotalTime | awk '{print $2}')
  sleep 4; key HOME 3
  warm=$(am start -W -n $c | grep TotalTime | awk '{print $2}')
  sleep 2; key HOME 3
  echo "$p  $cold / $warm" >> $O/result.txt
done

# 6. Загрузка системы (из журнала событий текущей загрузки)
echo "== загрузка, мс от включения" >> $O/result.txt
logcat -b events -d | grep -E "boot_progress_(start|system_run|enable_screen)" | awk '{print $NF, $(NF-1)}' >> $O/result.txt
echo "boot_completed: $(getprop sys.boot_completed)" >> $O/result.txt

# 7. Нагрузка процессора в покое на главном экране
key HOME 5
echo "== нагрузка в покое" >> $O/result.txt
top -b -n 1 -m 8 | head -14 >> $O/result.txt
chmod -R 755 $O
echo готово
