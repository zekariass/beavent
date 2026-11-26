/** Skeleton shaped like the results (S-02 loading): a title bar, filter bar and rows with a thumbnail and three bars. */
export function ResultsSkeleton() {
  const bar = "rounded-lg bg-surface motion-safe:animate-pulse";
  return (
    <main
      aria-busy="true"
      className="mx-auto flex w-full max-w-6xl flex-1 flex-col gap-6 px-4 py-6 sm:px-6"
    >
      <div className={`${bar} h-8 w-1/2`} />
      <div className={`${bar} h-12 w-full`} />
      <div className="grid gap-x-10 md:grid-cols-2">
        {[0, 1, 2, 3, 4].map((i) => (
          <div key={i} className="flex gap-4 border-b border-border py-4">
            <div className={`${bar} size-[104px] shrink-0`} />
            <div className="flex flex-1 flex-col gap-2">
              <div className={`${bar} h-5 w-2/3`} />
              <div className={`${bar} h-4 w-1/2`} />
              <div className={`${bar} h-4 w-1/3`} />
            </div>
          </div>
        ))}
      </div>
    </main>
  );
}
