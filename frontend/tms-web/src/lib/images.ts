/**
 * Reducción de fotos en el teléfono antes de subirlas.
 *
 * Una foto de la cámara de un móvil pesa entre 3 y 8 MB, y un reporte admite cinco: subidas así
 * por datos móviles desde la puerta de un cliente no llegan, o llegan tarde, y el servidor acepta
 * como mucho 6 MB por fichero y 32 MB por petición. Una foto de 1600 px de lado mayor en JPEG al
 * 80 % sigue dejando leer un sello o una firma y pesa unos cientos de KB.
 *
 * Si el navegador no puede decodificar la imagen (o no hay canvas, como en las pruebas), se queda
 * el original: reducir es una optimización, y el formulario valida después tipo y tamaño con
 * {@link photoProblem} para decirlo antes de subir en vez de recoger un 400.
 */
export const PHOTO_MAX_EDGE = 1600;
export const PHOTO_JPEG_QUALITY = 0.8;
/** El límite por fichero del servidor (ADR-006 / multipart). */
export const MAX_UPLOAD_FILE_BYTES = 6 * 1024 * 1024;
/** Los tipos de imagen que el almacén de evidencias acepta. */
export const ACCEPTED_IMAGE_TYPES: readonly string[] = ["image/jpeg", "image/png", "image/webp"];

export async function downscalePhoto(file: File, maxEdge = PHOTO_MAX_EDGE): Promise<File> {
  if (!file.type.startsWith("image/") || typeof createImageBitmap !== "function") return file;
  try {
    const bitmap = await createImageBitmap(file);
    const scale = Math.min(1, maxEdge / Math.max(bitmap.width, bitmap.height));
    const width = Math.max(1, Math.round(bitmap.width * scale));
    const height = Math.max(1, Math.round(bitmap.height * scale));
    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    const ctx = canvas.getContext("2d");
    if (!ctx) { bitmap.close?.(); return file; }
    ctx.drawImage(bitmap, 0, 0, width, height);
    bitmap.close?.();
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, "image/jpeg", PHOTO_JPEG_QUALITY));
    if (!blob) return file;
    // Una captura pequeña ya comprimida puede crecer al reencodarse: entonces se queda la original,
    // salvo que la original sea de un tipo que el servidor no acepta (HEIC, por ejemplo).
    const originalAccepted = ACCEPTED_IMAGE_TYPES.includes(file.type);
    if (originalAccepted && scale === 1 && blob.size >= file.size) return file;
    const name = file.name.replace(/\.[^.]+$/, "") || "foto";
    return new File([blob], `${name}.jpg`, { type: "image/jpeg", lastModified: file.lastModified });
  } catch {
    return file;
  }
}

/** Por qué el servidor rechazaría esta foto, o `null` si la aceptará. */
export function photoProblem(file: Blob): "type" | "size" | null {
  if (!ACCEPTED_IMAGE_TYPES.includes(file.type)) return "type";
  if (file.size > MAX_UPLOAD_FILE_BYTES) return "size";
  return null;
}
