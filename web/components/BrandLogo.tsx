import Image from "next/image";

type Props = {
  /** 表示の一辺（正方形）。ナビは 32、ログインは 72 程度 */
  size?: number;
  priority?: boolean;
};

/** ブランドマーク。実体は `web/public/PC.png` */
export default function BrandLogo({ size = 32, priority = false }: Props) {
  return (
    <Image
      src="/PC.png"
      alt="架電SaaS"
      width={size}
      height={size}
      priority={priority}
      style={{
        display: "block",
        width: size,
        height: size,
        objectFit: "contain",
        borderRadius: size >= 56 ? 12 : 6,
      }}
    />
  );
}
