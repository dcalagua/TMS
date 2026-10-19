import { apiRequest } from './httpClient'
import type { PageResponse } from './pageResponse'

/**
 * Perfiles de material: las dimensiones, el peso y las reglas de apilado de un material tal como
 * las declara el sistema de almacén (EWM). El almacén es el dueño; TMS los recibe por la API de
 * integración y aquí solo los lee. No hay alta, edición ni baja desde la oficina.
 */

/** Cómo se apila un material. `null` en el perfil significa que el almacén no lo declaró. */
export type StackMode = 'NONE' | 'STACKABLE' | 'NESTABLE'

export const STACK_MODES: StackMode[] = ['NONE', 'STACKABLE', 'NESTABLE']

/** Mirrors the backend's `MaterialProfileView` record. */
export interface MaterialProfileView {
  id: string
  materialCode: string
  description: string | null
  /** El sistema que originó el perfil, p. ej. `EWM_EBIM`. */
  sourceSystem: string
  /** Cuándo lo cambió el almacén (instante ISO con offset). */
  sourceUpdatedAt: string
  lengthM: number | null
  widthM: number | null
  heightM: number | null
  unitWeightKg: number | null
  unitVolumeM3: number | null
  stackMode: StackMode | null
  stackMaxQuantity: number | null
  /** Solo para `NESTABLE`: lo que crece la pila por cada unidad anidada. */
  nestIncrementM: number | null
  maxTopLoadKg: number | null
  orientationLocked: boolean
  version: number
  createdAt: string
  /** Cuándo lo recibió TMS por última vez. */
  updatedAt: string
}

/** Claves de orden que acepta el backend. */
export type MaterialProfileSortKey = 'materialCode' | 'sourceUpdatedAt' | 'updatedAt'

export interface MaterialProfileListParams {
  companyId: string
  /** Contiene, sin distinguir mayúsculas, sobre código o descripción. */
  search?: string
  page?: number
  size?: number
  /** `clave,dirección`, p. ej. `materialCode,asc`. */
  sort?: `${MaterialProfileSortKey},${'asc' | 'desc'}`
  signal?: AbortSignal
}

export function fetchMaterialProfiles(params: MaterialProfileListParams): Promise<PageResponse<MaterialProfileView>> {
  const { companyId, signal, ...query } = params
  return apiRequest<PageResponse<MaterialProfileView>>('/masterdata/material-profiles', { companyId, signal, query })
}

export function fetchMaterialProfile(companyId: string, id: string, signal?: AbortSignal): Promise<MaterialProfileView> {
  return apiRequest<MaterialProfileView>(`/masterdata/material-profiles/${id}`, { companyId, signal })
}
