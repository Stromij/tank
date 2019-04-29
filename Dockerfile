FROM openjdk:8-jdk-alpine AS build

ADD . /build
WORKDIR /build

RUN ./gradlew shadowJar

FROM openjdk:8-jre-alpine

ENV APPLICATION_USER ktor
RUN adduser -D -g '' $APPLICATION_USER

RUN mkdir -p /app/logs
RUN mkdir /storage
RUN chown -R $APPLICATION_USER /app
RUN chown -R $APPLICATION_USER /storage

USER $APPLICATION_USER

EXPOSE 23513/tcp

COPY --from=build /build/build/libs/tank-fat.jar /app/tank-fat.jar
COPY --from=build /build/resources/application.conf /app/application.conf
COPY --from=build /build/scripts/start.sh /app/start.sh
WORKDIR /app

ENTRYPOINT ./start.sh

CMD ["-config=/app/application.conf"]