package com.relay.infrastructure.persistence;

import com.relay.application.stats.PanelStatsRepository;
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
        Object[] cells = (Object[]) window(em.createNativeQuery("""
                select count(*),
                       count(*) filter (where s.decision in ('approved', 'rejected')
                                           or s.status = 'awaiting_approval'),
                       count(*) filter (where s.decision = 'approved'),
                       count(*) filter (where s.decision = 'rejected'),
                       count(*) filter (where s.status = 'awaiting_approval')
                  from steps s
                  join runs r on r.id = s.run_id""" + RUN_WINDOW), from, to).getSingleResult();
        return new Gate(number(cells[0]), number(cells[1]), number(cells[2]), number(cells[3]), number(cells[4]));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Rejection> rejections(Instant from, Instant to, int limit) {
        /*
          Both halves of the `or` are needed. `decision = 'rejected'` catches a refusal
          where the person did not type anything, and `reject_reason is not null` catches
          a reason written before the decision column was settled. Dropping either one
          loses a real refusal from a screen whose whole point is that none are lost.
        */
        Query query = window(em.createNativeQuery("""
                select s.run_id, s.id, r.goal, r.status, s.title, s.tool_name, s.reject_reason,
                       coalesce(s.finished_at, s.started_at, r.created_at) as decided_at
                  from steps s
                  join runs r on r.id = s.run_id""" + RUN_WINDOW + """
                   and (s.decision = 'rejected' or s.reject_reason is not null)
                 order by decided_at desc, s.ordinal desc
                 limit :limit
                """), from, to);
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

    // -----------------------------------------------------------------------

    private static Query window(Query query, Instant from, Instant to) {
        return query.setParameter("from", from).setParameter("to", to);
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
