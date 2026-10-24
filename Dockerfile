# Builds the Java backend and serves the dashboard from the same container.
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
# data/ is where the write-ahead logs and sealed segments are kept (mount a volume to keep them)
CMD ["sh", "-c", "java -cp out adstore.Server $PORT frontend data"]
