FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY server ./server
RUN mvn -q -DskipTests -pl server/xianyuops-server -am package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/server/xianyuops-server/target/xianyuops-server.jar /app/app.jar
EXPOSE 48180
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "/app/app.jar"]
