/**
 * `POST /api/ask` — a question about the mailbox, answered with sources.
 *
 * Read-only: this endpoint searches Gmail and returns text. It never starts a run,
 * so nothing here passes through the approval gate — there is nothing to approve.
 *
 * The shape is deliberately wide because the *trace* is the product: the query that
 * was actually run, where that query came from, whether a model wrote the answer,
 * and which mails it was allowed to lean on. An answer without those is a claim.
 */

/** `ok` carries an answer; the other three carry an explanation and no sources. */
export type AskStatus = 'ok' | 'empty' | 'unavailable' | 'error';

/** Who wrote `answer`: the model, a plain listing of the hits, or nobody. */
export type AskAnswerSource = 'llm' | 'listing' | 'none';

/** Where `query` came from: the model, or the built-in rules when it is unavailable. */
export type AskQuerySource = 'llm' | 'heuristic';

/** One mail the answer is allowed to lean on. `[1]` in the text is `sources[0]`. */
export type AskSourceItem = {
  id: string;
  subject: string;
  from: string;
  /** ISO instant the mail arrived. */
  at: string;
  /** Deep link into Gmail. */
  url: string;
};

export type AskAnswer = {
  question: string;
  /** The Gmail query that actually ran. Shown verbatim — it is the receipt. */
  query: string;
  queryExplanation: string;
  querySource: AskQuerySource;
  status: AskStatus;
  answer: string;
  answerSource: AskAnswerSource;
  sources: AskSourceItem[];
  resultCount: number;
  /** `live`, `replay`… — how the Gmail call was served. */
  mode?: string | null;
  tokens: number;
  costUsd: number;
};
