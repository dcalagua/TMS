import { useQuery, useQueryClient } from "@tanstack/react-query";
import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { t } from "../../lib/i18n";
import { notifyError } from "../../lib/ui";
import { ApiError, onApiResponseError } from "../api/httpClient";
import { fetchMe, type CompanyAccessView, type MeView, type UserView } from "../api/meApi";
import { describeApiError, isCompanyScopeStale, isTenantSuspended } from "../api/problemMessages";
import { useAuth } from "../auth/AuthContext";
import { readMeCopy, writeMeCopy } from "./meOfflineCopy";

const STORAGE_KEY = "tms.selectedCompanyId";

export type CompanyStatus = "idle" | "loading" | "error" | "ready";

interface CompanyContextValue {
  status: CompanyStatus;
  /** El perfil de negocio del usuario, de `GET /api/v1/me`. Distinto del usuario de auth de
   * Supabase: este es quien el backend resolvió en servidor, y trae el nombre que muestra el
   * shell. Null hasta que `/me` haya respondido. */
  profile: UserView | null;
  companies: CompanyAccessView[];
  selected: CompanyAccessView | null;
  selectCompany: (companyId: string) => void;
  hasPermission: (permission: string) => boolean;
  hasCapability: (capability: string) => boolean;
  errorMessage: string | null;
  refetch: () => void;
}

const CompanyContext = createContext<CompanyContextValue | null>(null);

function readStoredCompanyId(): string | null {
  try {
    return window.localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

function storeCompanyId(companyId: string | null): void {
  try {
    if (companyId) window.localStorage.setItem(STORAGE_KEY, companyId);
    else window.localStorage.removeItem(STORAGE_KEY);
  } catch {
    // El almacenamiento puede no estar disponible (modo privado, cookies desactivadas). El
    // selector sigue funcionando en la pestaña actual; simplemente no sobrevive a una recarga.
  }
}

/**
 * Contexto de empresa: la única fuente de verdad sobre en qué empresas puede operar el usuario
 * autenticado. Refleja `GET /api/v1/me` exactamente — aquí no se fabrica ningún acceso de
 * tenant. Elegir una empresa solo cambia qué `X-Company-Id` mandan las peticiones siguientes;
 * el backend valida esa cabecera por su cuenta en cada llamada con ámbito de empresa.
 */
export function CompanyProvider({ children }: { children: ReactNode }) {
  const { status: authStatus, user } = useAuth();
  const userId = user?.id ?? null;
  const queryClient = useQueryClient();
  const [selectedId, setSelectedId] = useState<string | null>(readStoredCompanyId);

  // Limpia la selección en el momento del logout. Se ajusta durante el render (el patrón
  // documentado de React para "resetea estado cuando cambia un valor") en vez de en un efecto,
  // para que un ciclo logout/login no llegue a commitear un frame con la empresa del anterior.
  const [trackedAuthStatus, setTrackedAuthStatus] = useState(authStatus);
  if (authStatus !== trackedAuthStatus) {
    setTrackedAuthStatus(authStatus);
    if (authStatus !== "signedIn" && selectedId !== null) setSelectedId(null);
  }

  const meQuery = useQuery<MeView, ApiError>({
    queryKey: ["me"],
    queryFn: async ({ signal }) => {
      try {
        const me = await fetchMe(signal);
        if (userId) writeMeCopy(userId, me);
        return me;
      } catch (error) {
        // Sin respuesta (la red dice estar pero no llega nada): la copia, si la hay. Una respuesta
        // del servidor, aunque sea un error, manda siempre.
        const copy = !(error instanceof ApiError) && !signal.aborted ? readMeCopy(userId) : undefined;
        if (copy) return copy;
        throw error;
      }
    },
    enabled: authStatus === "signedIn",
    staleTime: 60_000,
    // ADR-019 §1: un usuario de transportista sin señal arranca con su última copia de `/me`
    // (solo existe para ellos; ver `meOfflineCopy`). La respuesta real la sustituye al llegar.
    placeholderData: () => readMeCopy(userId),
  });

  useEffect(() => {
    return onApiResponseError((error: ApiError) => {
      // ADR-021: una escritura rechazada por suspensión refresca `/me`, para que el aviso fijo
      // aparezca aunque la suspensión haya llegado con la sesión ya abierta.
      if (isTenantSuspended(error)) {
        void queryClient.invalidateQueries({ queryKey: ["me"] });
        return;
      }
      if (!isCompanyScopeStale(error)) return;
      notifyError(
        t("Cambió tu acceso a la compañía"),
        t("Ya no tienes acceso a la compañía seleccionada. Elige otra para continuar."),
      );
      setSelectedId(null);
      storeCompanyId(null);
      void queryClient.invalidateQueries({ queryKey: ["me"] });
    });
  }, [queryClient]);

  const companies = useMemo(() => meQuery.data?.companies ?? [], [meQuery.data]);

  const profile = meQuery.data?.user ?? null;

  const selected = useMemo(() => {
    if (companies.length === 0) return null;
    const found = selectedId ? companies.find((company) => company.id === selectedId) : undefined;
    return found ?? companies[0] ?? null;
  }, [companies, selectedId]);

  useEffect(() => {
    storeCompanyId(selected?.id ?? null);
  }, [selected]);

  const status: CompanyStatus =
    authStatus !== "signedIn" ? "idle" : meQuery.isPending ? "loading" : meQuery.isError ? "error" : "ready";

  const value: CompanyContextValue = {
    status,
    profile,
    companies,
    selected,
    selectCompany: setSelectedId,
    hasPermission: (permission) => selected?.permissions.includes(permission) ?? false,
    hasCapability: (capability) => selected?.capabilities.includes(capability) ?? false,
    errorMessage: meQuery.error ? describeApiError(meQuery.error) : null,
    refetch: () => void meQuery.refetch(),
  };

  return <CompanyContext.Provider value={value}>{children}</CompanyContext.Provider>;
}

export function useCompany(): CompanyContextValue {
  const context = useContext(CompanyContext);
  if (!context) throw new Error("useCompany debe usarse dentro de un CompanyProvider");
  return context;
}
