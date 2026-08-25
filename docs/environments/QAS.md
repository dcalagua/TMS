# Entorno QAS

Este documento fija qué es QAS en TMS by EBIM, porque hasta ahora no lo era nada: existía la rama
`qas` en Git y un proyecto Supabase hosted que nadie había declarado como perteneciente a un
entorno concreto. Esa ambigüedad ya costó un despliegue bloqueado, así que aquí queda escrito.

## Qué es QAS

| Pieza | Valor |
|---|---|
| Rama Git | `qas` |
| Base de datos | Proyecto Supabase hosted de TMS, región `us-east-1` |
| Auth | Supabase Auth del **mismo** proyecto |
| Propósito | Quality Assurance: validar una entrega antes de que llegue a producción |
| Datos | Desechables. De demostración, reconocibles por el prefijo `QAS-` |
| Esquema | Propiedad de Flyway, y sólo de Flyway |
| Datos de producción | **Prohibidos** |

El *ref* del proyecto no se escribe aquí a propósito: identifica el entorno pero no aporta nada a
quien lee, y las coordenadas concretas viven donde deben vivir, en las variables del despliegue.

## Flyway es la única fuente de verdad del esquema

El esquema de QAS se construye ejecutando `V1 … V35` desde
`backend/tms-api/src/main/resources/db/migration/`. Nada más lo toca:

- **No** se aplica DDL a mano por Studio ni por el editor SQL.
- **No** se usan las migraciones de Supabase para objetos de la aplicación (ADR-002, ADR-004).
- **No** se usa `flyway repair` para tapar una discrepancia de checksum.

El backend arranca con `validate-on-migrate: true`, así que una migración editada después de
haberse aplicado detiene el arranque en vez de continuar sobre un esquema que ya no coincide con
su historia. Es deliberado, y es la razón por la que este entorno se reconstruyó entero el
2026-08-25 en lugar de parchearse.

### Cómo se reconstruye desde cero

Cuando el historial y el código divergen sin remedio —que es lo que pasó con V23— el
procedimiento es reconstruir, no reparar:

    1. DROP SCHEMA tms CASCADE;              -- sólo `tms`; auth/storage/realtime no se tocan
    2. Arrancar el backend con SPRING_PROFILES_ACTIVE=prod contra QAS
       -> Flyway aplica V1..V35 y crea su historia nueva
    3. Ejecutar supabase/seeds/qas_seed.sql
    4. Comprobar: latest = V35, applied = 35, failed = 0

El paso 2 usa el mecanismo real del despliegue, no un cliente SQL: lo que valida el entorno es
que arranque *el backend*, no que las sentencias se dejen ejecutar.

Sobrevive al `DROP SCHEMA` todo lo que no vive dentro de `tms`: el rol `tms_app`, la extensión
`postgis` y las cuentas de `auth.users`. Las tres migraciones que las crean son idempotentes
(`CREATE EXTENSION IF NOT EXISTS`, y `CREATE ROLE` bajo una guarda `IF NOT EXISTS`), así que
reconstruir no falla por encontrárselas ya puestas.

## El seed

`supabase/seeds/qas_seed.sql`. Es re-ejecutable, no lleva ninguna contraseña, y todo lo que crea
lleva el prefijo `QAS-` para que nadie confunda una fila suya con un dato real.

Enlaza las personas con Supabase Auth **buscándolas por correo**, no por identificador escrito a
mano, de modo que sirve igual contra un proyecto QAS recreado donde los ids serían otros. Si una
cuenta no existe todavía en `auth.users`, su fila queda con `auth_user_id` nulo: la persona no
puede entrar hasta que alguien la cree en Studio, y eso se ve, que es mejor que un seed que fije
una credencial.

Contenido: una organización, dos empresas, tres personas con sus tres roles, cuatro ubicaciones
—una de ellas con los dos roles operativos, que es el caso que da sentido al modelo canónico—,
dos zonas, una frecuencia, una ruta con dos paradas, un transportista, un tipo de vehículo, un
vehículo, un conductor, un tarifario, tres pedidos con línea y un plan en borrador con su viaje.

Las fechas son literales y no `CURRENT_DATE`: un seed que se mueve con el reloj da entornos
distintos en cada ejecución, y entonces "en QAS no se ve igual" deja de poder diagnosticarse.

## Los tres entornos

| | LOCAL | QAS | PROD |
|---|---|---|---|
| Rama | `dev` | `qas` | `main` |
| Perfil Spring | `local` | `prod` | `prod` |
| Base de datos | Supabase local (`supabase start`) o PostgreSQL en contenedor | Supabase hosted de QAS | Sin aprovisionar |
| Auth | Supabase local | Supabase Auth de QAS | Sin aprovisionar |
| Flyway | activo | activo | activo |
| `LocalProfileDatabaseGuard` | activo | no se registra (`@Profile("local")`) | no se registra |
| Datos | desechables | desechables | reales |

**LOCAL no debe apuntar nunca a la base de QAS.** No es una preferencia de estilo: bajo el perfil
`local` el guard comprueba que la base esté en la propia máquina y rechaza el arranque si no lo
está, precisamente porque un `.env` apuntando a un proyecto hosted con Flyway habilitado ya se
encontró en el árbol de trabajo tres veces. Ver `docs/development/DATABASE_SAFETY.md`.

PROD todavía no existe. Cuando exista será **otro** proyecto Supabase, nunca este.

## Despliegue

| Pieza | Canal |
|---|---|
| Backend | Render, servicio `tms-api`, desde `backend/tms-api/Dockerfile`, perfil `prod` |
| Frontend | AWS Amplify, desde `amplify.yml` (`appRoot: frontend/tms-web`), artefacto estático |

El frontend **no** se despliega por Docker. `render.yaml` declaraba un servicio `tms-web` que
apuntaba a un `Dockerfile` retirado en `00f9386`, cuando la publicación pasó a Amplify; ese
servicio se retiró del blueprint el 2026-08-25 para que la configuración deje de contradecirse.

Variables que el despliegue espera (sus valores viven en el panel, nunca en el repositorio):

    Backend   SPRING_PROFILES_ACTIVE=prod
              TMS_DB_URL · TMS_DB_USERNAME · TMS_DB_PASSWORD
              TMS_SUPABASE_JWT_ISSUER_URI · TMS_SUPABASE_JWKS_URI
              TMS_CORS_ALLOWED_ORIGINS      <- la URL del frontend QAS; vacío = ninguna llamada
                                               de navegador funciona

    Frontend  VITE_API_BASE_URL             <- URL del backend QAS, terminada en /api/v1
              VITE_SUPABASE_URL · VITE_SUPABASE_ANON_KEY

`TMS_FLYWAY_ENABLED` no se declara y no hace falta: bajo `prod` el perfil fija `enabled: true` y
no hay variable que lo apague.

## Cuentas de prueba

Tres, en `auth.users` del proyecto QAS: una de administración de organización, una de
planificación y una de sólo lectura. Sus contraseñas no están en el repositorio y no deben
estarlo. Para el smoke autenticado de extremo a extremo se leen de las variables
`E2E_USER_EMAIL` y `E2E_USER_PASSWORD`; si no están, esas pruebas se saltan de forma visible en
lugar de fallar en silencio.
