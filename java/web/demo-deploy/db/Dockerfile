FROM mysql:8.4

ENV MYSQL_DATABASE=canteen_demo \
    MYSQL_USER=demo_app \
    MYSQL_ROOT_HOST=localhost \
    MYSQL_INITDB_SKIP_TZINFO=1
COPY 01-schema.sql 02-grants.sql /docker-entrypoint-initdb.d/
EXPOSE 3306
