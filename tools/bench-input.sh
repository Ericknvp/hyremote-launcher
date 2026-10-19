#!/system/bin/sh
# Mide el coste real de inyectar una tecla por distintos caminos.
N=20

t0=$(date +%s%N)
i=0
while [ $i -lt $N ]; do
    input keyevent 0
    i=$((i + 1))
done
t1=$(date +%s%N)
echo "input keyevent      : $(( (t1 - t0) / 1000000 / N )) ms por tecla"

t0=$(date +%s%N)
i=0
while [ $i -lt $N ]; do
    cmd input keyevent 0
    i=$((i + 1))
done
t1=$(date +%s%N)
echo "cmd input keyevent  : $(( (t1 - t0) / 1000000 / N )) ms por tecla"

t0=$(date +%s%N)
i=0
while [ $i -lt $N ]; do
    /system/bin/true
    i=$((i + 1))
done
t1=$(date +%s%N)
echo "referencia (fork)   : $(( (t1 - t0) / 1000000 / N )) ms por proceso"
