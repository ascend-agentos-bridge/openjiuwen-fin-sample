# Java 业务应用（Agent Core + ExternalMemoryRail → Agent Memory REST）
FROM maven:3.9.16-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package dependency:copy-dependencies -DoutputDirectory=/app/lib

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/classes ./classes
COPY --from=build /app/lib ./lib
EXPOSE 8099
ENTRYPOINT ["java","-cp","classes:lib/*","com.openjiuwen.samples.creditmemory.AgentMemoryAssistant"]
