import { byCreatedAt, snapshotId, type OfflineStore, type OutboxEntry, type Snapshot } from "./types";

/**
 * El almacén en memoria: el de las pruebas y el de reserva donde no hay IndexedDB. Pierde todo
 * al recargar, que es exactamente lo que se espera de él.
 */
export function createMemoryStore(): OfflineStore & {
  snapshots: Map<string, Snapshot>;
  outbox: Map<string, OutboxEntry>;
} {
  const snapshots = new Map<string, Snapshot>();
  const outbox = new Map<string, OutboxEntry>();

  return {
    snapshots,
    outbox,
    async getSnapshot<T>(userId: string, companyId: string, key: string) {
      return (snapshots.get(snapshotId(userId, companyId, key)) as Snapshot<T> | undefined) ?? null;
    },
    async putSnapshot<T>(userId: string, companyId: string, key: string, data: T, savedAt = new Date().toISOString()) {
      const id = snapshotId(userId, companyId, key);
      snapshots.set(id, { id, userId, companyId, key, data, savedAt });
    },
    async listOutbox(userId: string) {
      return [...outbox.values()].filter((entry) => entry.userId === userId).map((entry) => ({ ...entry })).sort(byCreatedAt);
    },
    async getOutbox(id: string) {
      const entry = outbox.get(id);
      return entry ? { ...entry } : null;
    },
    async putOutbox(entry: OutboxEntry) {
      outbox.set(entry.id, { ...entry });
    },
    async deleteOutbox(id: string) {
      outbox.delete(id);
    },
    async wipeUser(userId: string) {
      for (const [id, snapshot] of snapshots) if (snapshot.userId === userId) snapshots.delete(id);
      for (const [id, entry] of outbox) if (entry.userId === userId) outbox.delete(id);
    },
    async wipeOtherUsers(userId: string) {
      for (const [id, snapshot] of snapshots) if (snapshot.userId !== userId) snapshots.delete(id);
      for (const [id, entry] of outbox) if (entry.userId !== userId) outbox.delete(id);
    },
    async pruneSnapshots(olderThan: string) {
      for (const [id, snapshot] of snapshots) if (snapshot.savedAt < olderThan) snapshots.delete(id);
    },
  };
}
