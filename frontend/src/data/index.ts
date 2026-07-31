import { ApiBriefSource } from './ApiBriefSource';
import { ApiRunSource } from './ApiRunSource';
import { ApiAskSource, MockAskSource } from './AskSource';
import type { AskSource } from './AskSource';
import type { BriefSource } from './BriefSource';
import { MockBriefSource } from './MockBriefSource';
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

let briefInstance: BriefSource | null = null;

export function getBriefSource(): BriefSource {
  if (!briefInstance) {
    briefInstance =
      RUN_SOURCE_KIND === 'api'
        ? new ApiBriefSource(API_BASE_URL)
        : // the mock brief hands its suggestions to the same mock engine
          new MockBriefSource(getRunSource());
  }
  return briefInstance;
}

let askInstance: AskSource | null = null;

export function getAskSource(): AskSource {
  if (!askInstance) {
    askInstance = RUN_SOURCE_KIND === 'api' ? new ApiAskSource(API_BASE_URL) : new MockAskSource();
  }
  return askInstance;
}

export type { RunSource, RunStreamHandlers, StreamStatus, Unsubscribe } from './RunSource';
export type { BriefSource } from './BriefSource';
export type { AskSource } from './AskSource';
