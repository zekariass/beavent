import type { Metadata } from "next";
import { Noto_Sans } from "next/font/google";
import "@/app/globals.css";

// Same Latin face as the rest of the web platform (DESIGN_TOKENS.md §3.1).
const latin = Noto_Sans({ variable: "--font-latin", subsets: ["latin"] });

export const metadata: Metadata = {
  title: "Beavent admin",
  description: "Internal console for Beavent platform staff",
  robots: { index: false, follow: false },
};

export default function RootLayout({ children }: LayoutProps<"/ops-console">) {
  return (
    <html lang="en" className={`${latin.variable} h-full antialiased`}>
      <body className="flex min-h-full flex-col">{children}</body>
    </html>
  );
}
