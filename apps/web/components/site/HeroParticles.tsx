import type { CSSProperties } from "react";

/** 预置粒子参数（固定种子，避免 SSR/CSR 不一致） */
const PARTICLES = [
  { left: 6, bottom: 5, size: 3, duration: 12, delay: 0, opacity: 0.5 },
  { left: 14, bottom: 18, size: 2, duration: 9, delay: 1.2, opacity: 0.7 },
  { left: 22, bottom: 8, size: 4, duration: 14, delay: 2.5, opacity: 0.6 },
  { left: 31, bottom: 25, size: 2, duration: 11, delay: 0.8, opacity: 0.8 },
  { left: 38, bottom: 12, size: 3, duration: 10, delay: 3.1, opacity: 0.55 },
  { left: 45, bottom: 30, size: 2, duration: 13, delay: 1.8, opacity: 0.65 },
  { left: 52, bottom: 6, size: 4, duration: 15, delay: 4.2, opacity: 0.75 },
  { left: 58, bottom: 20, size: 3, duration: 8, delay: 0.3, opacity: 0.5 },
  { left: 64, bottom: 14, size: 2, duration: 12, delay: 2.9, opacity: 0.7 },
  { left: 71, bottom: 28, size: 3, duration: 11, delay: 1.5, opacity: 0.6 },
  { left: 77, bottom: 10, size: 2, duration: 9, delay: 3.6, opacity: 0.8 },
  { left: 83, bottom: 22, size: 4, duration: 14, delay: 0.6, opacity: 0.55 },
  { left: 88, bottom: 16, size: 3, duration: 10, delay: 2.2, opacity: 0.65 },
  { left: 92, bottom: 4, size: 2, duration: 13, delay: 4.8, opacity: 0.7 },
  { left: 18, bottom: 35, size: 3, duration: 11, delay: 5.1, opacity: 0.45 },
  { left: 48, bottom: 38, size: 2, duration: 12, delay: 3.9, opacity: 0.6 },
  { left: 68, bottom: 32, size: 3, duration: 9, delay: 1.1, opacity: 0.75 },
  { left: 95, bottom: 26, size: 2, duration: 15, delay: 2.7, opacity: 0.5 },
] as const;

export default function HeroParticles() {
  return (
    <div className="site-header-particles" aria-hidden="true">
      {PARTICLES.map((particle, index) => (
        <span
          key={index}
          className="site-header-particle"
          style={
            {
              left: `${particle.left}%`,
              bottom: `${particle.bottom}%`,
              width: particle.size,
              height: particle.size,
              "--particle-opacity": particle.opacity,
              animationDuration: `${particle.duration}s`,
              animationDelay: `${particle.delay}s`,
            } as CSSProperties
          }
        />
      ))}
    </div>
  );
}
