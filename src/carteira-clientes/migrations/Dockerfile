# Contexto de build = raiz do repositorio (o reactor precisa do pom.xml agregador).
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace

COPY pom.xml mvnw ./
COPY .mvn/ .mvn/
COPY src/carteira-clientes/migrations/pom.xml src/carteira-clientes/migrations/pom.xml
RUN chmod +x mvnw

COPY src/carteira-clientes/migrations/src src/carteira-clientes/migrations/src
RUN ./mvnw -f src/carteira-clientes/migrations/pom.xml -o -B package -DskipTests \
    || ./mvnw -f src/carteira-clientes/migrations/pom.xml -B package -DskipTests

# Executor one-shot: sobe, migra, termina. Nao e um servico de longa duracao -- por isso o
# Compose (F7) o declara sem restart policy e o resto da topologia so libera apos ele sair com
# exit 0 (ADR-0014: migracao e etapa explicita de deployment, nunca DDL em runtime da aplicacao).
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=build /workspace/src/carteira-clientes/migrations/target/carteira-clientes-migrations.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
