#!/bin/sh
set -eu
umask 077

if [ "${CANTEEN_DEMO_MODE:-}" != 1 ] || [ "${CANTEEN_DEMO_DB_TRANSPORT:-}" != railway-wireguard ]; then
    echo 'Railway demo mode and private transport are required' >&2
    exit 1
fi
secret=${CANTEEN_DEMO_PROXY_SECRET:-}
if [ "${#secret}" -lt 32 ]; then
    echo 'Missing or short demo proxy secret' >&2
    exit 1
fi
case "$secret" in *[!A-Za-z0-9_-]*) echo 'Proxy secret must be URL-safe ASCII' >&2; exit 1;; esac
PORT=${PORT:-8080}
case "$PORT" in *[!0-9]*|'') echo 'Invalid PORT' >&2; exit 1;; esac
export PORT

envsubst '${PORT} ${CANTEEN_DEMO_PROXY_SECRET}' < /app/nginx.conf.template > /tmp/canteen-nginx.conf
nginx -t -c /tmp/canteen-nginx.conf

java -Xms64m -Xmx256m -XX:+ExitOnOutOfMemoryError \
    -jar /app/canteen-web-1.0.0.jar --canteen.mode=serve --server.port=18080 &
java_pid=$!
nginx -c /tmp/canteen-nginx.conf -g 'daemon off;' &
nginx_pid=$!

stop() {
    kill "$java_pid" "$nginx_pid" 2>/dev/null || true
    wait "$java_pid" "$nginx_pid" 2>/dev/null || true
}
trap stop INT TERM EXIT
while kill -0 "$java_pid" 2>/dev/null && kill -0 "$nginx_pid" 2>/dev/null; do
    sleep 1
done
echo 'Demo web or proxy exited' >&2
exit 1
