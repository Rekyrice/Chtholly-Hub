import SiteChrome from "@/components/site/SiteChrome";
import { NOT_FOUND_VISUAL } from "@/lib/route-visuals";
import SiteNotFound from "./(site)/not-found";

export default function NotFound() {
  return (
    <SiteChrome visualOverride={NOT_FOUND_VISUAL}>
      <SiteNotFound />
    </SiteChrome>
  );
}
