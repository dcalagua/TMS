import { byCreatedAt, snapshotId, type OfflineStore, type OutboxEntry, type Snapshot } from "./types";

/**
 * IndexedDB sin librerías: dos almacenes (`snapshots` y `outbox`), los dos con índice por
 * usuario, y unas pocas promesas sobre la API de peticiones y transacciones.
 *
 * Los blobs de las fotos y la firma se guardan tal cual (clonado estructurado): nada de base64,
 * que triplicaría lo que ocupa una cola de cinco fotos.
 */

const DB_NAME = "etms-portal";
const DB_VERSION = 1;
const SNAPSHOTS = "snapshots";
const OUTBOX = "outbox";
const BY_USER = "userId";

function promisify<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

function done(tx: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error ?? new Error("IndexedDB transaction aborted"));
  });
}

function open(factory: IDBFactory): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = factory.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(SNAPSHOTS)) {
        db.createObjectStore(SNAPSHOTS, { keyPath: "id" }).createIndex(BY_USER, "userId");
      }
      if (!db.objectStoreNames.contains(OUTBOX)) {
        db.createObjectStore(OUTBOX, { keyPath: "id" }).createIndex(BY_USER, "userId");
      }
    };
    request.onsuccess = () => {
      const db = request.result;
      // Otra pestaña con una versión más nueva del esquema: se cede la conexión.
      db.onversionchange = () => db.close();
      resolve(db);
    };
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error("IndexedDB blocked"));
  });
}

/** Recorre un almacén borrando lo que cumpla el predicado, en una sola transacción. */
async function deleteWhere<T>(db: IDBDatabase, storeName: string, predicate: (value: T) => boolean): Promise<void> {
  const tx = db.transaction(storeName, "readwrite");
  const request = tx.objectStore(storeName).openCursor();
  request.onsuccess = () => {
    const cursor = request.result;
    if (!cursor) return;
    if (predicate(cursor.value as T)) cursor.delete();
    cursor.continue();
  };
  await done(tx);
}

export function createIndexedDbStore(factory: IDBFactory = indexedDB): OfflineStore {
  let connection: Promise<IDBDatabase> | null = null;
  const db = () => {
    connection ??= open(factory).catch((error: unknown) => {
      connection = null;
      throw error;
    });
    return connection;
  };

  return {
    async getSnapshot<T>(userId: string, companyId: string, key: string) {
      const tx = (await db()).transaction(SNAPSHOTS, "readonly");
      const value = await promisify(tx.objectStore(SNAPSHOTS).get(snapshotId(userId, companyId, key)));
      return (value as Snapshot<T> | undefined) ?? null;
    },
    async putSnapshot<T>(userId: string, companyId: string, key: string, data: T, savedAt = new Date().toISOString()) {
      const tx = (await db()).transaction(SNAPSHOTS, "readwrite");
      const snapshot: Snapshot<T> = { id: snapshotId(userId, companyId, key), userId, companyId, key, data, savedAt };
      tx.objectStore(SNAPSHOTS).put(snapshot);
      await done(tx);
    },
    async listOutbox(userId: string) {
      const tx = (await db()).transaction(OUTBOX, "readonly");
      const rows = await promisify(tx.objectStore(OUTBOX).index(BY_USER).getAll(IDBKeyRange.only(userId)));
      return (rows as OutboxEntry[]).sort(byCreatedAt);
    },
    async getOutbox(id: string) {
      const tx = (await db()).transaction(OUTBOX, "readonly");
      return ((await promisify(tx.objectStore(OUTBOX).get(id))) as OutboxEntry | undefined) ?? null;
    },
    async putOutbox(entry: OutboxEntry) {
      const tx = (await db()).transaction(OUTBOX, "readwrite");
      tx.objectStore(OUTBOX).put(entry);
      await done(tx);
    },
    async deleteOutbox(id: string) {
      const tx = (await db()).transaction(OUTBOX, "readwrite");
      tx.objectStore(OUTBOX).delete(id);
      await done(tx);
    },
    async wipeUser(userId: string) {
      const database = await db();
      await deleteWhere<{ userId: string }>(database, SNAPSHOTS, (value) => value.userId === userId);
      await deleteWhere<{ userId: string }>(database, OUTBOX, (value) => value.userId === userId);
    },
    async wipeOtherUsers(userId: string) {
      const database = await db();
      await deleteWhere<{ userId: string }>(database, SNAPSHOTS, (value) => value.userId !== userId);
      await deleteWhere<{ userId: string }>(database, OUTBOX, (value) => value.userId !== userId);
    },
    async pruneSnapshots(olderThan: string) {
      await deleteWhere<Snapshot>(await db(), SNAPSHOTS, (value) => value.savedAt < olderThan);
    },
  };
}
