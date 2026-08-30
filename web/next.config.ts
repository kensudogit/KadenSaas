import type { NextConfig } from "next";

const config: NextConfig = {
  // ★ Docker で配るので standalone。node_modules を丸ごと運ばずに済む
  output: "standalone",
  reactStrictMode: true,
};

export default config;
