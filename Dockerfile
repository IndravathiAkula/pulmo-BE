FROM eclipse-temurin:17-jdk

WORKDIR /app

COPY build/quarkus-app/ /app/

CMD ["java","-jar","/app/quarkus-run.jar"]