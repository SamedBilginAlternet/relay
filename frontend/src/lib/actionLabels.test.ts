import { describe, expect, it } from 'vitest';
import { ACTION_LABELS, actionLabel } from './actionLabels';

/**
 * Why this test exists.
 *
 * Four consecutive loads of the same Bugün screen, same session, same records:
 * the urgent mail's button read "Jira issue oluştur", then "Jira'ya aç", then
 * "Jira kaydı aç"; the pull request read "İncele ve yorumla", then "Review
 * iste". Nothing about the day had changed — the label was coming out of the
 * model, and the model writes it again every time.
 *
 * That is a product with no vocabulary. Nobody can learn a button that renames
 * itself, put it in a support note, or say it out loud to a colleague and be
 * understood. "Review iste" also put an English word on a Turkish screen.
 *
 * Deleting these assertions means the wording went back to the generator.
 */
describe('actionLabel', () => {
  it('a_record_always_gets_the_same_button_name', () => {
    // The three names the same mail's button actually wore, one after another.
    for (const written of ['Jira issue oluştur', 'Jira’ya aç', 'Jira kaydı aç']) {
      expect(actionLabel('jira.createIssue', written)).toBe('Jira kaydı aç');
    }
    // …and the two the same pull request wore.
    for (const written of ['İncele ve yorumla', 'Review iste']) {
      expect(actionLabel('github.addComment', written)).toBe('İncele ve yorumla');
    }
  });

  it('names every tool the brief is allowed to suggest', () => {
    // The registry the backend advertises to the classifier. A tool missing
    // from the dictionary falls through to the model's wording, which is the
    // bug this file exists to stop.
    const registered = [
      'jira.createIssue',
      'jira.updateIssue',
      'jira.addComment',
      'jira.getComments',
      'jira.getIssue',
      'jira.searchIssues',
      'jira.listMyIssues',
      'gmail.createDraft',
      'gmail.getMessage',
      'gmail.search',
      'gmail.listToday',
      'github.addComment',
      'github.listMyPullRequests',
      'github.listMyIssues',
      'notion.createPage',
      'slack.postMessage',
      'slack.listChannels',
      'calendar.listToday',
      'calendar.listUpcoming',
      'calendar.createEvent',
    ];
    for (const tool of registered) {
      expect(actionLabel(tool, 'model uydurdu')).not.toBe('model uydurdu');
    }
  });

  it('button_names_have_no_english_words', () => {
    // Brand names are the only foreign words allowed: they are what the thing
    // is called. Everything else is Turkish.
    const brands = /\b(jira|slack|github|gmail)\b/gi;
    const english =
      /\b(review|issue|ticket|comment|create|open|reply|draft|pull|request|update|search|list|send|message|task|meeting|prepare|assign)\b/i;

    for (const [tool, label] of Object.entries(ACTION_LABELS)) {
      expect(label.replace(brands, ''), tool).not.toMatch(english);
    }
  });

  it('is a finite list, and the interface owns every entry', () => {
    const labels = Object.values(ACTION_LABELS);
    expect(labels.length).toBeGreaterThan(0);
    // No duplicates: two different tools sharing a name is the same confusion
    // from the other direction.
    expect(new Set(labels).size).toBe(labels.length);
    for (const label of labels) {
      expect(label.trim()).toBe(label);
      // A specific verb, not a tool id leaking through (DESIGN.md §3).
      expect(label).not.toMatch(/\./);
    }
  });

  it('keeps the model’s words for a tool it has never heard of', () => {
    expect(actionLabel('linear.createIssue', 'Linear kaydı aç')).toBe('Linear kaydı aç');
  });
});
