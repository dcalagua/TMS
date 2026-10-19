import { keepPreviousData, useQuery } from "@tanstack/react-query";
import {
  fetchMaterialProfile, fetchMaterialProfiles, type MaterialProfileView, type StackMode,
} from "../../shared/api/materialProfilesApi";
import { enumLabel } from "../../lib/enums";
import { t } from "../../lib/i18n";
import { fmtDecimal } from "../../lib/locale";

/** El permiso que lee los perfiles; forma parte de la capability MASTER_DATA_VIEW. */
export const MATERIAL_PROFILE_READ = "masterdata.material_profile:read";

/** Prefijo de todas las lecturas de perfiles de una empresa: lista y detalle. */
export const materialProfilesKey = (companyId: string) => ["material-profiles", companyId] as const;
export const materialProfileDetailKey = (companyId: string, id: string) =>
  [...materialProfilesKey(companyId), "detail", id] as const;

export interface MaterialProfileListQuery {
  companyId: string;
  search: string;
  page: number;
  size: number;
  enabled: boolean;
}

/** La lista paginada, ordenada por código como la entrega el backend por defecto. */
export function useMaterialProfiles({ companyId, search, page, size, enabled }: MaterialProfileListQuery) {
  const term = search.trim();
  return useQuery({
    queryKey: [...materialProfilesKey(companyId), "list", term, page, size],
    queryFn: ({ signal }) => fetchMaterialProfiles({
      companyId, search: term || undefined, page, size, sort: "materialCode,asc", signal,
    }),
    enabled: enabled && companyId !== "",
    placeholderData: keepPreviousData,
  });
}

/** El detalle de un perfil, sembrado con la fila de la lista para que el drawer abra lleno. */
export function useMaterialProfile(companyId: string, row: MaterialProfileView) {
  return useQuery({
    queryKey: materialProfileDetailKey(companyId, row.id),
    queryFn: ({ signal }) => fetchMaterialProfile(companyId, row.id, signal),
    placeholderData: row,
  });
}

/** El modo de apilado en palabras; `null` es que el almacén no lo declaró. */
export function stackModeLabel(mode: StackMode | null): string {
  return mode === null ? t("No declarado") : enumLabel("stackMode", mode);
}

/** Largo × ancho × alto en metros; un lado sin medir queda como guion. */
export function dimensionsLabel(profile: Pick<MaterialProfileView, "lengthM" | "widthM" | "heightM">): string {
  const sides = [profile.lengthM, profile.widthM, profile.heightM];
  if (sides.every((side) => side === null)) return "-";
  return `${sides.map((side) => fmtDecimal(side, 3)).join(" × ")} m`;
}

/** Una longitud en metros con su unidad, o guion. */
export function fmtMeters(value: number | null | undefined): string {
  const amount = fmtDecimal(value, 3);
  return amount === "-" ? amount : `${amount} m`;
}
