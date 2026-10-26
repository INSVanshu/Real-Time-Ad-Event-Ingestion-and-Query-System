
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY backend backend
RUN mkdir out && javac -d out backend/src/main/java/adstore/*.java

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/out out
COPY frontend frontend
ENV PORT=8080
EXPOSE 8080

CMD ["sh", "-c", "java -cp out adstore.Server $PORT frontend data"]

