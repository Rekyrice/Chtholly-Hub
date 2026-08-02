package com.chtholly.agent.web;

/**
 * Explains a robots.txt policy decision.
 *
 * @param decision final allow or deny decision
 * @param cacheHit whether the origin policy came from cache
 * @param matchedRule matched robots rule, when present
 * @param errorCode stable failure code for fail-closed decisions, when present
 */
public record RobotsPolicyResult(
        RobotsDecision decision,
        boolean cacheHit,
        String matchedRule,
        String errorCode) {

    /**
     * Reports whether crawling is allowed.
     *
     * @return true for an allow decision
     */
    public boolean allowed() {
        return decision == RobotsDecision.ALLOW;
    }
}
