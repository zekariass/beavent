"use client";

import { useState, useTransition } from "react";
import type { Language } from "@beavent/i18n";
import { pick, type Followed } from "@beavent/venue";
import { setFollowing } from "@/lib/customer/saved-actions";
import { translator } from "@/lib/i18n";

/** One followed business: its name, whether to be told about its news, and a way to stop following. */
export function FollowingRow({ language, item }: { language: Language; item: Followed }) {
  const t = translator(language);
  const [gone, setGone] = useState(false);
  const [notifications, setNotifications] = useState(item.notifications);
  const [pending, start] = useTransition();
  if (gone) return null;
  return (
    <li className="flex flex-col gap-3 border-b border-border py-4 sm:flex-row sm:items-center sm:justify-between">
      <div className="flex flex-col">
        <a
          href={`/${language}/b/${item.businessSlug}`}
          className="bv-type-body font-semibold text-fg hover:text-primary focus-visible:outline-2 focus-visible:outline-focus"
        >
          {item.name}
        </a>
        <span className="bv-type-label text-fg-muted">{pick(item.category, language)}</span>
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <label className="bv-type-label flex min-h-12 items-center gap-2 text-fg">
          <input
            type="checkbox"
            checked={notifications}
            disabled={pending}
            onChange={(e) => {
              const next = e.target.checked;
              setNotifications(next);
              start(async () => {
                const r = await setFollowing(language, item.businessSlug, true, next);
                if (!r.ok) setNotifications(!next);
              });
            }}
            className="size-5 accent-primary"
          />
          {t("following.notify")}
        </label>
        <button
          type="button"
          disabled={pending}
          onClick={() =>
            start(async () => {
              const r = await setFollowing(language, item.businessSlug, false);
              if (r.ok) setGone(true);
            })
          }
          className="bv-type-label inline-flex min-h-12 items-center rounded-lg border border-border-strong px-4 text-fg hover:bg-surface focus-visible:outline-2 focus-visible:outline-focus disabled:text-fg-subtle"
        >
          {t("follow.stop")}
        </button>
      </div>
    </li>
  );
}
