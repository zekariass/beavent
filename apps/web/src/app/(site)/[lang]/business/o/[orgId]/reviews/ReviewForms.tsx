"use client";

import { useActionState } from "react";
import {
  buttonClass,
  Field,
  FormMessage,
  inputClass,
  secondaryButtonClass,
} from "@/components/business/ui";
import { removeReply, reply, report, type ReviewState } from "./actions";

export interface Labels {
  reply: string;
  replyEdit: string;
  replyLabel: string;
  replySend: string;
  replyRemove: string;
  report: string;
  reportHint: string;
  reportReason: string;
  reportDetails: string;
  reportSend: string;
  reasons: Record<string, string>;
}

/** The reply to a review: write it, change it, or take it back. */
export function ReplyForm({
  language,
  orgId,
  reviewId,
  current,
  labels,
}: {
  language: string;
  orgId: string;
  reviewId: string;
  current?: string;
  labels: Labels;
}) {
  const [state, action, pending] = useActionState(
    reply.bind(null, language, orgId, reviewId),
    {} as ReviewState,
  );
  const [removed, remove, removing] = useActionState(
    () => removeReply(language, orgId, reviewId),
    {} as ReviewState,
  );
  return (
    <details className="w-full">
      <summary className="bv-type-label inline-flex min-h-11 cursor-pointer items-center text-primary">
        {current ? labels.replyEdit : labels.reply}
      </summary>
      <div className="mt-2 flex max-w-xl flex-col gap-3">
        <form action={action} className="flex flex-col gap-3">
          <Field label={labels.replyLabel}>
            <textarea
              name="body"
              required
              maxLength={1000}
              rows={3}
              defaultValue={current}
              className={inputClass}
            />
          </Field>
          <FormMessage error={state.error} />
          <button disabled={pending} className={`self-start ${buttonClass}`}>
            {labels.replySend}
          </button>
        </form>
        {current ? (
          <form action={remove}>
            <FormMessage error={removed.error} />
            <button disabled={removing} className={secondaryButtonClass}>
              {labels.replyRemove}
            </button>
          </form>
        ) : null}
      </div>
    </details>
  );
}

/** Ask staff to look at a review that breaks the rules. */
export function ReportForm({
  language,
  orgId,
  reviewId,
  labels,
}: {
  language: string;
  orgId: string;
  reviewId: string;
  labels: Labels;
}) {
  const [state, action, pending] = useActionState(
    report.bind(null, language, orgId, reviewId),
    {} as ReviewState,
  );
  return (
    <details className="w-full">
      <summary className="bv-type-label inline-flex min-h-11 cursor-pointer items-center text-fg-muted">
        {labels.report}
      </summary>
      <form action={action} className="mt-2 flex max-w-xl flex-col gap-3">
        <p className="bv-type-caption text-fg-muted">{labels.reportHint}</p>
        <Field label={labels.reportReason}>
          <select name="reason" className={inputClass} defaultValue="INAPPROPRIATE">
            {Object.entries(labels.reasons).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </Field>
        <Field label={labels.reportDetails}>
          <textarea name="details" maxLength={500} rows={2} className={inputClass} />
        </Field>
        <FormMessage error={state.error} done={state.done} />
        <button disabled={pending} className={`self-start ${secondaryButtonClass}`}>
          {labels.reportSend}
        </button>
      </form>
    </details>
  );
}
