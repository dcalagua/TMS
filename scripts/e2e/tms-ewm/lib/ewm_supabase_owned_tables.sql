-- Generated from EWM SupabaseOwnedTables.java (test DDL) for the local TMS<->EWM E2E run.

CREATE TABLE IF NOT EXISTS public.organizations (
                    id           uuid PRIMARY KEY,
                    slug         text NOT NULL UNIQUE,
                    name         text NOT NULL,
                    legal_name   text,
                    tax_id       text,
                    country_code char(2) NOT NULL DEFAULT 'PE',
                    currency     char(3) NOT NULL DEFAULT 'PEN',
                    timezone     text NOT NULL DEFAULT 'America/Lima',
                    accent_color text NOT NULL DEFAULT '#1D4ED8',
                    brand_slug   text,
                    portal_name  text,
                    config       jsonb NOT NULL DEFAULT '{}'::jsonb,
                    is_active    boolean NOT NULL DEFAULT true,
                    created_at   timestamptz NOT NULL DEFAULT now(),
                    updated_at   timestamptz NOT NULL DEFAULT now()
                );

CREATE TABLE IF NOT EXISTS public.companies (
                    id              uuid PRIMARY KEY,
                    organization_id uuid NOT NULL REFERENCES public.organizations(id)
                                         ON DELETE CASCADE,
                    name            text NOT NULL,
                    legal_name      text,
                    tax_id          text,
                    erp_code        text,
                    country_code    char(2) NOT NULL DEFAULT 'PE',
                    currency        char(3) NOT NULL DEFAULT 'PEN',
                    config          jsonb NOT NULL DEFAULT '{}'::jsonb,
                    is_default      boolean NOT NULL DEFAULT false,
                    is_active       boolean NOT NULL DEFAULT true,
                    created_at      timestamptz NOT NULL DEFAULT now(),
                    updated_at      timestamptz NOT NULL DEFAULT now()
                );

CREATE TABLE IF NOT EXISTS public.warehouses (
                    id              uuid PRIMARY KEY,
                    organization_id uuid,
                    company_id      uuid NOT NULL,
                    code            text NOT NULL,
                    erp_code        text,
                    name            text NOT NULL,
                    address         text,
                    timezone        text NOT NULL DEFAULT 'America/Lima',
                    is_3pl          boolean NOT NULL DEFAULT false,
                    is_active       boolean NOT NULL DEFAULT true,
                    created_at      timestamptz NOT NULL DEFAULT now(),
                    CONSTRAINT ux_warehouses_company_code UNIQUE (company_id, code)
                );

CREATE TABLE IF NOT EXISTS public.app_users (
                    id                   uuid PRIMARY KEY,
                    organization_id      uuid,
                    company_id           uuid,
                    auth_user_id         uuid UNIQUE,
                    email                text,
                    full_name            text,
                    role                 text,
                    is_platform_operator boolean NOT NULL DEFAULT false,
                    is_active            boolean NOT NULL DEFAULT true,
                    created_at           timestamptz NOT NULL DEFAULT now(),
                    updated_at           timestamptz NOT NULL DEFAULT now()
                );

CREATE UNIQUE INDEX IF NOT EXISTS idx_app_users_email_org
                    ON public.app_users (organization_id, lower(email))
                    WHERE email IS NOT NULL;

CREATE TABLE IF NOT EXISTS public.app_user_warehouses (
                    app_user_id  uuid NOT NULL,
                    warehouse_id uuid NOT NULL,
                    PRIMARY KEY (app_user_id, warehouse_id)
                );

DO $$
                BEGIN
                    IF EXISTS (SELECT 1 FROM information_schema.columns
                                WHERE table_schema = 'public'
                                  AND table_name = 'app_user_warehouses'
                                  AND column_name = 'id') THEN
                        EXECUTE 'ALTER TABLE public.app_user_warehouses '
                                'ALTER COLUMN id SET DEFAULT gen_random_uuid()';
                    END IF;
                END $$;

CREATE TABLE IF NOT EXISTS public.locations (
                    id                uuid PRIMARY KEY,
                    organization_id   uuid,
                    company_id        uuid NOT NULL,
                    warehouse_id      uuid,
                    parent_id         uuid,
                    code              text NOT NULL,
                    name              text,
                    kind              text NOT NULL DEFAULT 'internal',
                    zone              text,
                    aisle             text,
                    level_no          int,
                    position_no       int,
                    pick_sequence     int,
                    max_weight_kg     numeric(12,3),
                    max_volume_m3     numeric(12,6),
                    is_pickable       boolean NOT NULL DEFAULT true,
                    is_replenishable  boolean NOT NULL DEFAULT true,
                    is_putaway_target boolean NOT NULL DEFAULT false,
                    is_blocked        boolean NOT NULL DEFAULT false,
                    block_reason      text,
                    is_active         boolean NOT NULL DEFAULT true,
                    created_at        timestamptz NOT NULL DEFAULT now(),
                    updated_at        timestamptz NOT NULL DEFAULT now()
                );

ALTER TABLE public.warehouses ADD COLUMN IF NOT EXISTS organization_id uuid;

ALTER TABLE public.warehouses ADD COLUMN IF NOT EXISTS address text;

ALTER TABLE public.warehouses ADD COLUMN IF NOT EXISTS is_3pl boolean NOT NULL DEFAULT false;

-- E2E fixture: the EWM development tenant/company and warehouse CD01 (same code TMS knows as the
-- origin's external reference). Fictitious data.
INSERT INTO public.organizations (id, slug, name) VALUES
  ('22222222-2222-4222-8222-222222222222', 'e2e-org', 'E2E Organization') ON CONFLICT DO NOTHING;
INSERT INTO public.companies (id, organization_id, name, is_default) VALUES
  ('11111111-1111-4111-8111-111111111111', '22222222-2222-4222-8222-222222222222', 'E2E Peru', true) ON CONFLICT DO NOTHING;
INSERT INTO public.warehouses (id, organization_id, company_id, code, erp_code, name, is_active) VALUES
  ('33333333-3333-4333-8333-333333333301', '22222222-2222-4222-8222-222222222222',
   '11111111-1111-4111-8111-111111111111', 'CD01', 'CD01', 'CD Lima', true) ON CONFLICT DO NOTHING;
INSERT INTO public.warehouses (id, organization_id, company_id, code, erp_code, name, is_active) VALUES
  ('33333333-3333-4333-8333-333333333302', '22222222-2222-4222-8222-222222222222',
   '11111111-1111-4111-8111-111111111111', 'CD02', 'CD02', 'CD Callao', true) ON CONFLICT DO NOTHING;

-- The EWM administrator the harness signs in as (jwt mode, local JWKS). role = TenantAdmin.
INSERT INTO public.app_users (id, organization_id, company_id, auth_user_id, email, full_name, role, is_active) VALUES
  ('44444444-4444-4444-8444-444444444401', '22222222-2222-4222-8222-222222222222',
   '11111111-1111-4111-8111-111111111111', '0e2e0000-0000-4000-8000-0000000000b1',
   'e2e-ewm-admin@example.test', 'E2E EWM Admin', 'TenantAdmin', true) ON CONFLICT DO NOTHING;
