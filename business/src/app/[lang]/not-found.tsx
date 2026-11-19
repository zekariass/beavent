import Link from "next/link";

export default function NotFound() {
  return (
    <main className="mx-auto flex max-w-xl flex-1 flex-col justify-center gap-4 px-6 py-16">
      <h1 className="text-2xl font-semibold">404</h1>
      <p lang="am">ይህ ገጽ አልተገኘም።</p>
      <p lang="en">This page was not found.</p>
      <Link href="/" className="underline">
        Beavent
      </Link>
    </main>
  );
}
