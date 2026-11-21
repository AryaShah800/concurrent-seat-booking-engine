#!/bin/sh
# Starts a throwaway PostgreSQL 16 (loopback only, trust auth) and then the booking API on $PORT.
set -e
PG=/usr/lib/postgresql/16/bin
if [ ! -s "$PGDATA/PG_VERSION" ]; then
  "$PG/initdb" -D "$PGDATA" -U app --auth=trust >/dev/null
fi
"$PG/pg_ctl" -D "$PGDATA" -l "$PGDATA/server.log" -w start \
  -o "-c listen_addresses=127.0.0.1 -c unix_socket_directories=/tmp -c max_connections=20 -c shared_buffers=16MB -c work_mem=2MB -c fsync=off -c synchronous_commit=off"
"$PG/psql" -h 127.0.0.1 -U app -d postgres -tc "SELECT 1 FROM pg_database WHERE datname='seatbooking'" | grep -q 1 \
  || "$PG/createdb" -h 127.0.0.1 -U app seatbooking
exec java $JAVA_OPTS -jar /app/app.jar --server.port="${PORT:-8080}"
