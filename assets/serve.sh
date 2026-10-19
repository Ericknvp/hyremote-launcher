#!/system/bin/sh
# Keeps the bridge listening. Runs in the FOREGROUND: whoever started us
# supervises us (init, via /system/etc/init/hyremote.rc, which restarts us if
# we ever exit).
#
# nc -L is the whole trick. Toybox netcat in server mode stays bound and forks
# a handler per connection, instead of serving one connection and exiting like
# `nc -l` does. That removes the relisten cycle entirely, which was the source
# of both problems in the old build:
#   - the previous socket lingered in TIME_WAIT, so relistening logged
#     "Address already in use" and lost the race (29 sockets piled up);
#   - the loop then had to sleep between attempts, so a press took 100ms-2100ms
#     depending on timing.
# Measured with -L: 62-68ms flat, and one TIME_WAIT socket instead of 29.
#
# The port is bound on the very first command, so the control is usable as soon
# as this script starts - there is no warm-up and no sleep before serving.

DIR=/data/local/tmp/hyremote
LOG=$DIR/bridge.log

# The bridge picks its own port: 8080 normally, then the next ones if that
# port is already taken on somebody else's projector. The launcher discovers
# it by probing this same range on localhost, and the QR always shows the
# port that is really listening - so the pair (IP, port) is never guessed.
PORT_MIN=8080
PORT_MAX=8095

mkdir -p "$DIR"

echo "[$(date '+%H:%M:%S')] serve: start pid=$$ uid=$(id -u)" >> "$LOG"

while true; do
  p=$PORT_MIN
  while [ "$p" -le "$PORT_MAX" ]; do
    # Record the port before binding. The launcher reads it through /status,
    # so the web page and the QR can never disagree with the real listener.
    echo "$p" > "$DIR/port" 2>/dev/null
    echo "[$(date '+%H:%M:%S')] serve: trying port $p" >> "$LOG"

    # -L keeps the listener bound across connections; -E folds the handler's
    # stderr into the log so a failing `input` call is visible instead of
    # vanishing into /dev/null.
    #
    # A successful bind never returns, so reaching the next line means the
    # port was busy or the listener died: try the next candidate.
    nc -L -E -p $p sh "$DIR/handler.sh" >> "$LOG" 2>&1

    echo "[$(date '+%H:%M:%S')] serve: port $p unavailable, next" >> "$LOG"
    p=$((p + 1))
  done

  # Every candidate failed (rare). Back off so this cannot spin.
  echo "[$(date '+%H:%M:%S')] serve: no free port in $PORT_MIN-$PORT_MAX, retry in 5s" >> "$LOG"
  sleep 5
done
