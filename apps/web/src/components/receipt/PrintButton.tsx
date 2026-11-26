"use client";

import { useEffect } from "react";

/**
 * Prints the page in the light theme whatever the screen uses (a dark page prints as pale text on white paper): the theme
 * is set to light while the print dialog is open, including when the person prints from the browser's own menu.
 */
export function PrintButton({ label }: { label: string }) {
  useEffect(() => {
    const root = document.documentElement;
    let before: string | null = null;
    const start = () => {
      before = root.getAttribute("data-theme");
      root.setAttribute("data-theme", "light");
    };
    const end = () => {
      if (before === null) root.removeAttribute("data-theme");
      else root.setAttribute("data-theme", before);
    };
    window.addEventListener("beforeprint", start);
    window.addEventListener("afterprint", end);
    return () => {
      window.removeEventListener("beforeprint", start);
      window.removeEventListener("afterprint", end);
    };
  }, []);
  return (
    <button
      type="button"
      onClick={() => window.print()}
      className="bv-type-label inline-flex min-h-12 items-center justify-center rounded-lg bg-primary px-5 text-on-primary hover:bg-primary-pressed focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus print:hidden"
    >
      {label}
    </button>
  );
}
