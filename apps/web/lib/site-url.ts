const LOCAL_SITE_URL = "http://localhost:3000/";

export type RemoteImagePattern = {
  protocol: "http" | "https";
  hostname: string;
  port: string;
  pathname: "/**";
};

export function resolveSiteUrl(value?: string | null): URL {
  try {
    const url = new URL(value?.trim() || LOCAL_SITE_URL);

    if (url.protocol !== "http:" && url.protocol !== "https:") {
      return new URL(LOCAL_SITE_URL);
    }

    return url;
  } catch {
    return new URL(LOCAL_SITE_URL);
  }
}

export function parseRemoteImageOrigin(
  value?: string | null,
): RemoteImagePattern | null {
  if (!value?.trim()) {
    return null;
  }

  try {
    const url = new URL(value);

    if (url.protocol !== "http:" && url.protocol !== "https:") {
      return null;
    }

    return {
      protocol: url.protocol.slice(0, -1) as RemoteImagePattern["protocol"],
      hostname: url.hostname,
      port: url.port,
      pathname: "/**",
    };
  } catch {
    return null;
  }
}

export const siteUrl = resolveSiteUrl(process.env.NEXT_PUBLIC_SITE_URL);
