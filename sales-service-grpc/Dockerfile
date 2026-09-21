FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew gradlew.bat gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY proto proto
COPY sales-domain sales-domain
COPY sales-service-rest sales-service-rest
COPY sales-service-grpc sales-service-grpc
COPY sales-service-rest-h2 sales-service-rest-h2
COPY datagen datagen
RUN ./gradlew :sales-service-grpc:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/sales-service-grpc/build/libs/*.jar app.jar
COPY certs/sales-service-grpc /certs/sales-service-grpc
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
