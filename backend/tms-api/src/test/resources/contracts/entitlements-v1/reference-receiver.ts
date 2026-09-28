/**
 * Receptor de REFERENCIA de ebim.entitlements/v1 — solo para tests.
 *
 * Implementa README.md §3 en memoria para validar los fixtures dorados ANTES de
 * que un SaaS los use: si este receptor no produce exactamente
 * expected/put-responses.json y expected/get-applied.json, el fixture está mal.
 * Cada SaaS implementa lo mismo con su propia persistencia (plan §10.2).
 *
 * La verificación criptográfica del JWT es del transporte de cada SaaS; aquí
 * llega ya verificada como `{ scopes, jti }`. Lo que sí vive aquí es lo que el
 * contrato exige encima: scope correcto y `jti` de un solo uso.
 */
import { canonicalize, entitlementChecksum } from '../../../supabase/functions/_shared/entitlements/jcs.ts';
import { assertSnapshotSafe } from '../../../supabase/functions/_shared/entitlements/snapshot.ts';
import {
  ENTITLEMENTS_SCHEMA,
  MAX_SNAPSHOT_BYTES,
  type AppliedStatus,
  type EnforcementMode,
  type EntitlementSnapshot,
  type GetAppliedBody,
  type ReceiverErrorCode,
} from '../../../supabase/functions/_shared/entitlements/types.ts';

export interface ReceiverConfig {
  environment: string;
  productCode: string;
  /** Capacidades que este SaaS sabe hacer cumplir (su manifiesto ACTIVE). */
  knownCapabilities: string[];
  /** Baseline del SaaS: concedida siempre que appActive. */
  baselineCapabilities: string[];
  provisionedTenants: string[];
  enforcementMode: EnforcementMode;
  writeScope: string;
  readScope: string;
}

export interface VerifiedToken {
  scopes: string[];
  jti: string;
}

export interface ReceiverResponse {
  status: number;
  body: Record<string, unknown>;
}

interface Applied {
  snapshot: EntitlementSnapshot;
  version: number;
  checksum: string;
  appliedAt: string;
  status: Exclude<AppliedStatus, 'NONE'>;
  unknownCapabilities: string[];
}

const TOP_LEVEL_KEYS = [
  'schema', 'environment', 'controlPlaneTenantId', 'productCode', 'external', 'snapshotVersion', 'previousVersion',
  'effectiveAt', 'issuedAt', 'appActive', 'planCode', 'capabilities', 'limits', 'allowances', 'aiCredits',
  'correlationId', 'idempotencyKey', 'checksum',
].sort();

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const CODE_RE = /^[a-z0-9]+(\.[a-z0-9_]+)+$/;
const ISO_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/;

function isObject(v: unknown): v is Record<string, unknown> {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}

function exactKeys(v: Record<string, unknown>, required: string[], optional: string[] = []): boolean {
  const keys = Object.keys(v);
  return required.every((k) => k in v) && keys.every((k) => required.includes(k) || optional.includes(k));
}

function validScope(v: unknown): boolean {
  if (!isObject(v) || !exactKeys(v, ['level'], ['companyIds'])) return false;
  if (v.level === 'TENANT') return !('companyIds' in v);
  if (v.level !== 'COMPANY') return false;
  return !('companyIds' in v) || (Array.isArray(v.companyIds) && v.companyIds.every((c) => typeof c === 'string'));
}

function validSources(v: unknown): boolean {
  return Array.isArray(v) && v.every((s) => ['BASELINE', 'PLAN', 'ADDON', 'OVERRIDE'].includes(s as string));
}

/** Forma de schema.json, escrita a mano (sin dependencias). */
export function isValidSnapshotShape(doc: unknown): doc is EntitlementSnapshot {
  if (!isObject(doc)) return false;
  if (JSON.stringify(Object.keys(doc).sort()) !== JSON.stringify(TOP_LEVEL_KEYS)) return false;
  const d = doc;
  const ext = d.external;
  return (
    d.schema === ENTITLEMENTS_SCHEMA &&
    ['DEV', 'QAS', 'DEMO', 'PRD'].includes(d.environment as string) &&
    typeof d.controlPlaneTenantId === 'string' && UUID_RE.test(d.controlPlaneTenantId) &&
    typeof d.productCode === 'string' && /^[a-z0-9]+$/.test(d.productCode) &&
    isObject(ext) && exactKeys(ext, ['tenantId', 'organizationId', 'companyIds']) &&
    typeof ext.tenantId === 'string' && (ext.organizationId === null || typeof ext.organizationId === 'string') &&
    Array.isArray(ext.companyIds) && ext.companyIds.every((c) => typeof c === 'string') &&
    Number.isInteger(d.snapshotVersion) && (d.snapshotVersion as number) >= 1 &&
    (d.previousVersion === null || (Number.isInteger(d.previousVersion) && (d.previousVersion as number) < (d.snapshotVersion as number))) &&
    typeof d.effectiveAt === 'string' && ISO_RE.test(d.effectiveAt) &&
    typeof d.issuedAt === 'string' && ISO_RE.test(d.issuedAt) &&
    typeof d.appActive === 'boolean' &&
    (d.planCode === null || typeof d.planCode === 'string') &&
    Array.isArray(d.capabilities) && d.capabilities.every((c) =>
      isObject(c) && exactKeys(c, ['code', 'enabled', 'scope', 'sources']) && typeof c.code === 'string' && CODE_RE.test(c.code) &&
      typeof c.enabled === 'boolean' && validScope(c.scope) && validSources(c.sources)) &&
    Array.isArray(d.limits) && d.limits.every((l) =>
      isObject(l) && exactKeys(l, ['code', 'value', 'unit', 'enforcement', 'scope', 'sources']) && typeof l.code === 'string' &&
      CODE_RE.test(l.code) && typeof l.value === 'number' && l.value >= 0 && (l.unit === null || typeof l.unit === 'string') &&
      ['HARD', 'SOFT'].includes(l.enforcement as string) && validScope(l.scope) && validSources(l.sources)) &&
    Array.isArray(d.allowances) && d.allowances.every((a) =>
      isObject(a) && exactKeys(a, ['code', 'meterCode', 'included', 'unit', 'period', 'overageMode', 'sources']) &&
      typeof a.code === 'string' && CODE_RE.test(a.code) && typeof a.meterCode === 'string' && typeof a.included === 'number' &&
      a.included >= 0 && (a.unit === null || typeof a.unit === 'string') && isObject(a.period) &&
      exactKeys(a.period, ['start', 'end']) && a.overageMode === 'BLOCK' && validSources(a.sources)) &&
    isObject(d.aiCredits) && exactKeys(d.aiCredits, ['weights', 'weightsVersion']) && Array.isArray(d.aiCredits.weights) &&
    Number.isInteger(d.aiCredits.weightsVersion) &&
    typeof d.correlationId === 'string' && UUID_RE.test(d.correlationId) &&
    typeof d.idempotencyKey === 'string' && /^ma-ent-v1-[0-9a-f]{64}$/.test(d.idempotencyKey) &&
    typeof d.checksum === 'string' && /^sha256:[0-9a-f]{64}$/.test(d.checksum)
  );
}

function error(status: number, code: ReceiverErrorCode, message: string, extra: Record<string, unknown> = {}): ReceiverResponse {
  return { status, body: { error: code, message, ...extra } };
}

export class ReferenceReceiver {
  private readonly applied = new Map<string, Applied>();
  private readonly seenJti = new Set<string>();
  readonly audit: { tenant: string; version: number; outcome: string }[] = [];

  constructor(
    private readonly config: ReceiverConfig,
    private readonly now: () => Date = () => new Date(),
  ) {}

  private authorize(token: VerifiedToken, scope: string): ReceiverResponse | null {
    if (!token.scopes.includes(scope)) return error(403, 'INSUFFICIENT_SCOPE', `Se requiere ${scope}`);
    if (this.seenJti.has(token.jti)) return error(401, 'JTI_REPLAYED', 'jti ya utilizado');
    this.seenJti.add(token.jti);
    return null;
  }

  async put(tenantPath: string, body: unknown, token: VerifiedToken): Promise<ReceiverResponse> {
    const denied = this.authorize(token, this.config.writeScope);
    if (denied) return denied;

    if (!isValidSnapshotShape(body)) return error(422, 'SNAPSHOT_INVALID', 'El snapshot no cumple ebim.entitlements/v1');
    try {
      assertSnapshotSafe(body);
    } catch {
      return error(422, 'SNAPSHOT_INVALID', 'El snapshot contiene claves o valores prohibidos');
    }
    if (new TextEncoder().encode(canonicalize(body)).length > MAX_SNAPSHOT_BYTES) {
      return error(413, 'SNAPSHOT_TOO_LARGE', 'El snapshot supera 64 KB');
    }
    if (body.controlPlaneTenantId !== tenantPath || body.productCode !== this.config.productCode) {
      return error(422, 'SNAPSHOT_INVALID', 'Tenant o producto del cuerpo distintos de la ruta/receptor');
    }
    if (body.environment !== this.config.environment) {
      return error(422, 'ENVIRONMENT_MISMATCH', `El receptor es ${this.config.environment}`);
    }
    if ((await entitlementChecksum(body as unknown as Record<string, unknown>)) !== body.checksum) {
      return error(422, 'CHECKSUM_MISMATCH', 'El checksum no corresponde al contenido');
    }
    if (!this.config.provisionedTenants.includes(tenantPath)) {
      return error(404, 'TENANT_NOT_PROVISIONED', 'Tenant sin provisioning local');
    }

    const current = this.applied.get(tenantPath);
    if (current && body.snapshotVersion < current.version) {
      this.audit.push({ tenant: tenantPath, version: body.snapshotVersion, outcome: 'STALE_SNAPSHOT' });
      return error(409, 'STALE_SNAPSHOT', 'Versión anterior a la aplicada', { appliedVersion: current.version });
    }
    if (current && body.snapshotVersion === current.version) {
      if (body.checksum !== current.checksum) {
        this.audit.push({ tenant: tenantPath, version: body.snapshotVersion, outcome: 'VERSION_CONFLICT' });
        return error(409, 'VERSION_CONFLICT', 'Misma versión con otro contenido', { appliedVersion: current.version });
      }
      this.audit.push({ tenant: tenantPath, version: body.snapshotVersion, outcome: 'REPLAYED' });
      return { status: 200, body: this.putBody(current, true) };
    }

    const codes = [...body.capabilities, ...body.limits, ...body.allowances].map((c) => c.code);
    const unknownCapabilities = [...new Set(codes.filter((c) => !this.config.knownCapabilities.includes(c)))].sort();
    const next: Applied = {
      snapshot: body,
      version: body.snapshotVersion,
      checksum: body.checksum,
      appliedAt: this.now().toISOString(),
      status: unknownCapabilities.length > 0 ? 'APPLIED_WITH_WARNINGS' : 'APPLIED',
      unknownCapabilities,
    };
    this.applied.set(tenantPath, next);
    this.audit.push({ tenant: tenantPath, version: next.version, outcome: next.status });
    return { status: 200, body: this.putBody(next, false) };
  }

  private putBody(a: Applied, replayed: boolean): Record<string, unknown> {
    return {
      appliedVersion: a.version,
      appliedChecksum: a.checksum,
      appliedAt: a.appliedAt,
      status: a.status,
      unknownCapabilities: a.unknownCapabilities,
      replayed,
    };
  }

  get(tenantPath: string, token: VerifiedToken): ReceiverResponse {
    const denied = this.authorize(token, this.config.readScope);
    if (denied) return denied;
    if (!this.config.provisionedTenants.includes(tenantPath)) {
      return error(404, 'TENANT_NOT_PROVISIONED', 'Tenant sin provisioning local');
    }
    const a = this.applied.get(tenantPath);
    const body: GetAppliedBody = {
      controlPlaneTenantId: tenantPath,
      productCode: this.config.productCode,
      appliedVersion: a?.version ?? null,
      appliedChecksum: a?.checksum ?? null,
      appliedAt: a?.appliedAt ?? null,
      status: a?.status ?? 'NONE',
      unknownCapabilities: a?.unknownCapabilities ?? [],
      enforcementMode: this.config.enforcementMode,
    };
    return { status: 200, body: body as unknown as Record<string, unknown> };
  }

  /**
   * Decisión de enforcement: SOLO con el último snapshot aplicado (last-good),
   * sin llamar a MasterAdmin. Una capacidad desconocida nunca se concede.
   */
  isEntitled(tenant: string, code: string, companyId?: string): boolean {
    const a = this.applied.get(tenant);
    if (!a || !a.snapshot.appActive) return false;
    if (this.config.baselineCapabilities.includes(code)) return true;
    if (!this.config.knownCapabilities.includes(code)) return false;
    const cap = a.snapshot.capabilities.find((c) => c.code === code);
    if (!cap?.enabled) return false;
    if (cap.scope.level === 'COMPANY' && cap.scope.companyIds && companyId !== undefined) {
      return cap.scope.companyIds.includes(companyId);
    }
    return true;
  }

  limit(tenant: string, code: string): number | null {
    const a = this.applied.get(tenant);
    if (!a || !a.snapshot.appActive || !this.config.knownCapabilities.includes(code)) return null;
    return a.snapshot.limits.find((l) => l.code === code)?.value ?? null;
  }
}
