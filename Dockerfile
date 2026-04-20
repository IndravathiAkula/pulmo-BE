FROM eclipse-temurin:17-jdk

WORKDIR /app

COPY . .

# 🔥 ADD THIS LINE (fix permission)
RUN chmod +x gradlew

# Build
RUN ./gradlew build

# Run app
CMD ["java","-jar","build/quarkus-app/quarkus-run.jar"]