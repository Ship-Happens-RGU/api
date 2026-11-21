# ShipHappens API

## Prerequisites

- Java 27.0.1.1 or later. Older Java 27 builds are affected by Java4Shell (CVE-2026-114938), and the build and the API both refuse to run on them. Check with `java -version`.
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

