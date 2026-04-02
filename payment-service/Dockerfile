FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
RUN apk add --no-cache maven

COPY pom.xml .
COPY shared/pom.xml shared/pom.xml
COPY payment-service/pom.xml payment-service/pom.xml
RUN mvn dependency:go-offline -pl shared,payment-service -am -q 2>/dev/null || true

COPY shared/src shared/src
COPY payment-service/src payment-service/src
RUN mvn -pl shared,payment-service -am package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/payment-service/target/*.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-Dspring.threads.virtual.enabled=true", "-jar", "app.jar"]
