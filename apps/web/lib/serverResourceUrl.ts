export function resolveServerResourceUrl(
  resourceUrl: string,
  serverOrigin = process.env.API_SERVER_URL ?? "http://localhost:8888",
) {
  if (!resourceUrl.trim()) return resourceUrl;

  try {
    return new URL(resourceUrl).toString();
  } catch {
    const baseUrl = serverOrigin.endsWith("/") ? serverOrigin : `${serverOrigin}/`;
    return new URL(resourceUrl, baseUrl).toString();
  }
}
