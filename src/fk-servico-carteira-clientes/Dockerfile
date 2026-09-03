# Contexto de build = raiz do repositorio (o reactor precisa do pom.xml agregador).
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace

# lombok.config faz parte do contrato de compilacao, nao e conveniencia de IDE: e ele que
# carrega copyableAnnotations (@Qualifier do campo -> parametro do construtor gerado) e
# addLombokGeneratedAnnotation. Sem copia-lo para o contexto de build, o container compila sem
# erro e falha no wiring em runtime -- exatamente o que aconteceu na primeira subida.
COPY pom.xml lombok.config mvnw ./
COPY .mvn/ .mvn/
COPY src/fk-servico-carteira-clientes/pom.xml src/fk-servico-carteira-clientes/pom.xml
RUN chmod +x mvnw

COPY src/fk-servico-carteira-clientes/src src/fk-servico-carteira-clientes/src
RUN ./mvnw -f src/fk-servico-carteira-clientes/pom.xml -o -B package -DskipTests \
    || ./mvnw -f src/fk-servico-carteira-clientes/pom.xml -B package -DskipTests

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/src/fk-servico-carteira-clientes/target/fk-servico-carteira-clientes.jar app.jar
USER app
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
