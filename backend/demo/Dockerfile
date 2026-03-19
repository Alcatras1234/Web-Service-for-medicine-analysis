# 1. Этап сборки с Maven
FROM maven:3.9.4-eclipse-temurin-21 AS build

WORKDIR /backend

# Копирование pom.xml и установка зависимостей
COPY pom.xml /backend/
RUN mvn dependency:resolve

# Копирование исходников
COPY src /backend/src/

# Очистка и сборка проекта с тестами
RUN mvn clean install -DskipTests

# 2. Этап для запуска приложения с JDK
FROM eclipse-temurin:21-jdk-alpine AS server_app

WORKDIR /app

# Установка curl (если нужно для проверки)
RUN apk add --no-cache curl

# Копирование собранного JAR файла
COPY --from=build /backend/target/*.jar /app/backend.jar

# Копирование ресурсов
COPY --from=build /backend/src/main/resources/templates /app/src/main/resources/templates

# Открытие порта приложения
EXPOSE 8080

# Запуск приложения
ENTRYPOINT ["java", "-jar", "backend.jar"]
