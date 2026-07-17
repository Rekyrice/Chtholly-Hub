package com.chtholly.post.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request payload for a post-scoped, multi-turn streamed question.
 *
 * @param question current reader question
 * @param history completed turns from the current article page
 */
public record PostQaRequest(
        @NotBlank(message = "question 不能为空")
        @Size(max = 500, message = "question 不能超过 500 个字符")
        String question,
        @Valid
        @Size(max = 4, message = "history 不能超过 4 轮")
        List<Turn> history
) {
    public PostQaRequest {
        history = history == null ? List.of() : List.copyOf(history);
    }

    /**
     * One completed article question-and-answer turn.
     *
     * @param question previous reader question
     * @param answer previous assistant answer
     */
    public record Turn(
            @NotBlank(message = "history.question 不能为空")
            @Size(max = 500, message = "history.question 不能超过 500 个字符")
            String question,
            @NotBlank(message = "history.answer 不能为空")
            @Size(max = 4000, message = "history.answer 不能超过 4000 个字符")
            String answer
    ) {
    }
}
