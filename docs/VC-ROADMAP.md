# Relay — VC Roadmap

## Positioning Statement

> Relay turns every AI agent write action inside a regulated engineering org's tools into a named-approver, audit-ready evidence record — the trust layer compliance-driven engineering teams need before they'll let an agent touch Jira, Slack, GitHub, or Gmail at all.

This replaces Relay's prior positioning ("coordinate the day for team leads") with a narrower, VC-legible wedge: one accountable buyer (an EM/Director of Engineering personally on the hook for SOC 2 / ISO 27001 / KVKK evidence), one quantifiable job (produce audit-ready proof of every agent action's human authorization), and a moat rooted in Relay's actual technical asset — the PolicyEngine, the hard approval gate, and the cross-tool audit trail — not in integration count.

---

## What to Cut

- **Drop "coordinate a team lead's day."** Every deck slide, README line, and demo script should open with the compliance-accountable EM, not a generic team lead.
- **Stop leading with integration count.** "We connect to Jira, Slack, GitHub, Gmail, Calendar" is supporting detail, not the pitch. Lead with the audit trail and what it proves.
- **Reframe, don't retire, the five playbooks.** Daily digest, pre-meeting-prep, blocker-scan, PR-review-reminder, and turn-email-into-ticket stay in the product, but stop pitching them as standalone productivity wins — they are the mechanisms that generate approval records, which are the actual product.
- **Cut the n8n/Zapier competitive frame.** Design-time-vs-run-time is not a distinction a compliance buyer cares about. Drop it from pitch materials entirely.
- **Kill any residual "developer tool" framing.** State explicitly, early, and repeatedly: Relay never writes, compiles, tests, or deploys code. It governs the humans and approvals around engineering work — this directly rebuts the judges' criticism.
- **Fold the budget dashboard into the compliance story.** Cost-per-approved-action is audit-relevant; don't pitch it as a separate "track your AI spend" feature.
- **Defer full multi-tenancy and RBAC.** The compliance wedge needs lightweight per-approver identity only — resist scope creep toward the heavier multi-tenant rebuild that other candidates (e.g., agency billing) would have required.

---

## Phased Roadmap

### Phase 1 — Pitch-Ready Scope Narrowing
**Timeframe:** Weeks 1–2

**Goals:**
- Rebuild all external-facing narrative around the Compliance Trail wedge.
- Validate the JTBD and willingness to pay with real target users before writing more code.
- Sharpen the differentiation line against Vanta/Drata/Secureframe before any investor conversation.

**Work items:**
- Rewrite the pitch deck and one-pager around the new positioning statement; retire "coordinate the day" language everywhere.
- Draft a compliance-buyer-specific one-pager: "audit-ready evidence for AI agent write actions" aimed at SOC 2 / ISO 27001 / KVKK-accountable EMs.
- Run a competitive teardown of Vanta/Drata/Secureframe to lock the differentiation message: Relay proves *agent-action* authorization, it does not replace general compliance-evidence collection.
- Conduct 3–5 user interviews with EMs/Directors of Engineering at TR fintech/healthtech companies to validate the JTBD and pricing tolerance before committing further engineering.
- Identify 5–10 candidate design partners via warm intros through Revo Capital and 212 portfolio companies (both funds have fintech/enterprise-SaaS-heavy portfolios that fit this ICP directly).

### Phase 2 — MVP Build
**Timeframe:** Weeks 2–6

**Goals:**
- Ship the minimum audit trail that is credible as SOC 2 / ISO 27001 / KVKK evidence.
- Close the one real structural gap the current architecture has for this vertical: named individual approver identity.

**Work items:**
- Build lightweight per-approver identity (a named individual, e.g. via Slack user ID — not full RBAC or multi-tenancy). This is the critical unlock for this vertical and is pulled forward from the already-designed **Slack-based remote approval** roadmap item (~8–12h), repurposed here as an identity-capture mechanism as much as a UX improvement.
- Promote **audit-report export** from a roadmap idea to a shipped, core feature — this is the literal deliverable a compliance buyer needs to hand to an auditor.
- Add compliance-framework tagging: map each approval record to specific SOC 2 / ISO 27001 / KVKK control IDs.
- Extend the audit trail schema to capture approver identity + stated reason + linked control tag on every WRITE action.
- Repackage the existing playbooks (blocker-scan, PR-review-reminder, turn-email-into-ticket) explicitly as compliance-evidence generators in product copy and demo flow, not general productivity tools.
- Keep cost metering as-is technically, but reframe its UI copy as "governance cost visibility," consistent with the compliance narrative.

### Phase 3 — Design Partners / GTM
**Timeframe:** Weeks 6–14

**Goals:**
- Land 3–5 TR design partners piloting Relay against a real upcoming audit.
- Produce a quantifiable ROI case study (audit-prep hours saved, re-audit risk avoided).
- Open a channel conversation with the emerging AI-agent insurance/certification category.

**Work items:**
- Recruit design partners from TR neobanks/payment institutions (BDDK-regulated) and TR healthtechs pursuing ISO 27001/KVKK, via Revo Capital and 212 introductions.
- Price and run pilots against a stated ROI metric: hours saved in audit-evidence preparation, and re-audit risk avoided.
- Build a case study/evidence pack from the first completed pilot demonstrating measurable audit-prep time reduction.
- Open exploratory conversations with AIUC, Klaimee, and Mount (AI-agent insurance/certification vendors) as potential integration or channel partners — their stated need for "a timestamped, replayable record of every AI decision and human sign-off" is exactly what Relay's audit trail already produces.
- Ship **scheduled/cron playbooks** (designed, not built, ~6–8h) to support recurring compliance evidence generation (e.g., weekly access reviews) — a natural fit once the compliance narrative requires periodic, not just ad hoc, evidence.
- Ship the **account-level budget dashboard** (designed, not built, ~4–5h), reframed in-product as a governance cost dashboard rather than a standalone spend tracker.

### Phase 4 — Fundraise & Platform Expansion
**Timeframe:** Ongoing, starting Week 10 in parallel with Phase 3

**Goals:**
- Raise a TR-first seed round on the narrowed thesis.
- Begin laying the expansion story without diluting the wedge before it's proven.

**Work items:**
- Approach 212, Revo Capital, Speedinvest CEE's enterprise-SaaS vertical team, and Bek Ventures with the narrowed Compliance Trail pitch and any Phase 3 case study in hand.
- Use the Turkish Business Angels Association network (Galata Business Angels, Istanbul Startup Angels, BIC Angels) as the realistic pre-institutional check ahead of an institutional seed conversation.
- Scope **connection multiplexing / multiple Jira sites per workspace** (not yet designed) as the mechanism for rolling up compliance evidence across multiple teams inside one larger regulated org — the natural expansion path once a single-team pilot is proven.
- Treat **rejection memory / learned preferences** (designed, not built, ~8–10h) as a deliberately deferred v2 item: valuable for reducing approval friction over time, but explicitly flagged as a thesis-tension risk (it can look like the auto-approval the compliance pitch argues against) — do not build it until the compliance narrative and customer base are established enough to introduce it carefully, with its own audit trail of what was learned and why.
- Frame the long-term platform story as expanding from engineering-org compliance evidence into the general "trust ledger" for any AI agent acting with write access inside a regulated company — security teams, IT operations, DevOps change management — but keep this as the *second* act of the pitch, after the wedge, not a simultaneous claim.

---

## Pitch Narrative

**Problem.** Regulated engineering organizations — fintechs, healthtechs — want AI agents to speed up the daily grind of Jira, Slack, GitHub, and Gmail coordination, but the person accountable for SOC 2, ISO 27001, or KVKK audits cannot approve giving an agent write access without proof of who authorized each action and why. Today that proof is assembled manually, via screenshots and spreadsheets, in a scramble before every audit — and no existing product produces this evidence for AI-agent actions specifically.

**Wedge.** Relay's approval gate is a hard, non-optional product default — WRITE actions require explicit human approval, DESTRUCTIVE actions are forbidden outright — and it already produces a timestamped, named-approver record for every action an agent takes across Jira, Slack, GitHub, and Gmail. We package that record as SOC 2 / ISO 27001 / KVKK-mappable compliance evidence, so an engineering leader personally accountable for the audit can let an agent act inside real tools because they can prove, at any moment, exactly who approved what and why.

**Why now.** Sequoia and a16z have both named "execution and governance layers with compliance architecture and audit trails" as the highest-conviction agentic AI investment thesis for 2026, and a new AI-agent insurance and certification category — AIUC, Klaimee, Mount — is forming right now, requiring exactly the kind of timestamped, replayable human-sign-off record Relay's architecture already produces. At the same time, Atlassian and ServiceNow are proving that governed agent write access is becoming an enterprise norm — the market is being educated for us, and the compliance-accountable buyer is the fastest path to converting that education into willingness to pay.

**Why us.** Relay's PolicyEngine, hard approval gate, and full audit trail — who approved, why, and at what cost — were built as the product's core architecture from day one, not bolted on afterward for a compliance narrative. We proved trusted agent execution in 48 hours because we treated the approval gate as the product itself, not a feature checkbox. And we are cross-tool by design, unifying Jira, Slack, GitHub, and Gmail into one audit trail — something neither Atlassian nor ServiceNow can match without becoming a horizontal platform themselves.

**Moat.** The moat is not integration count — it is that Relay's audit trail spans tools no single vendor owns, so it survives as evidence even as Atlassian and ServiceNow harden their own native, single-tool governance. As the AI-agent insurance and certification category matures, that cross-tool, named-approver record becomes the underwriting evidence those insurers require, turning Relay's audit trail into infrastructure a regulated engineering org cannot replicate by stitching together multiple single-vendor tools on its own.

**Expansion story.** Starting from SOC 2, ISO 27001, and KVKK evidence for engineering organizations, Relay expands along two axes: horizontally into adjacent regulated-team buyers — security, IT operations, DevOps change management — who need the same cross-tool approval evidence, and vertically into becoming the system of record that AI-agent insurance and certification vendors plug into when underwriting any company's agentic AI risk. The same architecture that proves trust for one engineering team's daily coordination becomes, over time, the default evidence layer for "was this AI agent action authorized" across an entire regulated enterprise.
