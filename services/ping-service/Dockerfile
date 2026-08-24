# Expects the jar to be built first:  ./scripts/build.sh -DskipTests package
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S gps && adduser -S -G gps gps

WORKDIR /app
COPY target/ping-service.jar /app/app.jar
RUN chown -R gps:gps /app

USER gps
EXPOSE 8082

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
