import Image from "next/image";
import { cn } from "@/lib/utils";

type ChthollyAvatarProps = {
  size?: "sm" | "md" | "lg";
  className?: string;
};

const SIZE_CLASS = {
  sm: "size-6",
  md: "size-8",
  lg: "size-full",
} as const;

export default function ChthollyAvatar({
  size = "md",
  className,
}: ChthollyAvatarProps) {
  return (
    <span
      className={cn(
        "inline-flex shrink-0 items-center justify-center bg-transparent",
        SIZE_CLASS[size],
        className,
      )}
      aria-hidden="true"
      data-testid="chtholly-avatar"
      data-size={size}
    >
      <Image
        src="/images/illustrations/chtholly4.png"
        alt=""
        width={500}
        height={500}
        unoptimized
        className="block size-full object-contain"
      />
    </span>
  );
}
