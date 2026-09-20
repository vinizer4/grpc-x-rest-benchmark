FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew gradlew.bat gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY sales-domain sales-domain
COPY sales-service-rest sales-service-rest
COPY sales-service-grpc sales-service-grpc
COPY proto proto
COPY datagen datagen
RUN ./gradlew :datagen:installDist --no-daemon -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/datagen/build/install/datagen /app
ENTRYPOINT ["/app/bin/datagen"]
