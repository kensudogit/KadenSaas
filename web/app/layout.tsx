import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "架電SaaS",
  description: "アウトバウンドコール業務の基盤",
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="ja">
      <body>{children}</body>
    </html>
  );
}
