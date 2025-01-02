# 베이스 이미지 설정
FROM openjdk:17-jdk-slim

# JAR 파일을 컨테이너로 복사
COPY ./app-artifact/*.jar /app/app.jar

# 애플리케이션 실행
CMD ["java", "-jar", "/app/app.jar"]
