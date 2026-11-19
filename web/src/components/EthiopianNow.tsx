"use client";

import {
  formatEthiopianDate,
  formatEthiopianTime,
  gregorianToEthiopian,
  toEthiopianTime,
  zoned,
  type CalendarLanguage,
} from "@beavent/calendar";
import { useSyncExternalStore } from "react";

const TIME_ZONE = "Africa/Addis_Ababa";

// The current minute as a store: re-renders once a minute, and renders nothing on the server (no stale prerender).
const subscribe = (notify: () => void) => {
  const timer = setInterval(notify, 10_000);
  return () => clearInterval(timer);
};
const currentMinute = () => Math.floor(Date.now() / 60_000);
const noMinuteOnServer = () => null;

/** Today's date in the Ethiopian calendar and the time in Ethiopian and international convention (Addis Ababa). */
export function EthiopianNow({
  language,
  labels,
}: {
  language: CalendarLanguage;
  labels: { today: string; now: string };
}) {
  const minute = useSyncExternalStore(subscribe, currentMinute, noMinuteOnServer);
  if (minute === null) return <div className="h-20" aria-hidden />;
  const local = zoned(minute * 60_000, TIME_ZONE);
  const date = formatEthiopianDate(gregorianToEthiopian(local), language, { weekday: true });
  const time = formatEthiopianTime(toEthiopianTime(local.hour, local.minute), language);
  const standard = `${String(local.hour).padStart(2, "0")}:${String(local.minute).padStart(2, "0")}`;
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1">
      <dt className="text-sm opacity-70">{labels.today}</dt>
      <dd>
        {date}{" "}
        <span className="text-sm opacity-70">
          ({local.day}/{local.month}/{local.year})
        </span>
      </dd>
      <dt className="text-sm opacity-70">{labels.now}</dt>
      <dd>
        {time} <span className="text-sm opacity-70">({standard})</span>
      </dd>
    </dl>
  );
}
