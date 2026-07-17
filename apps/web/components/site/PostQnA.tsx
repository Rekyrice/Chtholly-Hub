"use client";

import { Send, Sparkles } from "lucide-react";
import { FormEvent, useEffect, useRef, useState } from "react";
import { ChthollyIllustration } from "@/components/site/ChthollyIllustration";
import { postAiService } from "@/lib/services/postAiService";
import { cn } from "@/lib/utils";

type QnATurn = {
  id: string;
  question: string;
  answer: string;
  status: "streaming" | "done" | "error";
};

type PostQnAProps = {
  postId: string;
};

function createTurnId() {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

export default function PostQnA({ postId }: PostQnAProps) {
  const [question, setQuestion] = useState("");
  const [turns, setTurns] = useState<QnATurn[]>([]);
  const [streaming, setStreaming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const abortControllerRef = useRef<AbortController | null>(null);

  useEffect(() => {
    return () => {
      abortControllerRef.current?.abort();
    };
  }, []);

  const appendAnswer = (turnId: string, chunk: string) => {
    setTurns((current) =>
      current.map((turn) =>
        turn.id === turnId ? { ...turn, answer: `${turn.answer}${chunk}` } : turn,
      ),
    );
  };

  const finishTurn = (turnId: string, status: QnATurn["status"] = "done") => {
    setTurns((current) =>
      current.map((turn) => (turn.id === turnId ? { ...turn, status } : turn)),
    );
    setStreaming(false);
  };

  const submitQuestion = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const text = question.trim();
    if (!text || streaming) return;

    const turnId = createTurnId();
    const history = turns
      .filter((turn) => turn.status === "done" && turn.answer.trim())
      .slice(-4)
      .map((turn) => ({ question: turn.question, answer: turn.answer }));
    setTurns((current) => [
      ...current,
      {
        id: turnId,
        question: text,
        answer: "",
        status: "streaming",
      },
    ]);
    setQuestion("");
    setStreaming(true);
    setError(null);

    const controller = new AbortController();
    abortControllerRef.current = controller;
    let receivedContent = false;
    let receivedDone = false;

    try {
      for await (const streamEvent of postAiService.qaStream(
        postId,
        text,
        history,
        controller.signal,
      )) {
        if (streamEvent.type === "delta") {
          receivedContent = true;
          appendAnswer(turnId, streamEvent.data);
          continue;
        }
        receivedDone = true;
        finishTurn(turnId);
      }
      if (!receivedDone) {
        throw new Error("文章问答流未发送完成事件");
      }
    } catch {
      if (controller.signal.aborted || receivedDone) return;
      setError(receivedContent
        ? "回答暂时中断了。已保留收到的内容，可以稍后再问一次。"
        : "回答暂时中断了。可以稍后再问一次。");
      finishTurn(turnId, "error");
    } finally {
      if (abortControllerRef.current === controller) {
        abortControllerRef.current = null;
      }
    }
  };

  return (
    <section className="post-qna" aria-labelledby="post-qna-title">
      <div className="post-qna__intro">
        <ChthollyIllustration size="sm" state="curious" className="post-qna__illustration" />
        <div>
          <p className="post-qna__eyebrow">
            <Sparkles size={14} />
            文章问答
          </p>
          <h2 id="post-qna-title">问珂朵莉关于这篇文章</h2>
          <p>她会先读这篇文章，再安静地回答你的问题。可以继续追问。</p>
        </div>
      </div>

      {turns.length > 0 && (
        <div className="post-qna__history" aria-live="polite">
          {turns.map((turn) => (
            <article key={turn.id} className="post-qna-turn">
              <div className="post-qna-turn__question">{turn.question}</div>
              <div
                className={cn(
                  "post-qna-turn__answer",
                  turn.status === "streaming" && "post-qna-turn__answer--streaming",
                )}
              >
                {turn.answer || (turn.status === "streaming" ? "珂朵莉正在想……" : "没有收到回答。")}
              </div>
            </article>
          ))}
        </div>
      )}

      <form className="post-qna__form" onSubmit={submitQuestion}>
        <textarea
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
          placeholder="想问些什么呢？比如：这篇文章的核心观点是什么？"
          disabled={streaming}
          rows={2}
        />
        <button type="submit" disabled={streaming || !question.trim()}>
          <Send size={16} />
          <span>{streaming ? "回答中..." : "发送"}</span>
        </button>
      </form>

      {error && <p className="post-qna__error">{error}</p>}
    </section>
  );
}
