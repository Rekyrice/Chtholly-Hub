import TraceDetailView from "@/components/site/TraceDetailView";

export default async function TraceDetailPage({
  params,
}: {
  params: Promise<{ correlationId: string }>;
}) {
  const { correlationId } = await params;
  return <TraceDetailView key={correlationId} correlationId={correlationId} />;
}
