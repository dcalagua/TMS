# Demo del portal de transportistas: Lima y Arequipa

Solo para entornos desechables (stack local o proyecto demo/QA). **Nunca en producción.**

## Cómo está modelado

En la demo, Lima y Arequipa son **dos empresas** (`DEMO-LIMA`, `DEMO-AREQUIPA`) de la organización
`DEMO`. La empresa es el tenant operativo (ADR-003): cada maestro lleva `company_id` y RLS por
empresa (ADR-005). Entre empresas no se comparte nada operativo.

| Dato | Ámbito | ¿Compartido entre Lima y Arequipa? |
|---|---|---|
| Ubicaciones (CD, clientes/destinos), zonas, rutas, frecuencias | empresa | No |
| Transportistas, tipos de vehículo, vehículos, conductores | empresa | No |
| Tarifas, liquidación de fletes, costo de flota propia | empresa | No |
| Pedidos, planes, viajes, documentos, despachos del almacén | empresa | No |
| Credenciales de integración (ERP, EWM, GPS), webhooks | empresa | No (una por empresa) |
| Configuración (`company_settings`, modo de despacho) | empresa | No |
| Membresías y roles | por empresa, o de toda la organización | Un admin de organización llega a ambas |
| Usuario (`app_user`), catálogo de roles y permisos | global | Sí |
| Organización y entitlements comerciales | organización | Sí |

Consecuencia para el portal: una membresía de transportista es de **una empresa y un
transportista de esa empresa**. Si la misma empresa de transporte (TRSA) atiende Lima y Arequipa,
se registra una vez en cada empresa (mismo RUC, dos filas) y su usuario tiene una membresía en
cada una; en el portal cambia de empresa desde el menú de usuario.

La alternativa es **una sola empresa con dos orígenes** (`CD-LIMA` y `CD-AREQUIPA`, cada uno con
su código de almacén `CD01`/`CD02`): comparten transportistas, flota, conductores, clientes y
tarifas; los planes se hacen por origen y el despacho del almacén se valida contra el origen del
viaje. Esta demo usa dos empresas.

## Orden de ejecución

```sh
DB="postgresql://postgres:postgres@localhost:54322/postgres"
psql "$DB" -f supabase/seeds/local_dev_seed.sql                       # organización, 2 empresas, admin
psql "$DB" -f supabase/seeds/carrier_portal_demo_seed.sql             # Lima
psql "$DB" -f supabase/seeds/carrier_portal_demo_seed_arequipa.sql    # Arequipa
psql "$DB" -v demo_password='<contraseña>' -f supabase/seeds/demo_auth_users.sql

# con el backend levantado y con almacén de evidencias:
DEMO_PASSWORD='<contraseña>' SUPABASE_ANON_KEY='<anon key>' python3 scripts/demo/carrier_portal_demo.py --all
#   o solo una:  TMS_COMPANY_CODE=DEMO-LIMA (por defecto) | DEMO-AREQUIPA
```

Los archivos SQL se pueden volver a ejecutar. El script crea viajes nuevos en cada ejecución.

## Usuarios

| Usuario | Empresa | Rol |
|---|---|---|
| admin@demo.local | toda la organización (Lima y Arequipa) | ORGANIZATION_ADMIN |
| planner.lima@demo.local | DEMO-LIMA | PLANNER |
| viewer@demo.local | DEMO-LIMA | VIEWER |
| planner.arequipa@demo.local | DEMO-AREQUIPA | PLANNER |
| transportista.trsa@demo.local | DEMO-LIMA (TRSA) y DEMO-AREQUIPA (TRSA) | CARRIER_OPERATOR |
| transportista.trsb@demo.local | DEMO-LIMA (TRSB) | CARRIER_OPERATOR |
| transportista.traq@demo.local | DEMO-AREQUIPA (TRAQ) | CARRIER_OPERATOR |
