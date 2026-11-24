/**
 * Loading state for console pages (SCREEN_DESIGN_STANDARDS.md §6): a skeleton shaped like a title and a list, not a
 * spinner. Used by `loading.tsx` of the business and admin consoles.
 */
export function PageSkeleton() {
  const bar = "rounded-lg bg-surface motion-safe:animate-pulse";
  return (
    <main
      aria-busy="true"
      className="mx-auto flex w-full max-w-4xl flex-1 flex-col gap-8 px-6 py-8"
    >
      <div className={`${bar} h-7 w-1/3`} />
      <div className="flex flex-col gap-3">
        <div className={`${bar} h-14`} />
        <div className={`${bar} h-14`} />
        <div className={`${bar} h-14`} />
      </div>
    </main>
  );
}
