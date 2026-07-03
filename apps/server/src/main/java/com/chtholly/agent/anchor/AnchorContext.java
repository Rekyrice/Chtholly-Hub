package com.chtholly.agent.anchor;

import com.chtholly.agent.memory.AgentTurn;
import com.chtholly.agent.state.CharacterState;

import java.util.List;

/**
 * Snapshot of the five identity anchors used to build agent context.
 *
 * @param soul       Identity anchor from the character soul file.
 * @param episodic   Recent conversation turns.
 * @param semantic   Knowledge snippets from the semantic anchor.
 * @param procedural Learned behavior rules.
 * @param relational Per-user relationship and mood state.
 */
public record AnchorContext(
        String soul,
        List<AgentTurn> episodic,
        List<String> semantic,
        List<String> procedural,
        CharacterState relational
) {
    /**
     * Creates a builder with safe empty defaults.
     *
     * @return Anchor context builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String soul = "";
        private List<AgentTurn> episodic = List.of();
        private List<String> semantic = List.of();
        private List<String> procedural = List.of();
        private CharacterState relational = CharacterState.defaultState();

        public Builder soul(String soul) {
            this.soul = soul == null ? "" : soul;
            return this;
        }

        public Builder episodic(List<AgentTurn> episodic) {
            this.episodic = episodic == null ? List.of() : List.copyOf(episodic);
            return this;
        }

        public Builder semantic(List<String> semantic) {
            this.semantic = semantic == null ? List.of() : List.copyOf(semantic);
            return this;
        }

        public Builder procedural(List<String> procedural) {
            this.procedural = procedural == null ? List.of() : List.copyOf(procedural);
            return this;
        }

        public Builder relational(CharacterState relational) {
            this.relational = relational == null ? CharacterState.defaultState() : relational;
            return this;
        }

        public AnchorContext build() {
            return new AnchorContext(soul, episodic, semantic, procedural, relational);
        }
    }
}
