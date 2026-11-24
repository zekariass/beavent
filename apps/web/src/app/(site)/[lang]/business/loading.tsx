import { PageSkeleton } from "@/components/PageSkeleton";

/** Shown while a business-console page loads its per-user data (SCREEN_DESIGN_STANDARDS.md §6). */
export default function Loading() {
  return <PageSkeleton />;
}
