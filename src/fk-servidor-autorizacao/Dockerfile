# Contexto de build = raiz do repositorio (o reactor precisa do pom.xml agregador).
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace

COPY pom.xml mvnw ./
COPY .mvn/ .mvn/
COPY src/fk-servidor-autorizacao/pom.xml src/fk-servidor-autorizacao/pom.xml
RUN chmod +x mvnw

COPY src/fk-servidor-autorizacao/src src/fk-servidor-autorizacao/src
RUN ./mvnw -f src/fk-servidor-autorizacao/pom.xml -o -B package -DskipTests \
    || ./mvnw -f src/fk-servidor-autorizacao/pom.xml -B package -DskipTests

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/src/fk-servidor-autorizacao/target/fk-servidor-autorizacao.jar app.jar
USER app
EXPOSE 9000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
