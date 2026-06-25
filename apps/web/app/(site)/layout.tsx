import Navbar from "@/components/site/Navbar";
import SiteHeader from "@/components/site/SiteHeader";
import Footer from "@/components/site/Footer";

export default function SiteLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <div className="min-h-screen flex flex-col">
      <Navbar />
      <div className="h-[52px]" />
      <SiteHeader />
      <div className="relative flex-1">
        <main className="relative z-10 flex-1 py-8">
          <div className="max-w-6xl mx-auto px-4">{children}</div>
        </main>
        <div className="relative z-10">
          <Footer />
        </div>
      </div>
    </div>
  );
}
