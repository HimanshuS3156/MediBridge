# Place this file at the ROOT of the "medtour" folder (same level as backend/, frontend/, database/)

FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY backend/src ./backend/src
COPY backend/lib ./backend/lib
RUN mkdir -p backend/out && \
    javac -d backend/out -cp "backend/lib/*" backend/src/medtour/*.java

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/backend/out ./backend/out
COPY backend/lib ./backend/lib
COPY frontend ./frontend

WORKDIR /app/backend
# Main.java looks for "../frontend" relative to the working directory it's launched from —
# this WORKDIR makes that resolve to /app/frontend.
CMD ["java", "-cp", "out:lib/*", "medtour.Main"]
