# Кладёт «Витрину» в /system/priv-app (системное приложение) + файл разрешений. Запуск: su 0 sh make-privapp.sh
# Перед запуском: adb push app-release.apk /data/local/tmp/Vitrina.apk; adb push privapp-permissions-vitrina.xml /data/local/tmp/
# Откат: удалить /system/priv-app/Vitrina и /system/etc/permissions/privapp-permissions-vitrina.xml, перезагрузить.
set -e
R=/sys/fs/ext4/dm-0/reserved_clusters
OLD=$(cat $R)
# Раздел заполнен на 100 %, а ядро держит 16 МБ про запас — без этого любая запись даёт «нет места»
echo 0 > $R
mount -o remount,rw /
mkdir -p /system/priv-app/Vitrina
dd if=/data/local/tmp/Vitrina.apk of=/system/priv-app/Vitrina/Vitrina.apk conv=fsync 2>/dev/null
dd if=/data/local/tmp/privapp-permissions-vitrina.xml of=/system/etc/permissions/privapp-permissions-vitrina.xml conv=fsync 2>/dev/null
chmod 755 /system/priv-app/Vitrina
chmod 644 /system/priv-app/Vitrina/Vitrina.apk /system/etc/permissions/privapp-permissions-vitrina.xml
chcon u:object_r:system_file:s0 /system/priv-app/Vitrina /system/priv-app/Vitrina/Vitrina.apk /system/etc/permissions/privapp-permissions-vitrina.xml
sync
mount -o remount,ro /
echo $OLD > $R
ls -lZ /system/priv-app/Vitrina /system/etc/permissions/privapp-permissions-vitrina.xml
cmp /data/local/tmp/Vitrina.apk /system/priv-app/Vitrina/Vitrina.apk && echo "APK совпадает"
rm -f /data/local/tmp/Vitrina.apk /data/local/tmp/privapp-permissions-vitrina.xml
