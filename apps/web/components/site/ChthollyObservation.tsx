import { MessageCircleHeart } from "lucide-react";
import { ChthollyIllustration } from "@/components/site/ChthollyIllustration";
import type { AgentExperienceItem } from "@/lib/types/search";

type ChthollyObservationProps = {
  experiences: AgentExperienceItem[];
};

export default function ChthollyObservation({ experiences }: ChthollyObservationProps) {
  const lines = experiences
    .map((experience) => experience.text)
    .filter(Boolean)
    .slice(0, 2);

  return (
    <section className="hub-observation">
      <div className="hub-section-heading">
        <p>Observation</p>
        <h2>
          <MessageCircleHeart size={18} />
          珂朵莉观察
        </h2>
      </div>
      <div className="hub-observation__body">
        <ChthollyIllustration size="xs" state={lines.length > 0 ? "curious" : "default"} />
        <div>
          {lines.length > 0 ? (
            lines.map((line, index) => <p key={`${line}-${index}`}>{line}</p>)
          ) : (
            <p>今天仓库里还算安静。没有关系，安静的时候也适合读一点东西。</p>
          )}
        </div>
      </div>
    </section>
  );
}
