FROM eclipse-temurin:17-jre-alpine

RUN apk add --no-cache nginx gettext \
    && addgroup -S canteen \
    && adduser -S -D -H -u 10001 -G canteen canteen \
    && mkdir -p /app /tmp/nginx-client-body /tmp/nginx-proxy \
    && chown -R canteen:canteen /app /tmp/nginx-client-body /tmp/nginx-proxy

WORKDIR /app
COPY --chown=canteen:canteen canteen-web-1.0.0.jar nginx.conf.template entrypoint.sh ./
RUN chmod 0500 /app/entrypoint.sh
USER canteen
ENV CANTEEN_DEMO_BIND_ADDRESS=127.0.0.1
EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh"]
