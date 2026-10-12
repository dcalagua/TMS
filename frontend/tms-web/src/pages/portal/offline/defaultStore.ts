import { createIndexedDbStore } from "./idbStore";
import { createMemoryStore } from "./memoryStore";
import type { OfflineStore } from "./types";

let shared: OfflineStore | null = null;

/** IndexedDB donde existe; en memoria donde no (jsdom, navegadores que lo bloquean). */
export function defaultOfflineStore(): OfflineStore {
  shared ??= typeof indexedDB !== "undefined" && indexedDB !== null ? createIndexedDbStore(indexedDB) : createMemoryStore();
  return shared;
}
