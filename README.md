# ms-clinico

Fichas clínicas de los pacientes. Cubre **RF-09**. **Zona crítica de seguridad**:
el contenido de las notas se **cifra a nivel de aplicación** (AES-GCM, clave custodiada
por KMS en AWS) antes de persistir, y la lectura exige el header `X-Usuario-Rol` con valor
`PSICOLOGO` o `ADMIN` (en AWS proviene del JWT de Cognito). Cada acceso queda en el log de auditoría.

**Puerto:** 8086 · **Base de datos:** `clinico_db` · **Swagger:** http://localhost:8086/swagger-ui.html

## Endpoints

| Método | Ruta | Descripción |
|---|---|---|
| POST | `/api/fichas` | Abre la ficha de un paciente (201; 409 si ya existe) |
| GET | `/api/fichas` | Lista fichas |
| GET | `/api/fichas/{id}` | Obtiene una ficha (404 si no existe) |
| GET | `/api/fichas/paciente/{pacienteId}` | Ficha de un paciente |
| PUT | `/api/fichas/{id}` | Actualiza antecedentes |
| DELETE | `/api/fichas/{id}` | Elimina la ficha (204) |
| POST | `/api/fichas/{id}/notas` | Registra nota de sesión, se persiste cifrada (201) |
| GET | `/api/fichas/{id}/notas` | Notas descifradas; exige `X-Usuario-Rol: PSICOLOGO` (403 si no) |

## Cómo correr localmente

```bash
# Opción A — todo el sistema en contenedores (repo rys-infraestructura, clonado al lado de este)
cd ../rys-infraestructura && docker compose up -d --build
curl http://localhost:8086/api/fichas

# Opción B — solo este servicio con Maven (requiere PostgreSQL locales)
mvn spring-boot:run

# Opción C — solo la imagen Docker de este servicio
docker build -t rys/ms-clinico:local .
docker run --rm -p 8086:8086 -e DB_HOST=host.docker.internal rys/ms-clinico:local
curl http://localhost:8086/actuator/health
```

## Arquitectura

Capas `api` → `service` → `repository` → `domain`, más `CifradoService` (AES-GCM con IV
aleatorio por nota) y log de auditoría propio.
Relación JPA destacada: `FichaClinica` **OneToMany** `NotaClinica` (y **ManyToOne** inversa).

## Imagen Docker (EP3)

`Dockerfile` **multi-stage**: la etapa `build` (Maven + JDK 21) descarga dependencias en una capa aparte (caché)
y separa el jar en las capas de Spring Boot; la etapa `runtime` usa solo **JRE 21 sobre Alpine**, corre con un
**usuario sin privilegios** (UID 10001), expone el puerto 8086, define un `HEALTHCHECK` y ajusta la JVM al
límite de memoria del contenedor (`MaxRAMPercentage`). `.dockerignore` deja fuera `target/`, `.git/` y el resto.
Tamaño aproximado: ~145 MB comprimida (≈400 MB descomprimida, de los cuales ~180 MB son el JRE compartido).

## Despliegue en la nube (EP3)

Corre en el clúster **Docker Swarm** de AWS como el servicio `rys_ms-clinico` (definido en
`rys-infraestructura/docker-compose.yml`), detrás del **API Gateway**:
`https://<api>.execute-api.us-east-1.amazonaws.com/prod/clinico/api/fichas` (cabecera `x-api-key`).

### Pipeline CI/CD (`.github/workflows/ci-cd.yml`)

| Etapa | Cuándo | Qué hace |
|---|---|---|
| 1 · Build y pruebas | push y pull request a `main`/`develop` | `mvn verify` + reporte de pruebas |
| 2 · Recursos cloud | push a `main` | Crea/verifica el repositorio ECR `rys/ms-clinico` (análisis de vulnerabilidades al subir) |
| 3 · Imagen | push a `main` | `docker build` multi-stage y push a Amazon ECR con etiqueta = commit (+ `latest`) |
| 4 · Despliegue | push a `main` | `docker service update --image …` por SSH al manager: rolling update `start-first` con rollback automático |
| 5 · Prueba de humo | push a `main` | Por el API Gateway: `/actuator/info` muestra la versión del commit, `/actuator/health` = UP, `GET /api/fichas` = 200 y sin API key = 403 |

Secrets del repositorio: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN` (Learner Lab, se renuevan
en cada sesión), `SWARM_HOST`, `SWARM_SSH_KEY`, `API_URL`, `API_KEY`.

## Correcciones aplicadas sobre la EP2

- **Configuración externa:** la conexión a la base de datos (y a Redis) estaba fija en `localhost`, lo que impedía
  ejecutar el servicio en contenedores; ahora se toma de `DB_HOST`, `DB_PORT`, `DB_NAME`, `REDIS_HOST` (con
  valores por defecto para desarrollo).
- **Salud del servicio:** se agregó Spring Boot Actuator (`/actuator/health/liveness`, `/readiness`, `/info` con la
  versión desplegada), que usan el `HEALTHCHECK` de Docker, Swarm y la prueba de humo del pipeline.
- **Apagado ordenado** (`server.shutdown: graceful`): al escalar hacia abajo o actualizar, termina las peticiones en curso.
- **Secretos fuera del código:** la clave de la base de datos llega como secreto de Docker Swarm
  (`/run/secrets/spring.datasource.password`, leído con `spring.config.import: configtree`).
- `spring.jpa.open-in-view: false` y pool de conexiones acotado (`DB_POOL_SIZE`, 4 por réplica) para que
  varias réplicas compartan la misma instancia RDS.
- **Clave de cifrado:** si faltaba `CLINICO_CRYPTO_KEY`, el servicio usaba en silencio la clave de desarrollo
  escrita en el código. Ahora, con `CLINICO_PERMITIR_CLAVE_DESARROLLO=false` (clúster), **no arranca** sin la clave
  real (secreto de Swarm), y valida que sea de 32 bytes (AES-256). Pruebas nuevas en `CifradoServiceTest`.
