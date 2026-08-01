package com.relay.infrastructure.persistence;

import com.relay.application.stats.PanelStatsRepository;
import com.relay.domain.AgentRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The panel, in SQL.
 *
 * <p>Deliberately native and deliberately aggregate-only. Loading the runs through the
 * JPA aggregate would pull every step, every agent message and every JSON blob across
 * the wire so the application could count rows the database can count in place — on the
 * demo box that is the difference between a screen that opens and a screen that spins.
 *
 * <p>{@code count(*) filter (where ...)} is PostgreSQL; so is the rest of this project.
 */
@Repository
public class JpaPanelStatsRepository implements PanelStatsRepository {

    /**
     * The window is a half-open interval on the RUN's start time: {@code [from, to)}.
     * Applying it to the run and not to the step is what keeps a run that spans midnight
     * from being counted in two ranges at once.
     *
     * <p>The newlines are load-bearing. A text block strips trailing spaces from every
     * line, so a fragment glued on after {@code ... where } arrives as {@code wherer} —
     * which Postgres reports as a syntax error and nothing else catches.
     */
    private static final String RUN_WINDOW = "\n where r.created_at >= :from and r.created_at < :to\n";

    /**
     * "This step was closed by someone stopping the run, not by someone refusing it."
     *
     * <p>Two conditions, and both are needed. {@code Coordinator.stop} is the only code
     * that writes this sentence, and it writes it only while finishing the run as
     * {@code cancelled} — so a row matching both was produced there. Either half alone is
     * not enough: a run can be cancelled long after a human typed a real refusal on one of
     * its steps, and a person is free to type anything into the reject box.
     *
     * <p>This is still a literal match on a sentence our own code writes; see
     * {@link PanelStatsRepository#CANCEL_REASON_PREFIX} for why that is acceptable here
     * and what would replace it.
     */
    private static final String CANCELLED_OFF =
            "s.decision = 'rejected' and r.status = 'cancelled' and s.reject_reason like :cancelReason";

    /**
     * "A human rewrote a parameter on this step before approving it."
     *
     * <p>Read from the journal rather than from {@code steps.params_locked}, and the
     * difference matters: the column is cleared when a write bounces back to the gate
     * after the provider refused it ({@code ToolAgent.refreshParams}), which would drop
     * exactly the steps a person had to correct twice. {@code agent_messages} is
     * append-only, so the record of the correction outlives the correction.
     *
     * <p>{@code exists} rather than a join: one step can carry several edit lines — one
     * per field — and a join would count that step once per field it changed. The screen
     * says "kaç karar", not "kaç alan".
     *
     * <p>See {@link PanelStatsRepository#PARAM_EDIT_PREFIX} for the literal and why it is
     * one.
     */
    private static final String APPROVED_WITH_EDIT = """
            s.decision = 'approved' and exists (
                select 1 from agent_messages m
                 where m.step_id = s.id
                   and m.from_agent = :userAgent
                   and m.content like :editPrefix)""";

    private final EntityManager em;

    public JpaPanelStatsRepository(EntityManager em) {
        this.em = em;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Count> runStatusCounts(Instant from, Instant to) {
        List<?> rows = window(em.createNativeQuery("""
                select r.status, count(*)
                  from runs r""" + RUN_WINDOW + """
                 group by r.status
                """), from, to).getResultList();
        List<Count> out = new ArrayList<>();
        for (Object row : rows) {
            Object[] cells = (Object[]) row;
            out.add(new Count(text(cells[0]), number(cells[1])));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Totals runTotals(Instant from, Instant to) {
        Object[] cells = (Object[]) window(em.createNativeQuery("""
                select count(*), coalesce(sum(r.cost_tokens), 0), coalesce(sum(r.cost_usd), 0)
                  from runs r""" + RUN_WINDOW), from, to).getSingleResult();
        return new Totals(number(cells[0]), number(cells[1]), money(cells[2]));
    }

    @Override
    @Transactional(readOnly = true)
    public Gate gateCounts(Instant from, Instant to) {
        /*
          "Reached the gate" is decided from two columns, not one. A step a human answered
          carries decision approved/rejected; a step still waiting carries no decision at
          all and is only recognisable by its status. Counting either alone under-reports
          the very number the screen exists to show.
        */
        /*
          The refusal bucket and the cancellation bucket are cut from the same column and
          are disjoint by construction — `rejected` carries `not (…)` of exactly the
          predicate `cancelled` carries. That is what keeps the three numbers the screen
          prints adding up to the decisions that were actually made.
        */
        String sql = "select count(*),\n"
                + " count(*) filter (where s.decision in ('approved', 'rejected')"
                + " or s.status = 'awaiting_approval'),\n"
                + " count(*) filter (where s.decision = 'approved'),\n"
                + " count(*) filter (where " + APPROVED_WITH_EDIT + "),\n"
                + " count(*) filter (where s.decision = 'rejected' and not (" + CANCELLED_OFF + ")),\n"
                + " count(*) filter (where " + CANCELLED_OFF + "),\n"
                + " count(*) filter (where s.status = 'awaiting_approval')\n"
                + " from steps s join runs r on r.id = s.run_id" + RUN_WINDOW;
        Query query = editPattern(cancelPattern(window(em.createNativeQuery(sql), from, to)));
        Object[] cells = (Object[]) query.getSingleResult();
        return new Gate(number(cells[0]), number(cells[1]), number(cells[2]), number(cells[3]),
                number(cells[4]), number(cells[5]), number(cells[6]));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Rejection> rejections(Instant from, Instant to, int limit) {
        /*
          Both halves of the first `or` are needed. `decision = 'rejected'` catches a
          refusal where the person did not type anything, and `reject_reason is not null`
          catches a reason written before the decision column was settled. Dropping either
          one loses a real refusal from a screen whose whole point is that none are lost.

          The `not (…)` is what issue #54 is about: four of the six lines on the live panel
          were one person pressing Durdur, and a list of refusals that is mostly not
          refusals cannot prove the gate does anything.
        */
        return list(from, to, limit,
                "(s.decision = 'rejected' or s.reject_reason is not null) and not (" + CANCELLED_OFF + ")");
    }

    @Override
    @Transactional(readOnly = true)
    public List<Rejection> cancellations(Instant from, Instant to, int limit) {
        return list(from, to, limit, CANCELLED_OFF);
    }

    /** The two lists differ by one predicate; everything else about them must not drift. */
    private List<Rejection> list(Instant from, Instant to, int limit, String predicate) {
        Query query = cancelPattern(window(em.createNativeQuery("""
                select s.run_id, s.id, r.goal, r.status, s.title, s.tool_name, s.reject_reason,
                       coalesce(s.finished_at, s.started_at, r.created_at) as decided_at
                  from steps s
                  join runs r on r.id = s.run_id""" + RUN_WINDOW + "   and (" + predicate + """
                )
                 order by decided_at desc, s.ordinal desc
                 limit :limit
                """), from, to));
        query.setParameter("limit", Math.max(1, limit));
        List<Rejection> out = new ArrayList<>();
        for (Object row : query.getResultList()) {
            Object[] cells = (Object[]) row;
            out.add(new Rejection(uuid(cells[0]), uuid(cells[1]), text(cells[2]), text(cells[3]),
                    text(cells[4]), text(cells[5]), text(cells[6]), instant(cells[7])));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ToolUsage> toolUsage(Instant from, Instant to) {
        /*
          A call is a step that actually reached the provider, so `done` and `failed`
          count and nothing else does. A rejected or still-pending step never made the
          request — putting it in this table would invoice the customer for work that
          was refused.
        */
        List<?> rows = window(em.createNativeQuery("""
                select s.tool_name, count(*), coalesce(sum(s.tokens), 0), coalesce(sum(s.cost_usd), 0)
                  from steps s
                  join runs r on r.id = s.run_id""" + RUN_WINDOW + """
                   and s.tool_name is not null
                   and s.status in ('done', 'failed')
                 group by s.tool_name
                 order by count(*) desc, s.tool_name asc
                """), from, to).getResultList();
        List<ToolUsage> out = new ArrayList<>();
        for (Object row : rows) {
            Object[] cells = (Object[]) row;
            out.add(new ToolUsage(text(cells[0]), number(cells[1]), number(cells[2]), money(cells[3])));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ModelUsage> modelUsage(Instant from, Instant to) {
        if (!hasStepColumn("model")) {
            return List.of();
        }
        /*
          `s.model is not null` is the whole predicate, and it is doing two jobs. It is the
          most direct statement that a model answered this step — narrower than the
          `status in ('done','failed')` toolUsage uses, which counts a step that reached a
          provider whether or not a model was involved. And it is the same predicate the
          premium column is summed under, so the money on the per-model rows and the money
          on the comparison line are two views of one set of rows. A judge can add the
          column up and get the total; that is the property this block is sold on.

          Rows written before the migration carry no model and are therefore absent from
          both sides at once. They are not silently priced at zero on one side.
        */
        boolean priced = hasStepColumn("premium_cost_usd");
        String premium = priced ? ", coalesce(sum(s.premium_cost_usd), 0)" : "";
        List<?> rows = window(em.createNativeQuery("""
                select s.model, count(*), coalesce(sum(s.tokens), 0), coalesce(sum(s.cost_usd), 0)"""
                + premium + """

                  from steps s
                  join runs r on r.id = s.run_id""" + RUN_WINDOW + """
                   and s.model is not null
                 group by s.model
                 order by count(*) desc, s.model asc
                """), from, to).getResultList();
        List<ModelUsage> out = new ArrayList<>();
        for (Object row : rows) {
            Object[] cells = (Object[]) row;
            out.add(new ModelUsage(text(cells[0]), number(cells[1]), number(cells[2]), money(cells[3]),
                    priced ? money(cells[4]) : null));
        }
        return out;
    }

    // -----------------------------------------------------------------------

    /**
     * Does {@code steps} carry this column on the box we are actually running against?
     *
     * <p>The two columns this block reads arrive in a migration, and this query is the
     * only reason the panel keeps answering on a deploy where the code is ahead of the
     * schema. Asking the catalogue is cheaper than the alternative and much better
     * behaved: a missing column raises a {@code SQLException} that Postgres reports by
     * poisoning the transaction, so every later statement in the same read-only
     * transaction would fail too — one broken block would take the whole screen down.
     *
     * <p>Not cached, on purpose. The answer changes exactly once, at the moment the
     * migration runs, and a cached {@code false} would keep the block dark until somebody
     * restarted the API and worked out why. One catalogue lookup per panel load is not a
     * cost worth that.
     */
    private boolean hasStepColumn(String column) {
        return !em.createNativeQuery("""
                select 1
                  from information_schema.columns
                 where table_schema = current_schema()
                   and table_name = 'steps'
                   and column_name = :column
                """)
                .setParameter("column", column)
                .getResultList()
                .isEmpty();
    }

    private static Query window(Query query, Instant from, Instant to) {
        return query.setParameter("from", from).setParameter("to", to);
    }

    /**
     * Bound rather than inlined. The sentence carries a Turkish dotless ı and, when the
     * actor is known, an e-mail address in brackets — building that into the SQL text
     * would be an injection hole in the one query on this screen that reads user input.
     */
    private static Query cancelPattern(Query query) {
        return query.setParameter("cancelReason", PanelStatsRepository.CANCEL_REASON_PREFIX + "%");
    }

    /** Same reason as {@link #cancelPattern}: bound, never spliced. */
    private static Query editPattern(Query query) {
        return query.setParameter("userAgent", AgentRole.USER)
                .setParameter("editPrefix", PanelStatsRepository.PARAM_EDIT_PREFIX + "%");
    }

    /**
     * The JDBC types behind these aggregates are not stable across drivers —
     * {@code count(*)} arrives as Long, {@code sum(bigint)} as BigDecimal. Reading them
     * through {@link Number} means a driver upgrade cannot turn a working panel into a
     * ClassCastException.
     */
    private static long number(Object cell) {
        return cell instanceof Number n ? n.longValue() : 0L;
    }

    private static double money(Object cell) {
        if (cell instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        return cell instanceof Number n ? n.doubleValue() : 0d;
    }

    private static String text(Object cell) {
        return cell == null ? null : cell.toString();
    }

    private static UUID uuid(Object cell) {
        if (cell instanceof UUID id) {
            return id;
        }
        return cell == null ? null : UUID.fromString(cell.toString());
    }

    private static Instant instant(Object cell) {
        if (cell instanceof Instant value) {
            return value;
        }
        if (cell instanceof Timestamp value) {
            return value.toInstant();
        }
        if (cell instanceof OffsetDateTime value) {
            return value.toInstant();
        }
        if (cell instanceof ZonedDateTime value) {
            return value.toInstant();
        }
        return null;
    }
}
