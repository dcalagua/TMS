import { useRef, useState, type TouchEvent } from "react";
import { haptic } from "./offline/network";

/**
 * Tirar hacia abajo para recargar, con eventos táctiles y nada más.
 *
 * Solo arranca con la página arriba del todo; el recorrido se amortigua a la mitad para que se
 * sienta como un tirón y no como un desplazamiento. El navegador no hace el suyo porque el portal
 * pone `overscroll-behavior-y: contain` (PortalLayout).
 */
export function usePullToRefresh(onRefresh: () => Promise<unknown>, { threshold = 72, disabled = false } = {}) {
  const [pull, setPull] = useState(0);
  const [refreshing, setRefreshing] = useState(false);
  const startY = useRef<number | null>(null);
  const pullRef = useRef(0);

  function setDistance(value: number) {
    pullRef.current = value;
    setPull(value);
  }

  const handlers = {
    onTouchStart(event: TouchEvent) {
      if (disabled || refreshing || window.scrollY > 0 || event.touches.length !== 1) return;
      startY.current = event.touches[0].clientY;
    },
    onTouchMove(event: TouchEvent) {
      if (startY.current === null) return;
      const delta = event.touches[0].clientY - startY.current;
      if (delta <= 0 || window.scrollY > 0) { setDistance(0); return; }
      const next = Math.min(threshold * 1.6, delta * 0.5);
      if (pullRef.current < threshold && next >= threshold) haptic(10);
      setDistance(next);
    },
    onTouchEnd: release,
    onTouchCancel: release,
  };

  function release() {
    if (startY.current === null) return;
    startY.current = null;
    const reached = pullRef.current >= threshold;
    setDistance(0);
    if (!reached) return;
    setRefreshing(true);
    void onRefresh().catch(() => undefined).finally(() => setRefreshing(false));
  }

  return { pull, refreshing, progress: Math.min(1, pull / threshold), handlers };
}
