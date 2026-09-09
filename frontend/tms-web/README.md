# eTMS by EBIM — cliente web

Frontend de gestión de transporte. Diseño, stack y lenguaje visual de la suite EBIM
(los mismos que eGMAO); módulos, lógica de negocio y conexiones de eTMS.

Solo frontend: no hay backend en este repositorio. Todo dato de negocio viaja
**React → Spring Boot → PostgreSQL**, y Supabase se usa exclusivamente para autenticación.

## Arrancar

```bash
nvm use                        # Node 22, el de `.nvmrc`
npm install
cp .env.example .env.local     # y rellena los valores
npm run dev                    # http://localhost:5173
```

| Script | Qué hace |
|---|---|
| `npm run dev` | Servidor de desarrollo con HMR |
| `npm run build` | `tsc -b` + build de producción a `dist/` |
| `npm run typecheck` | Solo la comprobación de tipos |
| `npm run lint` | oxlint |
| `npm run test` | Vitest (la capa está configurada; los tests se escriben según haga falta) |

### La versión de Node no es opcional

**`^20.19.0 || >=22.12.0`**, declarado en `engines` y fijado en `.nvmrc`. Es el suelo que
imponen Vite 8 —que empaqueta con rolldown, y rolldown importa `styleText` de `node:util`— y
oxlint, que resuelve su binario nativo por `engines`.

Lo que hace que merezca estar escrito es cómo se manifiesta cuando no se cumple: con Node 18 no
sale un mensaje sobre la versión, salen tres fallos que no se parecen entre sí. `vite build`
muere con `SyntaxError: ... does not provide an export named 'styleText'`; `npm run lint` muere
con `ERR_UNKNOWN_FILE_EXTENSION`, porque `npm ci` se saltó en silencio el binding nativo de la
plataforma al no cumplirse su `engines`; y `tsc -b` pasa sin quejarse, que es justo lo que hace
creer que el entorno está bien. `npm run test` cae con el mismo `styleText` que el build, así
que en un Node viejo **la comprobación de tipos es la única puerta que llega a ejecutarse**.

Declararlo convierte los cuatro en un `EBADENGINE` de `npm ci`, y deja escrito en el repositorio
lo mismo que `amplify.yml` instala en `preBuild` antes de construir.

## Conexiones

Cada una se configura por variable de entorno y ninguna se asume presente.

| Variable | Para qué | Si falta |
|---|---|---|
| `VITE_API_BASE_URL` | **Backend de eTMS** (Spring Boot). El único endpoint de datos de negocio: maestros, flota, pedidos, planificación, viajes, tarifas, torre de control, KPIs, integraciones y auditoría. | Cae a `http://localhost:8080/api/v1` |
| `VITE_SUPABASE_URL` / `VITE_SUPABASE_ANON_KEY` | **Supabase Auth**, y nada más: login, refresh y logout. Ninguna tabla de negocio se consulta por ahí. | Cae al stack local del CLI de Supabase; el login falla con un error de red normal en vez de tumbar la app |
| `VITE_GOOGLE_MAPS_API_KEY` | **Google Maps JS API**: selector de ubicación y mapas de paradas. | El selector degrada a latitud/longitud manual y los mapas dicen que no están disponibles. No es un error |

Nunca pongas aquí una service-role key, una URL de base de datos ni ningún secreto de
servidor: solo las variables `VITE_*` llegan al bundle del navegador y son públicas.

### Contrato con el backend

Vive entero en [`src/shared/api/httpClient.ts`](src/shared/api/httpClient.ts):

- **`X-Company-Id`** en cada endpoint con ámbito de empresa, y **`X-Correlation-Id`** en todas.
- Errores **RFC 9457 (`application/problem+json`)**: se ramifica siempre por el `code` estable,
  nunca por el `detail`, que es prosa y puede reescribirse.
- Un fallo de autenticación tiene **un** intento de recuperación: refresh de un solo vuelo y un
  único reintento. Un backend que siga respondiendo 401 cuesta una petición extra, no un bucle.
- Subida multipart y descarga de ficheros con el nombre que sugiere `Content-Disposition`.

## Cómo está organizado

```
src/
  theme.ts              Sistema de diseño: paleta AA, tokens de estado, data-viz, densidad, temas
  index.css             Reset y scrollbar de la suite
  App.tsx  main.tsx     Proveedores y tabla de rutas
  lib/                  colorMode · i18n · locale · enums · ui (toasts, confirm, prompt)
  shared/
    api/                29 clientes contra el backend + httpClient
    auth/               Supabase Auth, contexto de sesión y guarda de ruta
    company/            Multi-empresa (X-Company-Id), permisos y capabilities
    maps/               Cargador de Google Maps y los tres mapas
    ui/                 AppLayout, navegación, y la librería de componentes
  pages/                Una carpeta por módulo
```

### Decisiones que conviene conocer

- **El backend es la autoridad.** Capacidad, transiciones de estado, "a tiempo", totales de
  pedido y coste se pintan tal y como llegan. Nada de eso se recalcula aquí: una segunda copia
  de la regla acaba discrepando de la que factura.
- **Esconder es UX, no seguridad.** El menú y los botones se filtran por las capabilities que
  devuelve `/me`, y cada endpoint vuelve a comprobar el permiso por su cuenta.
- **`null` no es cero.** Un porcentaje sin medir se pinta como una raya; un bloque de KPIs que
  la cuenta no puede ver dice "no disponible para tu cuenta" en lugar de enseñar ceros.
- **Idioma.** `t("texto en español")`: la clave *es* el castellano y el diccionario de
  `lib/i18n.ts` lleva el inglés. Cambiar de idioma recarga la pantalla.
- **Paleta de gráficas.** `datavizSeries()` reparte los colores en un orden validado con el
  comprobador de daltonismo, no en el orden de la paleta: el verde de marca y el teal quedan
  demasiado juntos y ese orden los separa.

## Lo que quedó fuera

Los ~90 tests de la versión anterior y su suite de Playwright no se portaron; Vitest queda
configurado para escribir los que hagan falta. Es una decisión consciente, no un olvido.
