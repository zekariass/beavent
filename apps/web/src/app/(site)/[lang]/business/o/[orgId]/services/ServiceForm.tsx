"use client";

import { useActionState, useState } from "react";
import {
  Field,
  FormMessage,
  buttonClass,
  inputClass,
  secondaryButtonClass,
} from "@/components/business/ui";
import { saveService, type FormState, type VariantRow } from "./actions";

export interface ServiceFormProps {
  language: string;
  orgId: string;
  serviceId: string | null;
  version: number | null;
  categories: { id: string; label: string }[];
  groups: { id: string; label: string }[];
  locations: { id: string; label: string }[];
  initial: {
    nameAm: string;
    nameEn: string;
    categoryId: string;
    groupId: string;
    locationIds: string[];
    variants: VariantRow[];
  };
  labels: Record<string, string>;
}

export function ServiceForm(props: ServiceFormProps) {
  const { labels } = props;
  const [state, action, pending] = useActionState(
    saveService.bind(null, props.language, props.orgId, props.serviceId, props.version),
    {} as FormState,
  );
  const [value, setValue] = useState(props.initial);
  const setVariant = (i: number, change: Partial<VariantRow>) =>
    setValue((v) => ({
      ...v,
      variants: v.variants.map((row, j) => (j === i ? { ...row, ...change } : row)),
    }));
  return (
    <form action={action} className="flex flex-col gap-4">
      <input type="hidden" name="payload" value={JSON.stringify(value)} />
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label={labels.nameAm!}>
          <input
            lang="am"
            value={value.nameAm}
            onChange={(e) => setValue({ ...value, nameAm: e.target.value })}
            className={inputClass}
          />
        </Field>
        <Field label={labels.nameEn!}>
          <input
            lang="en"
            value={value.nameEn}
            onChange={(e) => setValue({ ...value, nameEn: e.target.value })}
            className={inputClass}
          />
        </Field>
        <Field label={labels.category!}>
          <select
            value={value.categoryId}
            onChange={(e) => setValue({ ...value, categoryId: e.target.value })}
            className={inputClass}
          >
            {props.categories.map((c) => (
              <option key={c.id} value={c.id}>
                {c.label}
              </option>
            ))}
          </select>
        </Field>
        <Field label={labels.section!}>
          <select
            value={value.groupId}
            onChange={(e) => setValue({ ...value, groupId: e.target.value })}
            className={inputClass}
          >
            <option value="">{labels.none}</option>
            {props.groups.map((g) => (
              <option key={g.id} value={g.id}>
                {g.label}
              </option>
            ))}
          </select>
        </Field>
      </div>
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-sm">{labels.locations}</legend>
        {props.locations.map((l) => (
          <label key={l.id} className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={value.locationIds.includes(l.id)}
              onChange={(e) =>
                setValue({
                  ...value,
                  locationIds: e.target.checked
                    ? [...value.locationIds, l.id]
                    : value.locationIds.filter((id) => id !== l.id),
                })
              }
            />
            {l.label}
          </label>
        ))}
      </fieldset>
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-sm">{labels.variants}</legend>
        {value.variants.map((v, i) => (
          <div key={i} className="grid grid-cols-[1fr_6rem_8rem_auto] items-end gap-2">
            <Field label={labels.variantName!}>
              <input
                value={v.name}
                onChange={(e) => setVariant(i, { name: e.target.value })}
                className={inputClass}
              />
            </Field>
            <Field label={labels.duration!}>
              <input
                type="number"
                min={5}
                max={1440}
                step={5}
                required
                value={v.durationMinutes}
                onChange={(e) => setVariant(i, { durationMinutes: Number(e.target.value) })}
                className={inputClass}
              />
            </Field>
            <Field label={labels.price!}>
              <input
                inputMode="decimal"
                required
                value={v.priceBirr}
                onChange={(e) => setVariant(i, { priceBirr: e.target.value })}
                className={inputClass}
              />
            </Field>
            <button
              type="button"
              disabled={value.variants.length === 1}
              onClick={() =>
                setValue({ ...value, variants: value.variants.filter((_, j) => j !== i) })
              }
              className={secondaryButtonClass}
            >
              {labels.remove}
            </button>
          </div>
        ))}
        <button
          type="button"
          onClick={() =>
            setValue({
              ...value,
              variants: [...value.variants, { name: "", durationMinutes: 30, priceBirr: "" }],
            })
          }
          className={`${secondaryButtonClass} self-start`}
        >
          {labels.addVariant}
        </button>
      </fieldset>
      <FormMessage error={state.error} done={state.done} />
      <button disabled={pending} className={`${buttonClass} self-start`}>
        {labels.save}
      </button>
    </form>
  );
}
