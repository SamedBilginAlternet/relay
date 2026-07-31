import { ApiRunSource } from './ApiRunSource';
import { MockRunSource } from './MockRunSource';
import type { RunSource } from './RunSource';

/**
 * IMPORTANT: read env with `||`, never `??`.
 * Docker build args arrive as EMPTY STRINGS ("") when unset, and `??` would
 * happily accept "" as a real value — leaving the app pointed at nothing.
 */
const rawSource = (import.meta.env.VITE_RUN_SOURCE || 'mock').toLowerCase();
export const RUN_SOURCE_KIND: 'api' | 'mock' = rawSource === 'api' ? 'api' : 'mock';
export const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL || '/api';

let instance: RunSource | null = null;

export function getRunSource(): RunSource {
  if (!instance) {
    instance = RUN_SOURCE_KIND === 'api' ? new ApiRunSource(API_BASE_URL) : new MockRunSource();
  }
  return instance;
}

export type { RunSource, RunStreamHandlers, StreamStatus, Unsubscribe } from './RunSource';
