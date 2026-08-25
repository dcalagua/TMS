import { apiRequest } from "./httpClient";

export interface OrganizationView {
  id: string;
  code: string;
  name: string;
}

/**
 * Una empresa en la que quien llama puede operar, reflejo del `CompanyAccessView` del backend.
 * `permissions` y `capabilities` son insumo de UX, no concesiones: esconder o mostrar una
 * entrada de menú a partir de ellos no cambia nada de lo que el backend permite, porque cada
 * endpoint vuelve a comprobar el permiso fino contra la empresa que llega en `X-Company-Id`.
 */
export interface CompanyAccessView {
  id: string;
  code: string;
  name: string;
  timeZone: string;
  organization: OrganizationView;
  permissions: string[];
  capabilities: string[];
}

export interface UserView {
  id: string;
  email: string;
  fullName: string;
}

export interface MeView {
  user: UserView;
  companies: CompanyAccessView[];
}

/** Todo lo necesario justo después del login: perfil, empresas seleccionables y los
 * permisos/capabilities por empresa, en una sola llamada. */
export function fetchMe(signal?: AbortSignal): Promise<MeView> {
  return apiRequest<MeView>("/me", { signal });
}
