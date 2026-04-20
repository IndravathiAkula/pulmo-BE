FROM eclipse-temurin:17-jdk

WORKDIR /app

# Copy entire project
COPY . .

# Build inside container
RUN ./gradlew build

# Run app
CMD ["java","-jar","build/quarkus-app/quarkus-run.jar"]