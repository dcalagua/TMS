import { isCarrierScoped, type MeView } from "../api/meApi";

/**
 * La última respuesta de `GET /me` de un usuario de transportista, para que el portal abra sin
 * señal (ADR-019 §1).
 *
 * Sin ella, un arranque sin red se queda en «Cargando tus compañías...» para siempre: la consulta
 * de `/me` espera a que haya red, y el portal no sabe ni qué empresa ni qué transportista es.
 * Con ella, `CompanyProvider` la usa como dato provisional mientras `/me` no responde; cuando
 * responde, manda la respuesta real.
 *
 * Solo se guarda para quien opera como transportista: la oficina no trabaja sin red y no tiene
 * por qué dejar nada en el teléfono. Es del usuario que la leyó: otro usuario no la ve, y se borra
 * al cerrar sesión. El backend sigue validando cada petición: esto solo decide qué se pinta.
 */

const KEY = "tms.portal.me";

interface StoredCopy {
  userId: string;
  me: MeView;
}

export function readMeCopy(userId: string | null | undefined): MeView | undefined {
  if (!userId) return undefined;
  try {
    const raw = window.localStorage.getItem(KEY);
    if (!raw) return undefined;
    const copy = JSON.parse(raw) as StoredCopy;
    return copy.userId === userId && Array.isArray(copy.me?.companies) ? copy.me : undefined;
  } catch {
    return undefined;
  }
}

/** Guarda la copia si el usuario es de transportista; si no, borra cualquiera que hubiera. */
export function writeMeCopy(userId: string, me: MeView): void {
  try {
    if (me.companies.some((company) => isCarrierScoped(company))) {
      window.localStorage.setItem(KEY, JSON.stringify({ userId, me } satisfies StoredCopy));
    } else {
      window.localStorage.removeItem(KEY);
    }
  } catch {
    /* sin almacenamiento: el portal simplemente no abre sin señal */
  }
}

/** Borra la copia; con `exceptUserId`, solo si es de otro usuario (al entrar otro en el teléfono). */
export function clearMeCopy(exceptUserId?: string): void {
  try {
    if (exceptUserId) {
      const raw = window.localStorage.getItem(KEY);
      if (!raw || (JSON.parse(raw) as StoredCopy).userId === exceptUserId) return;
    }
    window.localStorage.removeItem(KEY);
  } catch {
    /* noop */
  }
}
