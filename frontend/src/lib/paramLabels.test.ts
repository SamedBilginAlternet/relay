import { describe, expect, it } from 'vitest';
import { paramLabel } from './paramLabels';

/**
 * Why this test exists.
 *
 * The approval gate is the one screen the pitch is built around, and for a while
 * it asked people to approve a box labelled `İSSUETYPE` — the raw API field name
 * `issueType`, uppercased by CSS on a `lang="tr"` page, where `i` maps to the
 * dotted `İ`. Two separate mistakes stacked: showing a machine identifier to a
 * team lead at all, and running a locale-aware text transform over data.
 *
 * Deleting these assertions means one of two things came back: either a field a
 * person is asked to approve stopped having a human name, or an unrecognised key
 * started being reshaped before it is shown — and a label that does not match
 * what will be sent is the one thing an approval gate cannot afford.
 */
describe('paramLabel', () => {
  it('gives the approval gate a human noun for every field the demo shows', () => {
    expect(paramLabel('projectKey')).toBe('Proje');
    expect(paramLabel('summary')).toBe('Başlık');
    expect(paramLabel('issueType')).toBe('Konu türü');
    expect(paramLabel('channel')).toBe('Kanal');
    expect(paramLabel('text')).toBe('Mesaj');
    expect(paramLabel('status')).toBe('Durum');
    expect(paramLabel('to')).toBe('Kime');
    expect(paramLabel('subject')).toBe('Konu');
  });

  it('param_labels_are_not_uppercased_field_names', () => {
    // The exact regression: Turkish uppercasing turned `issueType` into `İSSUETYPE`.
    expect('issueType'.toLocaleUpperCase('tr-TR')).toBe('İSSUETYPE');

    for (const key of ['projectKey', 'issueType', 'description', 'title', 'inReplyTo']) {
      const label = paramLabel(key);
      expect(label).not.toContain('İ');
      expect(label).not.toBe(key.toLocaleUpperCase('tr-TR'));
    }
  });

  it('leaves a field it does not know exactly as the model wrote it', () => {
    // No uppercasing, no prettifying: the label must match what gets sent.
    expect(paramLabel('inventedByTheModel')).toBe('inventedByTheModel');
    expect(paramLabel('idempotencyKey')).toBe('idempotencyKey');
  });

  it('matches the schemas even where they disagree with themselves', () => {
    // Jira's own tool spells it both ways.
    expect(paramLabel('issuetype')).toBe(paramLabel('issueType'));
  });
});
