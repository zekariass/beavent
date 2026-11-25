"use client";

import { ListError } from "@/components/discovery/ListError";

export default function SearchError({ reset }: { error: Error; reset: () => void }) {
  return <ListError reset={reset} />;
}
