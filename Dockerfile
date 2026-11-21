FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn -B -pl mcp-tx-proxy -am package -DskipTests

FROM eclipse-temurin:21-jre
COPY --from=build /src/mcp-tx-proxy/target/tx-engine.jar /tx-engine.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/tx-engine.jar", "demo", "--port", "8080"]
