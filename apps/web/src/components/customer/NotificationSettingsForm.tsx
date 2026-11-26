"use client";

import { useId, useState, useTransition } from "react";
import type { Language } from "@beavent/i18n";
import {
  QUIET_PRESETS,
  quietChoice,
  quietHoursText,
  type NotificationSettings,
} from "@beavent/venue";
import { saveNotificationSettings } from "@/lib/customer/notification-actions";
import { translator } from "@/lib/i18n";

/**
 * Reminders and quiet hours (plan 8.4): which channels carry reminders and review requests, and when not to be
 * disturbed. Every change is saved as it is made; a line under the form says so (or that it failed).
 */
export function NotificationSettingsForm({
  language,
  initial,
}: {
  language: Language;
  initial: NotificationSettings;
}) {
  const t = translator(language);
  const name = useId();
  const [settings, setSettings] = useState(initial);
  const [state, setState] = useState<"idle" | "saved" | "error">("idle");
  const [, start] = useTransition();

  const change = (patch: Partial<NotificationSettings>) => {
    const before = settings;
    const next = { ...settings, ...patch };
    setSettings(next);
    setState("idle");
    start(async () => {
      const result = await saveNotificationSettings(language, next);
      if (result.ok) setState("saved");
      else {
        setSettings(before);
        setState("error");
      }
    });
  };

  const choice = quietChoice(settings);
  const row =
    "flex min-h-14 cursor-pointer items-center gap-3 py-2 has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-focus";
  const input = "size-5 accent-primary";

  return (
    <div className="flex flex-col gap-8">
      <fieldset className="flex flex-col gap-1">
        <legend className="bv-type-title2 text-fg">{t("notifications.reminders")}</legend>
        <p className="bv-type-body max-w-prose text-fg-muted">
          {t("notifications.reminders.hint")}
        </p>
        <div className="divide-y divide-border border-y border-border">
          <label className={row}>
            <input
              type="checkbox"
              className={input}
              checked={settings.remindersSms}
              onChange={(e) => change({ remindersSms: e.target.checked })}
            />
            <span className="bv-type-body text-fg">{t("notifications.sms")}</span>
          </label>
          <label className={row}>
            <input
              type="checkbox"
              className={input}
              checked={settings.remindersEmail}
              onChange={(e) => change({ remindersEmail: e.target.checked })}
            />
            <span className="bv-type-body text-fg">{t("notifications.email")}</span>
          </label>
        </div>
      </fieldset>

      <fieldset className="flex flex-col gap-1">
        <legend className="bv-type-title2 text-fg">{t("notifications.quiet")}</legend>
        <p className="bv-type-body max-w-prose text-fg-muted">{t("notifications.quiet.hint")}</p>
        <div className="divide-y divide-border border-y border-border">
          <label className={row}>
            <input
              type="radio"
              name={name}
              className={input}
              checked={choice === "off"}
              onChange={() => change({ quietHours: false })}
            />
            <span className="bv-type-body text-fg">{t("notifications.quiet.off")}</span>
          </label>
          {QUIET_PRESETS.map((preset, i) => {
            const text = quietHoursText(preset.from, preset.to, language, t);
            return (
              <label key={preset.from} className={row}>
                <input
                  type="radio"
                  name={name}
                  className={input}
                  checked={choice === i}
                  onChange={() =>
                    change({ quietHours: true, quietFrom: preset.from, quietTo: preset.to })
                  }
                />
                <span className="flex flex-col">
                  <span className="bv-type-body text-fg">{text.primary}</span>
                  <span className="bv-type-caption text-fg-muted">{text.secondary}</span>
                </span>
              </label>
            );
          })}
          {choice === "other" ? (
            <p className="bv-type-body py-3 text-fg">
              {quietHoursText(settings.quietFrom, settings.quietTo, language, t).primary}
            </p>
          ) : null}
        </div>
      </fieldset>

      <p
        role={state === "error" ? "alert" : "status"}
        className={`bv-type-label min-h-6 ${state === "error" ? "text-danger" : "text-success"}`}
      >
        {state === "saved"
          ? t("notifications.saved")
          : state === "error"
            ? t("notifications.error")
            : ""}
      </p>
    </div>
  );
}
