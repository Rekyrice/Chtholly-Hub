import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const dockerfile = readFileSync(resolve("Dockerfile"), "utf8");
const compose = readFileSync(resolve("../../docker-compose.prod.yml"), "utf8");
const webService = compose.slice(compose.indexOf("\n  web:"));

describe("public frontend build variables", () => {
  it.each(["NEXT_PUBLIC_SITE_URL", "NEXT_PUBLIC_OSS_PUBLIC_URL"])(
    "injects %s into the Docker build stage",
    (variable) => {
      expect(dockerfile).toMatch(new RegExp(`ARG ${variable}(?:=|\\r?$)`, "m"));
      expect(dockerfile).toContain(`ENV ${variable}=$${variable}`);
    },
  );

  it.each(["NEXT_PUBLIC_SITE_URL", "NEXT_PUBLIC_OSS_PUBLIC_URL"])(
    "passes %s through the web service build args",
    (variable) => {
      expect(webService).toMatch(
        new RegExp(`^ {8}${variable}: \\$\\{${variable}\\}$`, "m"),
      );
    },
  );
});
