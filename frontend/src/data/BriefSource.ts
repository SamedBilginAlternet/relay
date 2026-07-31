import type { Brief, SuggestedAction } from '../types/brief';

/**
 * Everything the Bugün screen is allowed to know about the backend.
 * Same shape of contract as RunSource — components never call `fetch`.
 */
export interface BriefSource {
  readonly kind: 'api' | 'mock';

  /** `GET /api/brief` — cached server-side, partial success allowed. */
  getBrief(): Promise<Brief>;

  /** `POST /api/brief/refresh` — skip the cache, refetch every tool. */
  refreshBrief(): Promise<Brief>;

  /**
   * `POST /api/runs/from-suggestion` — turns a suggested action into a normal
   * Relay run. Same plan, same approval gate, same transparency.
   */
  startFromSuggestion(cardId: string, action: SuggestedAction): Promise<{ runId: string }>;
}
