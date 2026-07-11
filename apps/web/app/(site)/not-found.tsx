import Image from "next/image";
import Link from "next/link";
import "../styles/not-found.css";

export default function NotFound() {
  return (
    <div className="not-found-page">
      <div className="not-found-background">
        <div className="not-found-background__image" />
        <div className="not-found-background__scrim" />
      </div>
      <div className="not-found-content">
        <Image
          src="/images/illustrations/lost.png"
          alt="珂朵莉迷路了"
          width={500}
          height={500}
          unoptimized
          className="not-found-illustration"
          priority
        />
        <h1 className="not-found-title">404</h1>
        <p className="not-found-message">这个页面迷失在妖精仓库了……</p>
        <p className="not-found-submessage">珂朵莉找了好久也没找到呢</p>
        <Link href="/hub" className="not-found-btn">
          回到仓库
        </Link>
      </div>
    </div>
  );
}
