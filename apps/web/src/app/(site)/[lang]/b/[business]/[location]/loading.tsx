/** Skeleton shaped like the venue page (hero, title, rows), not a spinner (SCREEN_DESIGN_STANDARDS.md §6). */
export default function Loading() {
  const bar = "rounded-lg bg-surface motion-safe:animate-pulse";
  return (
    <main
      aria-busy="true"
      className="mx-auto flex w-full max-w-6xl flex-1 flex-col gap-6 px-4 py-6 sm:px-6"
    >
      <div className={`${bar} aspect-[3/2] w-full sm:aspect-[3/1]`} />
      <div className={`${bar} h-8 w-2/3`} />
      <div className={`${bar} h-5 w-1/3`} />
      {[0, 1, 2, 3].map((i) => (
        <div key={i} className={`${bar} h-14`} />
      ))}
    </main>
  );
}
