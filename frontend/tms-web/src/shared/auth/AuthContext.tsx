import type { Session } from "@supabase/supabase-js";
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import {
  onApiResponseError,
  setAuthRefreshHandler,
  setAuthTokenProvider,
  type ApiError,
} from "../api/httpClient";
import { isAuthProblem } from "../api/problemMessages";
import { supabase } from "./supabaseClient";

export type AuthStatus = "loading" | "signedOut" | "signedIn";

export interface AuthUser {
  id: string;
  email: string | null;
}

export interface SignInResult {
  ok: boolean;
  message?: string;
}

interface AuthContextValue {
  status: AuthStatus;
  user: AuthUser | null;
  signIn(email: string, password: string): Promise<SignInResult>;
  signOut(): Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

function toAuthUser(session: Session | null): AuthUser | null {
  if (!session?.user) return null;
  return { id: session.user.id, email: session.user.email ?? null };
}

/**
 * Abstracción de Supabase Auth: login, logout y refresh de sesión viven aquí y en ningún otro
 * sitio. `httpClient` obtiene su bearer token por {@link setAuthTokenProvider}; las pantallas
 * de negocio nunca tocan el cliente de Supabase (regla V1: Supabase es solo autenticación).
 *
 * La sesión se refleja en un ref para poder leer el token de forma síncrona. Preguntar
 * `supabase.auth.getSession()` una vez por petición hacía que el token llegara un await (y una
 * adquisición de `navigator.locks`) más tarde que el estado `signedIn` al que pertenece: la
 * primera petición tras un login podía salir sin cabecera `Authorization`, recoger el 401 del
 * backend y tirar abajo una sesión perfectamente válida. Guardar la sesión aquí elimina el
 * problema de orden — `status` y el token que le corresponde se publican en el mismo commit.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>("loading");
  const [user, setUser] = useState<AuthUser | null>(null);

  /** La sesión con la que se autentica cada petición saliente. */
  const sessionRef = useRef<Session | null>(null);
  // Protege del bucle signOut → 401 → signOut: una vez en marcha un cierre de sesión forzado,
  // se ignoran los fallos de auth de las peticiones en vuelo hasta que llegue una sesión nueva.
  const signingOutRef = useRef(false);

  const applySession = useCallback((session: Session | null) => {
    sessionRef.current = session;
    if (session) signingOutRef.current = false;
    setUser(toAuthUser(session));
    setStatus(session ? "signedIn" : "signedOut");
  }, []);

  /**
   * La recuperación controlada que `httpClient` invoca tras un fallo de autenticación.
   * Devolver un token significa "reproduce la petición una vez"; devolver `null` significa que
   * la sesión se fue y quien llamó debe hacer aflorar el fallo, que es lo que acaba cerrando
   * la sesión del usuario.
   */
  const refreshAccessToken = useCallback(async (): Promise<string | null> => {
    try {
      const { data, error } = await supabase.auth.refreshSession();
      if (error || !data?.session) return null;
      applySession(data.session);
      return data.session.access_token ?? null;
    } catch {
      return null;
    }
  }, [applySession]);

  useEffect(() => {
    let active = true;

    // Se registran antes de resolver la sesión. Nada puede lanzar una petición de negocio
    // hasta que `status` sea `signedIn`, y eso solo ocurre más abajo, así que el proveedor
    // siempre está puesto cuando se construye la primera petición.
    setAuthTokenProvider(() => sessionRef.current?.access_token ?? null);
    setAuthRefreshHandler(refreshAccessToken);

    const { data: listener } = supabase.auth.onAuthStateChange((_event, session) => {
      if (!active) return;
      applySession(session);
    });

    void supabase.auth.getSession().then(({ data }) => {
      // Un login puede haber publicado ya una sesión más nueva mientras esto estaba en vuelo;
      // la almacenada no debe pisarla.
      if (!active || sessionRef.current) return;
      applySession(data.session ?? null);
    });

    return () => {
      active = false;
      listener.subscription.unsubscribe();
    };
  }, [applySession, refreshAccessToken]);

  useEffect(() => {
    return onApiResponseError((error: ApiError) => {
      // `httpClient` reporta un fallo de autenticación solo cuando su refresh+reintento ya
      // falló, así que llegar aquí significa que la sesión no se pudo recuperar. Un fallo sin
      // sesión en mano no es cosa nuestra: no se arregla cerrando sesión.
      if (!isAuthProblem(error) || signingOutRef.current || sessionRef.current === null) return;
      signingOutRef.current = true;
      sessionRef.current = null;
      void supabase.auth.signOut();
    });
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      status,
      user,
      async signIn(email, password) {
        const { data, error } = await supabase.auth.signInWithPassword({ email, password });
        if (error) return { ok: false, message: error.message };
        // Adopta la sesión de la llamada que la produjo en vez de esperar a
        // `onAuthStateChange`. Los dos se disparan, pero solo este orden garantiza que el
        // token es legible en el instante en que `status` pasa a `signedIn`.
        if (data?.session) applySession(data.session);
        return { ok: true };
      },
      async signOut() {
        signingOutRef.current = true;
        sessionRef.current = null;
        await supabase.auth.signOut();
        setUser(null);
        setStatus("signedOut");
      },
    }),
    [applySession, status, user],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth debe usarse dentro de un AuthProvider");
  return context;
}
