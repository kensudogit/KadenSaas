import type { Metadata } from "next";
import "./globals.css";
import GuideMount from "@/components/GuideMount";
import AppNav from "@/components/AppNav";

export const metadata: Metadata = {
  title: "架電SaaS",
  description: "アウトバウンドコール業務の基盤",
  icons: {
    icon: [{ url: "/PC.png", type: "image/png" }],
    apple: "/PC.png",
  },
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="ja">
      <body>
        <AppNav />
        {children}
        <GuideMount />
      </body>
    </html>
  );
}
