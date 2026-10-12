import { useRef, useState, type PointerEvent } from "react";
import { Box, Button, Typography } from "@mui/material";
import { DeleteOutlineRounded } from "@mui/icons-material";
import { t } from "../../lib/i18n";
import { R } from "../../theme";

interface SignaturePadProps {
  /** Se llama con el PNG al terminar cada trazo, y con `null` al borrar. */
  onChange: (signature: Blob | null) => void;
  disabled?: boolean;
  /** Alto del lienzo en píxeles CSS. El ancho es el del contenedor. */
  height?: number;
  label?: string;
}

/**
 * Un recuadro para firmar con el dedo, exportado como PNG.
 *
 * Escrito aquí y no traído de una librería: son cuarenta líneas de Pointer Events sobre un
 * `<canvas>`, y una dependencia para eso es más superficie que código.
 *
 * El contexto 2D se pide en el primer trazo y no al montar. Así el lienzo se dimensiona con su
 * tamaño real en pantalla (y la densidad de píxeles del teléfono, para que la firma no salga
 * borrosa), y un entorno sin canvas —las pruebas en jsdom— nunca lo pide.
 */
export function SignaturePad({ onChange, disabled = false, height = 160, label }: SignaturePadProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const contextRef = useRef<CanvasRenderingContext2D | null>(null);
  const drawingRef = useRef(false);
  const [empty, setEmpty] = useState(true);

  function context(): CanvasRenderingContext2D | null {
    const canvas = canvasRef.current;
    if (!canvas) return null;
    if (contextRef.current) return contextRef.current;
    const ratio = window.devicePixelRatio || 1;
    const rect = canvas.getBoundingClientRect();
    canvas.width = Math.max(1, Math.round(rect.width * ratio));
    canvas.height = Math.max(1, Math.round(rect.height * ratio));
    const ctx = canvas.getContext("2d");
    if (!ctx) return null;
    ctx.scale(ratio, ratio);
    ctx.lineWidth = 2.2;
    ctx.lineCap = "round";
    ctx.lineJoin = "round";
    ctx.strokeStyle = "#0F1B1C";
    contextRef.current = ctx;
    return ctx;
  }

  function point(event: PointerEvent<HTMLCanvasElement>) {
    const rect = event.currentTarget.getBoundingClientRect();
    return { x: event.clientX - rect.left, y: event.clientY - rect.top };
  }

  function start(event: PointerEvent<HTMLCanvasElement>) {
    if (disabled) return;
    const ctx = context();
    if (!ctx) return;
    event.currentTarget.setPointerCapture?.(event.pointerId);
    drawingRef.current = true;
    const { x, y } = point(event);
    ctx.beginPath();
    ctx.moveTo(x, y);
    // Un toque sin arrastre también deja marca: un punto.
    ctx.lineTo(x + 0.1, y + 0.1);
    ctx.stroke();
  }

  function move(event: PointerEvent<HTMLCanvasElement>) {
    if (!drawingRef.current) return;
    const ctx = contextRef.current;
    if (!ctx) return;
    const { x, y } = point(event);
    ctx.lineTo(x, y);
    ctx.stroke();
  }

  function end() {
    if (!drawingRef.current) return;
    drawingRef.current = false;
    setEmpty(false);
    canvasRef.current?.toBlob((blob) => onChange(blob), "image/png");
  }

  function clear() {
    const canvas = canvasRef.current;
    const ctx = contextRef.current;
    if (canvas && ctx) {
      ctx.save();
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      ctx.restore();
    }
    setEmpty(true);
    onChange(null);
  }

  return (
    <Box>
      <Box sx={{
        position: "relative", height, borderRadius: `${R.md}px`, border: "1px dashed", borderColor: "divider",
        bgcolor: "#FFFFFF", overflow: "hidden", opacity: disabled ? 0.6 : 1,
      }}>
        <Box
          component="canvas"
          ref={canvasRef}
          role="img"
          aria-label={label ?? t("Recuadro de firma")}
          onPointerDown={start}
          onPointerMove={move}
          onPointerUp={end}
          onPointerLeave={end}
          onPointerCancel={end}
          sx={{ width: "100%", height: "100%", display: "block", touchAction: "none", cursor: disabled ? "default" : "crosshair" }}
        />
        {empty && (
          <Typography variant="body2" sx={{
            position: "absolute", inset: 0, display: "grid", placeItems: "center", pointerEvents: "none",
            color: "#5C6B6C",
          }}>
            {t("Firma aquí con el dedo")}
          </Typography>
        )}
      </Box>
      <Box sx={{ display: "flex", justifyContent: "flex-end", mt: 0.5 }}>
        <Button size="small" startIcon={<DeleteOutlineRounded />} onClick={clear} disabled={disabled || empty}>
          {t("Borrar firma")}
        </Button>
      </Box>
    </Box>
  );
}
