import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "행간 — 하루를 따라 마음을 읽다",
  description: "목소리로 남기는 나만의 일기와 한 주의 회고",
  manifest: "/manifest.webmanifest",
  appleWebApp: { capable:true, title:"행간", statusBarStyle:"default" },
  icons: {
    icon: "/favicon.svg",
    shortcut: "/favicon.svg",
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="ko">
      <body className="antialiased">{children}</body>
    </html>
  );
}
