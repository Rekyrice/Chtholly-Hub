import { describe, expect, it, vi } from "vitest";

vi.mock("next/font/google", () => {
  const font = () => ({ variable: "--test-font" });

  return {
    JetBrains_Mono: font,
    Noto_Sans_JP: font,
    Noto_Sans_SC: font,
    Playfair_Display: font,
    Source_Sans_3: font,
  };
});

import { metadata as landingMetadata } from "@/app/(site)/page";
import { metadata as rootMetadata } from "@/app/layout";

describe("canonical metadata", () => {
  it("does not define a root canonical inherited by child routes", () => {
    expect(rootMetadata.alternates?.canonical).toBeUndefined();
  });

  it("defines the landing page canonical as the site root", () => {
    expect(landingMetadata.alternates?.canonical).toBe("/");
  });
});
