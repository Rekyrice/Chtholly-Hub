"use client";

import Image from "next/image";
import { cn } from "@/lib/utils";

export type IllustrationState =
  | "default"
  | "happy"
  | "sleepy"
  | "curious"
  | "reading"
  | "bored"
  | "greeting"
  | "away"
  | "serious"
  | "thinking"
  | "calm";

export type ChthollyIllustrationProps = {
  size?: "xs" | "sm" | "md" | "lg";
  className?: string;
  mood?: number;
  timeOfDay?: "morning" | "afternoon" | "evening" | "night" | "late-night";
  pageContext?: string;
  state?: IllustrationState;
};

export const ILLUSTRATION_MAP: Record<IllustrationState, string> = {
  default: "/images/illustrations/default.png",
  happy: "/images/illustrations/happy.png",
  sleepy: "/images/illustrations/sleepy.png",
  curious: "/images/illustrations/curious.png",
  reading: "/images/illustrations/reading.png",
  bored: "/images/illustrations/bored.png",
  greeting: "/images/illustrations/greeting.png",
  away: "/images/illustrations/away.png",
  serious: "/images/illustrations/reading.png",
  thinking: "/images/illustrations/curious.png",
  calm: "/images/illustrations/default.png",
};

const IMAGE_SIZE = {
  xs: { width: 84, height: 112, className: "h-28 w-auto" },
  sm: { width: 120, height: 160, className: "h-40 w-auto" },
  md: { width: 200, height: 280, className: "h-60 w-auto" },
  lg: { width: 300, height: 400, className: "h-80 w-auto" },
} as const;

export function ChthollyIllustration({
  size = "md",
  className,
  mood = 0,
  timeOfDay = "afternoon",
  pageContext,
  state,
}: ChthollyIllustrationProps) {
  const illustrationState = state ?? selectIllustration(mood, timeOfDay, pageContext);
  const imageSize = IMAGE_SIZE[size];

  return (
    <div className={cn("chtholly-illustration", className)}>
      <Image
        src={ILLUSTRATION_MAP[illustrationState]}
        alt={`珂朵莉 - ${illustrationState}`}
        width={imageSize.width}
        height={imageSize.height}
        className={cn("chtholly-illustration__image", imageSize.className)}
      />
    </div>
  );
}

export function selectIllustration(
  mood: number,
  timeOfDay: ChthollyIllustrationProps["timeOfDay"] = "afternoon",
  pageContext?: string,
): IllustrationState {
  if (timeOfDay === "late-night") return "away";
  if (timeOfDay === "night") return "sleepy";
  if (pageContext?.includes("/search")) return "curious";
  if (pageContext?.includes("/post/")) return "reading";
  if (mood > 0.3) return "happy";
  if (mood < -0.3) return "bored";
  return "default";
}
