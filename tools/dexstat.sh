# Как скомпилированы тяжёлые приложения (фильтр dex2oat) и сколько места на /data
APPS="com.google.android.youtube.tv ru.kinopoisk.tv com.netflix.mediaclient ru.rutube.app com.spotify.tv.android com.disney.disneyplus com.amazon.amazonvideo.livingroom mobi.zona.tvlite com.yyt.zapptv com.metaxtv.amedia org.xbmc.kodi"
df -h /data | tail -1
for p in $APPS; do
  f=$(dumpsys package dexopt | grep -A3 "^  \[$p\]" | grep -oE "status=[a-z-]+" | head -1)
  sz=$(du -sk $(dirname $(pm path $p | head -1 | sed 's/package://')) 2>/dev/null | awk '{print $1}')
  echo "$p  $f  ${sz}K"
done
