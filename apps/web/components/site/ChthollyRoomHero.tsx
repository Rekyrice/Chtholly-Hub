import ChthollyInlineChat from "@/components/agent/ChthollyInlineChat";
import { ChthollyIllustration } from "@/components/site/ChthollyIllustration";
import type { ChthollyIllustrationProps } from "@/components/site/ChthollyIllustration";

type ChthollyRoomHeroProps = {
  mood: number;
  message: string;
  timeOfDay: NonNullable<ChthollyIllustrationProps["timeOfDay"]>;
};

export default function ChthollyRoomHero({
  mood,
  message,
  timeOfDay,
}: ChthollyRoomHeroProps) {
  return (
    <section className="chtholly-room-hero" aria-labelledby="chtholly-room-title">
      <div className="chtholly-room-hero__character">
        <span className="chtholly-room-hero__halo" aria-hidden="true" />
        <ChthollyIllustration size="lg" mood={mood} timeOfDay={timeOfDay} />
      </div>

      <div className="chtholly-room-hero__desk">
        <p className="chtholly-room-kicker">CHTHOLLY&apos;S ROOM</p>
        <h1 id="chtholly-room-title">今天也在这里</h1>
        <p className="room-mood">{message}</p>
        <ChthollyInlineChat />
      </div>
    </section>
  );
}
