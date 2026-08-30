import type { Metadata } from "next";
import "./globals.css";
import GuideMount from "@/components/GuideMount";

export const metadata: Metadata = {
  title: "架電SaaS",
  description: "アウトバウンドコール業務の基盤",
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="ja">
      <body>
        {children}
        <GuideMount />
      </body>
    </html>
  );
}
