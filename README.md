# ShipHappens API

## Prerequisites

- Java 27
- Docker (for Postgres, Spark, MinIO / LocalStack via `compose.yaml`)

## Run

1. Start the supporting services (optional if already running):

```bash
cd api
docker compose up -d
```

2. Start the Spring Boot API:

On macOS/Linux:

```bash
cd api
./gradlew bootRun
```

On Windows:

```powershell
cd api
.\gradlew.bat bootRun
```

