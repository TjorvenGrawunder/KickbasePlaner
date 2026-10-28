FROM eclipse-temurin:27-jdk AS build

WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY src ./src
RUN sh ./gradlew --no-daemon bootJar

FROM eclipse-temurin:27-jre

WORKDIR /app
RUN useradd --system --create-home --home-dir /data --shell /usr/sbin/nologin app \
    && mkdir -p /data \
    && chown app:app /data
COPY --from=build /workspace/build/libs/KickbasePlaner-0.0.1-SNAPSHOT.jar /app/app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod 755 /app/docker-entrypoint.sh

ENV HOME=/data
EXPOSE 8080
VOLUME ["/data"]
USER app

ENTRYPOINT ["/app/docker-entrypoint.sh"]
