import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: {
    default: "SeekFlux · 从搜索走向发现",
    template: "%s · SeekFlux",
  },
  description:
    "连接 C 端内容发现与 B 端用户画像、内容发布的短视频搜索推荐产品。",
  applicationName: "SeekFlux",
  openGraph: {
    type: "website",
    locale: "zh_CN",
    siteName: "SeekFlux",
    title: "SeekFlux · 从搜索走向发现",
    description: "搜索、推荐、相似内容与创作者工作台，共用一套内容画像。",
  },
  twitter: {
    card: "summary_large_image",
    title: "SeekFlux · 从搜索走向发现",
    description: "搜索、推荐、相似内容与创作者工作台，共用一套内容画像。",
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN">
      <body className={`${geistSans.variable} ${geistMono.variable}`}>
        {children}
      </body>
    </html>
  );
}
