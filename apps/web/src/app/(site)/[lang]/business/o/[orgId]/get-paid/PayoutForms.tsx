"use client";

import { useActionState } from "react";
import {
  Field,
  FormMessage,
  buttonClass,
  inputClass,
  secondaryButtonClass,
} from "@/components/business/ui";
import { addPayoutAccount, withdrawPayoutAccount, type PayoutState } from "./actions";

export interface BankOption {
  code: string;
  kind: "BANK" | "MOBILE_MONEY";
  name: string;
}

export type PayoutLabels = Record<
  | "bank"
  | "bankChoose"
  | "banks"
  | "wallets"
  | "holder"
  | "holderHint"
  | "number"
  | "numberHint"
  | "send"
  | "codeSentTo"
  | "confirm"
  | "saved"
  | "withdraw"
  | "withdrawConfirm"
  | "withdrawn",
  string
>;

/** Two steps: details, then the code sent to the owner's phone. The code protects the payout account. */
export function AddAccountForm({
  language,
  orgId,
  banks,
  holderName,
  labels,
}: {
  language: string;
  orgId: string;
  banks: readonly BankOption[];
  holderName: string;
  labels: PayoutLabels;
}) {
  const [state, action, pending] = useActionState(
    addPayoutAccount.bind(null, language, orgId),
    {} as PayoutState,
  );
  const second = Boolean(state.challengeId) && !state.done;
  const values = state.values;
  return (
    <form action={action} className="flex max-w-xl flex-col gap-4">
      <Field label={labels.bank}>
        <select
          key={second ? "locked" : "open"}
          name="institutionCode"
          required
          defaultValue={values?.institutionCode ?? ""}
          disabled={second}
          className={inputClass}
        >
          <option value="" disabled>
            {labels.bankChoose}
          </option>
          <optgroup label={labels.banks}>
            {banks
              .filter((b) => b.kind === "BANK")
              .map((b) => (
                <option key={b.code} value={b.code}>
                  {b.name}
                </option>
              ))}
          </optgroup>
          <optgroup label={labels.wallets}>
            {banks
              .filter((b) => b.kind === "MOBILE_MONEY")
              .map((b) => (
                <option key={b.code} value={b.code}>
                  {b.name}
                </option>
              ))}
          </optgroup>
        </select>
      </Field>
      <Field label={labels.holder} hint={labels.holderHint}>
        <input
          name="accountName"
          required
          maxLength={200}
          defaultValue={values?.accountName ?? holderName}
          readOnly={second}
          className={inputClass}
        />
      </Field>
      <Field label={labels.number} hint={labels.numberHint}>
        <input
          name="accountNumber"
          required
          inputMode="numeric"
          autoComplete="off"
          maxLength={40}
          defaultValue={values?.accountNumber ?? ""}
          readOnly={second}
          className={inputClass}
        />
      </Field>
      {second && (
        <Field label={`${labels.codeSentTo} ${state.sentTo ?? ""}`}>
          <input
            name="code"
            required
            inputMode="numeric"
            autoComplete="one-time-code"
            className={inputClass}
            autoFocus
          />
        </Field>
      )}
      <FormMessage error={state.error} done={state.done ? labels.saved : undefined} />
      <button disabled={pending || state.done} className={`${buttonClass} self-start`}>
        {second ? labels.confirm : labels.send}
      </button>
    </form>
  );
}

/** Withdraws the account waiting for review: also behind a code. */
export function WithdrawForm({
  language,
  orgId,
  accountId,
  labels,
}: {
  language: string;
  orgId: string;
  accountId: string;
  labels: PayoutLabels;
}) {
  const [state, action, pending] = useActionState(
    withdrawPayoutAccount.bind(null, language, orgId, accountId),
    {} as PayoutState,
  );
  const second = Boolean(state.challengeId) && !state.done;
  return (
    <form action={action} className="flex flex-wrap items-end gap-3">
      {second && (
        <Field label={`${labels.codeSentTo} ${state.sentTo ?? ""}`}>
          <input
            name="code"
            required
            inputMode="numeric"
            autoComplete="one-time-code"
            className={`${inputClass} w-48`}
            autoFocus
          />
        </Field>
      )}
      <button disabled={pending || state.done} className={secondaryButtonClass}>
        {second ? labels.withdrawConfirm : labels.withdraw}
      </button>
      <FormMessage error={state.error} done={state.done ? labels.withdrawn : undefined} />
    </form>
  );
}
