"use client";

import { useActionState } from "react";
import { resolveException, uploadStatement, type ReconState } from "./actions";

const field = "rounded-lg border border-border-strong px-3 py-2";
const button =
  "self-start rounded-lg bg-primary px-4 py-2 font-medium text-on-primary hover:bg-primary-pressed disabled:opacity-50";

function Result({ state }: { state: ReconState }) {
  return (
    <>
      {state.error && (
        <p role="alert" className="text-danger">
          {state.error}
        </p>
      )}
      {state.done && (
        <p role="status" className="text-success">
          {state.done}
        </p>
      )}
    </>
  );
}

/** A statement for some days of one provider account, as a CSV file. */
export function UploadForm({
  accounts,
}: {
  accounts: { id: string; provider: string; name: string; environment: string }[];
}) {
  const [state, action, pending] = useActionState(uploadStatement, {} as ReconState);
  return (
    <form action={action} className="flex max-w-xl flex-col gap-3 text-sm">
      <label className="flex flex-col gap-1">
        Provider account
        <select name="account" required className={field}>
          {accounts.map((a) => (
            <option key={a.id} value={a.id}>
              {a.provider} · {a.name} · {a.environment}
            </option>
          ))}
        </select>
      </label>
      <div className="grid gap-3 sm:grid-cols-2">
        <label className="flex flex-col gap-1">
          First day
          <input type="date" name="from" required className={field} />
        </label>
        <label className="flex flex-col gap-1">
          Last day
          <input type="date" name="to" required className={field} />
        </label>
        <label className="flex flex-col gap-1">
          Balance at the start (birr, optional)
          <input name="opening" inputMode="decimal" className={field} />
        </label>
        <label className="flex flex-col gap-1">
          Balance at the end (birr, optional)
          <input name="closing" inputMode="decimal" className={field} />
        </label>
      </div>
      <label className="flex flex-col gap-1">
        Statement file (CSV with reference, type, amount, date and optionally fee)
        <input
          type="file"
          name="file"
          accept=".csv,text/csv,text/plain"
          required
          className={field}
        />
      </label>
      <Result state={state} />
      <button disabled={pending} className={button}>
        Import and match
      </button>
    </form>
  );
}

/** Why an exception is fine; kept with who wrote it. */
export function ResolveForm({
  statementId,
  lineId,
  attemptId,
}: {
  statementId: string;
  lineId: string | null;
  attemptId: string | null;
}) {
  const [state, action, pending] = useActionState(
    resolveException.bind(null, statementId, lineId, attemptId),
    {} as ReconState,
  );
  return (
    <form action={action} className="flex max-w-xl flex-col gap-2 text-sm">
      <label className="flex flex-col gap-1">
        Why is this fine? (audited)
        <input name="note" required minLength={3} maxLength={500} className={field} />
      </label>
      <Result state={state} />
      <button disabled={pending} className={button}>
        Mark as explained
      </button>
    </form>
  );
}
