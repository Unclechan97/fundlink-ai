FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY fundlink-ai-app/target/fundlink-ai-app-1.0.0.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
