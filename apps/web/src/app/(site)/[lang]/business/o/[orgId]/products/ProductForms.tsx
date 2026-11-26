"use client";

import { useActionState } from "react";
import {
  Field,
  FormMessage,
  buttonClass,
  inputClass,
  secondaryButtonClass,
} from "@/components/business/ui";
import {
  addProduct,
  archiveProduct,
  correctStock,
  receiveStock,
  setPrice,
  type FormState,
} from "./actions";

type Labels = Record<string, string>;

export function AddProductForm({
  language,
  orgId,
  labels,
}: {
  language: string;
  orgId: string;
  labels: Labels;
}) {
  const [state, action, pending] = useActionState(
    addProduct.bind(null, language, orgId),
    {} as FormState,
  );
  return (
    <form action={action} className="flex max-w-2xl flex-col gap-4">
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label={labels.nameEn!}>
          <input name="nameEn" maxLength={120} className={inputClass} />
        </Field>
        <Field label={labels.nameAm!}>
          <input name="nameAm" maxLength={120} className={inputClass} />
        </Field>
      </div>
      <Field label={labels.brand!}>
        <input name="brand" maxLength={80} className={inputClass} />
      </Field>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label={labels.variant!}>
          <input name="label" maxLength={60} className={inputClass} />
        </Field>
        <Field label={labels.sku!}>
          <input name="sku" maxLength={60} className={inputClass} />
        </Field>
        <Field label={labels.price!}>
          <input name="price" required inputMode="decimal" className={inputClass} />
        </Field>
        <Field label={labels.cost!}>
          <input name="cost" inputMode="decimal" className={inputClass} />
        </Field>
      </div>
      <label className="flex min-h-11 items-center gap-3">
        <input type="checkbox" name="track" defaultChecked className="size-5 accent-primary" />
        <span className="bv-type-body">{labels.track}</span>
      </label>
      <div className="flex items-center gap-4">
        <button type="submit" className={buttonClass} disabled={pending}>
          {labels.save}
        </button>
        <FormMessage error={state.error} done={state.done} />
      </div>
    </form>
  );
}

export function PriceForm({
  language,
  orgId,
  productId,
  variantId,
  current,
  labels,
}: {
  language: string;
  orgId: string;
  productId: string;
  variantId: string;
  current: string;
  labels: Labels;
}) {
  const [state, action, pending] = useActionState(
    setPrice.bind(null, language, orgId, productId, variantId),
    {} as FormState,
  );
  return (
    <form action={action} className="flex max-w-sm flex-col gap-2">
      <Field label={labels.price!}>
        <input
          name="price"
          required
          inputMode="decimal"
          defaultValue={current}
          className={inputClass}
        />
      </Field>
      <div className="flex items-center gap-3">
        <button type="submit" className={secondaryButtonClass} disabled={pending}>
          {labels.setPrice}
        </button>
        <FormMessage error={state.error} done={state.done} />
      </div>
    </form>
  );
}

export function ReceiveForm({
  language,
  orgId,
  locationId,
  variantId,
  labels,
}: {
  language: string;
  orgId: string;
  locationId: string;
  variantId: string;
  labels: Labels;
}) {
  const [state, action, pending] = useActionState(
    receiveStock.bind(null, language, orgId, locationId, variantId),
    {} as FormState,
  );
  return (
    <form action={action} className="flex max-w-sm flex-col gap-2">
      <Field label={labels.quantity!}>
        <input name="quantity" required inputMode="numeric" className={inputClass} />
      </Field>
      <Field label={labels.reason!}>
        <input name="reason" required minLength={3} maxLength={200} className={inputClass} />
      </Field>
      <div className="flex items-center gap-3">
        <button type="submit" className={secondaryButtonClass} disabled={pending}>
          {labels.save}
        </button>
        <FormMessage error={state.error} done={state.done} />
      </div>
    </form>
  );
}

export function CorrectForm({
  language,
  orgId,
  locationId,
  variantId,
  labels,
}: {
  language: string;
  orgId: string;
  locationId: string;
  variantId: string;
  labels: Labels;
}) {
  const [state, action, pending] = useActionState(
    correctStock.bind(null, language, orgId, locationId, variantId),
    {} as FormState,
  );
  return (
    <form action={action} className="flex max-w-sm flex-col gap-2">
      <Field label={labels.kind!}>
        <select name="kind" defaultValue="ADJUSTMENT" className={inputClass}>
          <option value="ADJUSTMENT">{labels.ADJUSTMENT}</option>
          <option value="DAMAGE">{labels.DAMAGE}</option>
        </select>
      </Field>
      <Field label={labels.change!}>
        <input name="change" required inputMode="numeric" className={inputClass} />
      </Field>
      <Field label={labels.reason!}>
        <input name="reason" required minLength={3} maxLength={200} className={inputClass} />
      </Field>
      <div className="flex items-center gap-3">
        <button type="submit" className={secondaryButtonClass} disabled={pending}>
          {labels.save}
        </button>
        <FormMessage error={state.error} done={state.done} />
      </div>
    </form>
  );
}

export function ArchiveButton({
  language,
  orgId,
  productId,
  label,
}: {
  language: string;
  orgId: string;
  productId: string;
  label: string;
}) {
  return (
    <form action={archiveProduct.bind(null, language, orgId, productId)}>
      <button type="submit" className={secondaryButtonClass}>
        {label}
      </button>
    </form>
  );
}
