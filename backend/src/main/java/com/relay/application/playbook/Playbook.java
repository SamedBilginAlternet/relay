package com.relay.application.playbook;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A flow we wrote down instead of leaving to the planner.
 *
 * <p>Free-form goals are the product's promise and stay. But the recurring jobs — the
 * morning round-up, mail becomes a ticket, tell the team what changed — always have the
 * same shape, and re-deriving that shape on every run makes quality depend on how the
 * model felt. A playbook fixes the shape and the wording; the model still fills in the
 * facts, and every write still passes the approval gate.
 *
 * @param id       stable handle used by the API
 * @param title    what the user sees on the button
 * @param goal     the run goal, phrased as the user would say it
 * @param subtitle one line explaining what will happen
 * @param steps    ordered steps; those whose provider is not connected are dropped at start
 */
public record Playbook(String id, String title, String goal, String subtitle, List<Move> steps) {

    /**
     * One step of a playbook.
     *
     * @param title    step title shown in the plan
     * @param toolName the tool to call
     * @param params   seed parameters; the specialist still finalises them
     * @param optional when true the step is dropped if its provider is missing, instead of
     *                 blocking the whole playbook
     */
    public record Move(String title, String toolName, Map<String, Object> params, boolean optional) {

        public static Move required(String title, String toolName, Map<String, Object> params) {
            return new Move(title, toolName, params, false);
        }

        public static Move optional(String title, String toolName, Map<String, Object> params) {
            return new Move(title, toolName, params, true);
        }
    }

    public Map<String, Object> view() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("title", title);
        map.put("goal", goal);
        map.put("subtitle", subtitle);
        List<Map<String, Object>> moves = new java.util.ArrayList<>();
        for (Move move : steps) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", move.title());
            item.put("tool", move.toolName());
            item.put("optional", move.optional());
            moves.add(item);
        }
        map.put("steps", moves);
        return map;
    }
}
